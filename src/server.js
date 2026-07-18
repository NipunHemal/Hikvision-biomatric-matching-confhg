const path = require("path");
const fs = require("fs");
const express = require("express");
const multer = require("multer");

const config = require("./config");
const { insertEvent, listEvents, getEvent } = require("./db");
const { extractAlert, normalise, isHeartbeat } = require("./parseEvent");
const { IsapiClient, DeviceError } = require("./isapi");
const { backfill } = require("./backfill");
const { captureRaw, logRequest, recent, logEvent } = require("./debug");

const app = express();
const device = new IsapiClient();

const picturesDir = path.join(__dirname, "..", "data", "pictures");
fs.mkdirSync(picturesDir, { recursive: true });

// Must come first: tees the raw bytes before the parsers consume the stream.
app.use(captureRaw);

// The device sends JSON, multipart, and (on some firmwares) XML with a
// text/plain content-type. Accept the lot and let the parser sort it out.
app.use(
  express.json({ type: ["application/json", "text/json"], limit: "10mb" }),
);
app.use(express.raw({ type: ["text/*", "application/xml"], limit: "10mb" }));

const upload = multer({
  storage: config.savePictures
    ? multer.diskStorage({
        destination: picturesDir,
        filename: (req, file, cb) =>
          cb(null, `${Date.now()}-${file.originalname || "snapshot.jpg"}`),
      })
    : multer.memoryStorage(),
  limits: { fileSize: 8 * 1024 * 1024, files: 4 },
});
const memUpload = multer({ limits: { fileSize: 1024 * 1024 } });

// Optional Basic auth, matching httpAuthenticationMethod on the device.
function basicAuth(req, res, next) {
  if (!config.webhookUser) return next();

  const header = req.get("authorization") || "";
  const [scheme, encoded] = header.split(" ");
  if (scheme === "Basic" && encoded) {
    const [user, pass] = Buffer.from(encoded, "base64").toString().split(":");
    if (user === config.webhookUser && pass === config.webhookPass)
      return next();
  }
  res.set("WWW-Authenticate", 'Basic realm="hik-webhook"');
  return res.status(401).end();
}

// Wraps async handlers so device errors become clean HTTP responses.
const route = (fn) => (req, res, next) =>
  Promise.resolve(fn(req, res)).catch(next);

// --- Webhook ---------------------------------------------------------------

app.all("/health", logRequest, (req, res) =>
  res.json({ ok: true, uptime: process.uptime() }),
);

// The last 20 inbound requests, newest first (needs DEBUG_REQUESTS=true).
app.get("/debug/requests", (req, res) => res.json(recent()));

// The device POSTs here on every authentication (fingerprint, face, card, PIN).
app.post(config.webhookPath, basicAuth, upload.any(), logRequest, (req, res) => {
  // Always ack fast — the terminal retries and queues locally on a non-2xx.
  res.status(200).json({ ok: true });

  let alert;
  try {
    alert = extractAlert(req);
  } catch (err) {
    console.error("[webhook] unparseable payload:", err.message);
    return;
  }
  if (!alert) return;

  const picture = (req.files || []).find((f) => f.path);
  const event = normalise(alert, {
    picturePath: picture
      ? path.relative(path.join(__dirname, ".."), picture.path)
      : null,
  });

  if (isHeartbeat(event)) return;

  const id = insertEvent(event);
  if (id === null) return; // duplicate retry from the device

  logEvent(id, event, alert);
});

// --- Events ----------------------------------------------------------------

app.get("/events", (req, res) => {
  const limit = Math.min(Number(req.query.limit) || 50, 500);
  const { employeeNo, method, since } = req.query;
  res.json(listEvents({ limit, employeeNo, method, since }));
});

// The exact JSON the device sent for one stored event.
app.get("/events/:id/raw", (req, res) => {
  const row = getEvent(req.params.id);
  if (!row) return res.status(404).json({ error: "no such event" });
  res.json(JSON.parse(row.raw));
});

// Replay the device's own log to fill gaps from downtime.
app.post(
  "/events/backfill",
  route(async (req, res) => {
    res.json(
      await backfill({ startTime: req.query.start, endTime: req.query.end }),
    );
  }),
);

app.use("/pictures", express.static(picturesDir));

// --- Device management -----------------------------------------------------

app.get(
  "/device/info",
  route(async (req, res) => res.json(await device.deviceInfo())),
);
app.get(
  "/device/time",
  route(async (req, res) => res.json(await device.time())),
);
app.get(
  "/device/hosts",
  route(async (req, res) => res.json(await device.httpHosts())),
);

app.get(
  "/persons",
  route(async (req, res) => {
    const out = [];
    for await (const p of device.persons()) out.push(p);
    res.json(out);
  }),
);

app.post(
  "/persons",
  route(async (req, res) => {
    if (!req.body?.employeeNo)
      return res.status(400).json({ error: "employeeNo is required" });
    res.json(await device.upsertPerson(req.body));
  }),
);

app.delete(
  "/persons/:employeeNo",
  route(async (req, res) =>
    res.json(await device.deletePerson(req.params.employeeNo)),
  ),
);

app.post(
  "/persons/:employeeNo/card",
  route(async (req, res) => {
    if (!req.body?.cardNo)
      return res.status(400).json({ error: "cardNo is required" });
    res.json(
      await device.assignCard(
        req.params.employeeNo,
        req.body.cardNo,
        req.body.cardType,
      ),
    );
  }),
);

// Enrol a face from an uploaded JPEG (field name: "face").
app.post(
  "/persons/:employeeNo/face",
  memUpload.single("face"),
  route(async (req, res) => {
    if (!req.file)
      return res.status(400).json({ error: 'attach a JPEG as field "face"' });
    res.json(await device.uploadFace(req.params.employeeNo, req.file.buffer));
  }),
);

// List a person's enrolled fingerprints.
app.get(
  "/persons/:employeeNo/fingerprints",
  route(async (req, res) =>
    res.json(await device.listFingerprints(req.params.employeeNo)),
  ),
);

// Enrol a fingerprint. Two modes:
//   {}                     -> ask the terminal to scan now, then apply
//   {fingerData:"<b64>"}   -> apply a template captured earlier
// The scan mode blocks until a finger is presented on the terminal.
app.post(
  "/persons/:employeeNo/fingerprint",
  route(async (req, res) => {
    const { employeeNo } = req.params;
    const fingerPrintID = Number(req.body?.fingerPrintID) || 1;
    if (fingerPrintID < 1 || fingerPrintID > 10) {
      return res.status(400).json({ error: "fingerPrintID must be 1-10" });
    }

    let { fingerData } = req.body || {};
    let quality = null;
    if (!fingerData) {
      const captured = await device.captureFingerprint(fingerPrintID);
      fingerData = captured.fingerData;
      quality = captured.quality;
    }

    await device.applyFingerprint({ employeeNo, fingerData, fingerPrintID });
    res.json({ employeeNo, fingerPrintID, quality, applied: true });
  }),
);

// Capture a template without assigning it — useful for enrolling the same
// finger onto several terminals.
app.post(
  "/fingerprint/capture",
  route(async (req, res) =>
    res.json(await device.captureFingerprint(Number(req.body?.fingerNo) || 1)),
  ),
);

// Express 5 has no optional :param? — one route per arity.
app.delete(
  "/persons/:employeeNo/fingerprints",
  route(async (req, res) =>
    res.json(await device.deleteFingerprint(req.params.employeeNo)),
  ),
);

app.delete(
  "/persons/:employeeNo/fingerprints/:fingerPrintID",
  route(async (req, res) =>
    res.json(
      await device.deleteFingerprint(
        req.params.employeeNo,
        req.params.fingerPrintID,
      ),
    ),
  ),
);

app.put(
  "/doors/:doorNo",
  route(async (req, res) =>
    res.json(
      await device.controlDoor(
        Number(req.params.doorNo),
        req.body?.cmd || "open",
      ),
    ),
  ),
);

// --- Errors ----------------------------------------------------------------

app.use((err, req, res, next) => {
  if (err instanceof DeviceError) {
    console.error(`[device] ${err.message}`);
    return res.status(502).json({
      error: err.message,
      subStatusCode: err.subStatusCode,
      statusCode: err.statusCode,
    });
  }
  console.error("[error]", err);
  res.status(500).json({ error: err.message });
});

app.listen(config.port, () => {
  const root = `http://${config.listenerHost}:${config.port}`;
  console.log(`Webhook listening on ${root}${config.webhookPath}`);
  console.log(`Events API   ${root}/events`);
  console.log(`Device API   ${root}/device/info  ${root}/persons`);
});

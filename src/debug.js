// Captures raw inbound requests so you can see exactly what the device sends.
// Enable with DEBUG_REQUESTS=true; inspect via GET /debug/requests.
const config = require('./config');

const RING_SIZE = 20;
const ring = [];

function record(entry) {
  ring.push(entry);
  if (ring.length > RING_SIZE) ring.shift();
}

function recent() {
  return [...ring].reverse();
}

// Body must be captured before express.json()/multer consume the stream, so this
// tees the raw bytes rather than reading req.body (which is not populated yet).
function captureRaw(req, res, next) {
  if (!config.debugRequests) return next();

  const chunks = [];
  req.on('data', (c) => chunks.length < 64 && chunks.push(c));
  req.on('end', () => {
    req.rawBodyForDebug = Buffer.concat(chunks);
  });
  next();
}

// Runs after body parsing so parsed fields and files are available too.
function logRequest(req, res, next) {
  if (!config.debugRequests) return next();

  const raw = req.rawBodyForDebug || Buffer.alloc(0);
  const contentType = req.get('content-type') || '(none)';
  const isMultipart = contentType.includes('multipart');

  const entry = {
    at: new Date().toISOString(),
    method: req.method,
    path: req.originalUrl,
    from: req.ip,
    contentType,
    contentLength: Number(req.get('content-length') || raw.length),
    headers: req.headers,
    // Binary parts would be unreadable; show text only.
    body: isMultipart
      ? { note: 'multipart — see fields/files', fields: req.body }
      : req.body,
    files: (req.files || []).map((f) => ({
      field: f.fieldname,
      filename: f.originalname,
      bytes: f.size,
      saved: f.path || null,
    })),
    rawPreview: isMultipart ? null : raw.toString('utf8').slice(0, 2000),
  };

  record(entry);

  console.log(
    `\n[req] ${entry.method} ${entry.path} from ${entry.from}\n` +
      `      content-type: ${entry.contentType}  (${entry.contentLength} bytes)\n` +
      `      headers: ${JSON.stringify(entry.headers)}\n` +
      `      body: ${JSON.stringify(entry.body)}` +
      (entry.files.length ? `\n      files: ${JSON.stringify(entry.files)}` : '')
  );

  next();
}

// Human-readable dump of a stored punch. Always printed, unlike logRequest —
// this is the operational log, not a wiring-up aid.
function logEvent(id, event, alert) {
  const line = (label, value) =>
    value === null || value === undefined || value === ''
      ? null
      : `  ${label.padEnd(12)}${value}`;

  const result =
    event.success === 1 ? 'SUCCESS' : event.success === 0 ? 'FAILED' : 'unknown';

  // An unmapped code is worth shouting about: it means eventCodes.js needs a
  // new entry before this punch type can be classified.
  const heading = event.eventName
    ? `${event.eventName}  (major ${event.majorType} / minor ${event.minorType})`
    : `UNMAPPED EVENT CODE — major ${event.majorType} / minor ${event.minorType} ` +
      `(0x${Number(event.minorType).toString(16)}) — add it to src/eventCodes.js`;

  const rows = [
    line('Event', heading),
    line('Time', event.eventTime),
    line('Employee', event.employeeNo),
    line('Name', event.personName),
    line('Card', event.cardNo),
    line('Method', `${event.verifyMethod || '?'}   ->  ${result}`),
    line('Verify mode', event.verifyMode),
    line('Attendance', event.attendance),
    line('Door', event.doorNo),
    line('Device', event.deviceIp),
    line('Serial', event.serialNo),
    line('Picture', event.picturePath),
  ].filter(Boolean);

  console.log(
    `\n${'='.repeat(64)}\n` +
      `  PUNCH #${id}\n` +
      `${'-'.repeat(64)}\n` +
      `${rows.join('\n')}\n` +
      `${'-'.repeat(64)}\n` +
      `  Raw payload from device:\n` +
      `${JSON.stringify(alert, null, 2)
        .split('\n')
        .map((l) => `  ${l}`)
        .join('\n')}\n` +
      `${'='.repeat(64)}\n`
  );
}

module.exports = { captureRaw, logRequest, recent, logEvent };

// A simulated DS-K1T808MFWX-B access terminal.
//
// Speaks enough ISAPI to stand in for the real thing during development:
// digest auth, person/card/fingerprint management, webhook registration, an
// event log that backfill can search, and punch triggering.
//
// Fidelity notes — these mirror quirks of the real V3.25.x firmware, because
// code that only works against a friendlier mock will break on the device:
//   * digest auth on every request
//   * deviceInfo / httpHosts answer in XML even with ?format=json
//   * httpHosts PUT rejects a JSON body ("badXmlFormat")
//   * webhook passwords outside 8-16 chars are rejected ("badXmlContent")
//   * CaptureFingerPrint is XML-only and replies multipart
//   * the event log is a ring buffer
//
//   node simulator/device.js            (port 8100)
//   node simulator/device.js --port 9000 --capacity 500
const http = require('http');
const crypto = require('crypto');
const { URL } = require('url');

const arg = (name, fallback) => {
  const i = process.argv.indexOf(`--${name}`);
  return i === -1 ? fallback : process.argv[i + 1];
};

const PORT = Number(arg('port', 8100));
const USER = arg('user', 'admin');
const PASS = arg('pass', 'simulator123');
const REALM = 'DS-K1T808MFWX-B';
const CAPACITY = Number(arg('capacity', 100000)); // ring buffer size

// Identity, so several simulators are distinguishable in one backend. The
// backend keys events on ipAddress, so each device needs its own.
const NAME = arg('name', 'Access Controller (SIMULATED)');
const DEVICE_IP = arg('ip', `127.0.0.${(PORT % 250) + 2}`);
const SERIAL = arg('serial', `SIM${String(PORT).padStart(5, '0')}`);

// Optional self-registration: skips needing the backend to register each
// device, which matters when running a fleet.
const WEBHOOK = arg('webhook', null);

const md5 = (s) => crypto.createHash('md5').update(s).digest('hex');
const nowIso = () => {
  // Local time with offset, matching how the terminal stamps events.
  const d = new Date();
  const off = -d.getTimezoneOffset();
  const sign = off >= 0 ? '+' : '-';
  const pad = (n) => String(Math.floor(Math.abs(n))).padStart(2, '0');
  return (
    d.toISOString().slice(0, 19).replace('Z', '') +
    `${sign}${pad(off / 60)}:${pad(off % 60)}`
  );
};

// --- device state ---------------------------------------------------------

const state = {
  online: true, // when false, webhook pushes are suppressed (device unreachable)
  persons: new Map(), // employeeNo -> UserInfo
  cards: new Map(), // employeeNo -> [cardNo]
  fingerprints: new Map(), // employeeNo -> Map(fingerPrintID -> fingerData)
  faces: new Set(), // employeeNo
  httpHosts: new Map(), // id -> config
  events: [], // ring buffer
  serialNo: 1000,
  pushed: 0,
  pushFailures: 0,
};

// Confirmed minor codes. PIN has no code I could confirm from Hikvision's
// documentation, so it is left unset rather than invented — pass an explicit
// `minor` to the punch API to exercise it.
const MINOR = {
  fingerprint: 113,
  card: 38,
  face: 75,
  faceFail: 76,
  exitButton: 27,
};

function addEvent(evt) {
  state.events.push(evt);
  if (state.events.length > CAPACITY) state.events.shift(); // ring buffer
  return evt;
}

// --- webhook push ---------------------------------------------------------

async function pushEvent(evt) {
  const host = state.httpHosts.get('1');
  if (!host) return { pushed: false, reason: 'no webhook registered' };
  if (!state.online) return { pushed: false, reason: 'device is offline' };

  const url = `http://${host.ipAddress}:${host.portNo}${host.url}`;
  const headers = { 'Content-Type': 'application/json' };
  if (host.httpAuthenticationMethod === 'basic' && host.userName) {
    headers.Authorization =
      'Basic ' + Buffer.from(`${host.userName}:${host.password}`).toString('base64');
  }

  const body = {
    ipAddress: DEVICE_IP,
    portNo: PORT,
    protocol: 'HTTP',
    macAddress: 'de:ad:be:ef:00:01',
    channelID: 1,
    dateTime: evt.time,
    activePostCount: ++state.pushed,
    eventType: 'AccessControllerEvent',
    eventState: 'active',
    eventDescription: 'Access Controller Event',
    AccessControllerEvent: {
      deviceName: NAME,
      majorEventType: evt.major,
      subEventType: evt.minor,
      cardReaderKind: 1,
      cardReaderNo: 1,
      doorNo: evt.doorNo,
      serialNo: evt.serialNo,
      ...(evt.employeeNoString && { employeeNoString: evt.employeeNoString }),
      ...(evt.name && { name: evt.name }),
      ...(evt.cardNo && { cardNo: evt.cardNo }),
      ...(evt.currentVerifyMode && { currentVerifyMode: evt.currentVerifyMode }),
      ...(evt.attendanceStatus && { attendanceStatus: evt.attendanceStatus }),
    },
  };

  try {
    const res = await fetch(url, {
      method: 'POST',
      headers,
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(5000),
    });
    if (!res.ok) {
      state.pushFailures += 1;
      console.log(`  push -> ${url} FAILED ${res.status} (event kept in device log)`);
      return { pushed: false, reason: `HTTP ${res.status}` };
    }
    console.log(`  push -> ${url} ${res.status}`);
    return { pushed: true, status: res.status };
  } catch (err) {
    state.pushFailures += 1;
    console.log(`  push -> ${url} FAILED ${err.message} (event kept in device log)`);
    return { pushed: false, reason: err.message };
  }
}

// Heartbeat: the real terminal posts a keep-alive with no credential attached.
setInterval(() => {
  if (!state.online || !state.httpHosts.has('1')) return;
  pushEvent(
    addEvent({
      major: 5,
      minor: 0,
      doorNo: 1,
      serialNo: ++state.serialNo,
      time: nowIso(),
      heartbeat: true,
    })
  );
}, 30_000).unref();

// --- punch ----------------------------------------------------------------

function punch({ employeeNo, method = 'fingerprint', success = true, doorNo = 1, minor }) {
  const person = state.persons.get(String(employeeNo));

  const code =
    minor !== undefined
      ? Number(minor)
      : method === 'face' && !success
        ? MINOR.faceFail
        : MINOR[method];

  if (code === undefined) {
    throw new Error(
      `No event code for method "${method}". Known: ${Object.keys(MINOR).join(', ')}. ` +
        `Pass an explicit "minor" to simulate anything else (PIN has no confirmed code).`
    );
  }

  const evt = addEvent({
    major: 5,
    minor: code,
    doorNo,
    serialNo: ++state.serialNo,
    time: nowIso(),
    employeeNoString: employeeNo ? String(employeeNo) : undefined,
    name: person?.name,
    cardNo: method === 'card' ? (state.cards.get(String(employeeNo)) || [])[0] : undefined,
    currentVerifyMode: 'cardOrFaceOrFp',
    attendanceStatus: 'checkIn',
  });

  console.log(
    `[punch] ${method}${success ? '' : ' (FAILED)'} employee=${employeeNo ?? '-'} ` +
      `${person?.name ?? '(unknown)'} minor=${code} serial=${evt.serialNo}`
  );

  return { evt, push: pushEvent(evt) };
}

// --- HTTP -----------------------------------------------------------------

const NONCE = crypto.randomBytes(8).toString('hex');

function authOk(req, uri) {
  const h = req.headers.authorization || '';
  if (!h.startsWith('Digest ')) return false;
  const p = {};
  for (const m of h.matchAll(/(\w+)=(?:"([^"]*)"|([^\s,]+))/g)) p[m[1]] = m[2] ?? m[3];
  const ha1 = md5(`${USER}:${REALM}:${PASS}`);
  const ha2 = md5(`${req.method}:${p.uri}`);
  return p.response === md5(`${ha1}:${NONCE}:${p.nc}:${p.cnonce}:${p.qop}:${ha2}`) && p.uri === uri;
}

const OK = { statusCode: 1, statusString: 'OK', subStatusCode: 'ok' };
const fail = (sub, code = 4) => ({
  statusCode: code,
  statusString: 'Invalid Operation',
  subStatusCode: sub,
  errorMsg: sub,
});

const server = http.createServer((req, res) => {
  const u = new URL(req.url, 'http://x');
  const isapi = u.pathname.startsWith('/ISAPI');

  const sendJson = (code, obj) => {
    res.writeHead(code, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(obj, null, 1));
  };
  const sendXml = (code, xml) => {
    res.writeHead(code, { 'Content-Type': 'application/xml' });
    res.end(`<?xml version="1.0" encoding="UTF-8"?>\n${xml}`);
  };

  // A device that is "offline" refuses ISAPI too — the control API stays up so
  // you can still bring it back.
  if (isapi && !state.online) {
    res.destroy();
    return;
  }

  // Digest auth guards ISAPI only, matching the real device.
  if (isapi && !authOk(req, u.pathname + u.search)) {
    res.writeHead(401, {
      'WWW-Authenticate': `Digest realm="${REALM}", qop="auth", nonce="${NONCE}", opaque="sim"`,
      'Content-Type': 'application/json',
    });
    return res.end('{}');
  }

  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', async () => {
    const raw = Buffer.concat(chunks).toString('utf8');
    const json = raw.trimStart().startsWith('{') ? JSON.parse(raw) : {};
    const path = u.pathname;

    try {
      // ---- control API (not part of ISAPI) ----
      if (path === '/sim/punch' && req.method === 'POST') {
        const result = punch(json);
        const push = await result.push;
        return sendJson(200, { event: result.evt, push });
      }

      if (path === '/sim/status') {
        return sendJson(200, {
          online: state.online,
          persons: state.persons.size,
          fingerprints: [...state.fingerprints.values()].reduce((n, m) => n + m.size, 0),
          eventsStored: state.events.length,
          capacity: CAPACITY,
          webhook: state.httpHosts.get('1') ?? null,
          pushed: state.pushed,
          pushFailures: state.pushFailures,
        });
      }

      // Take the device offline to test recovery: punches still record to the
      // device log but are not pushed, exactly like a network outage.
      if (path === '/sim/offline' && req.method === 'POST') {
        state.online = false;
        console.log('[sim] device is now OFFLINE — punches will queue in the device log');
        return sendJson(200, { online: false });
      }
      if (path === '/sim/online' && req.method === 'POST') {
        state.online = true;
        console.log('[sim] device is back ONLINE');
        return sendJson(200, { online: true });
      }
      if (path === '/sim/reset' && req.method === 'POST') {
        state.events.length = 0;
        state.pushed = 0;
        state.pushFailures = 0;
        return sendJson(200, { cleared: true });
      }

      // ---- System ----
      if (path === '/ISAPI/System/deviceInfo') {
        // XML even with ?format=json, like the real firmware.
        return sendXml(
          200,
          `<DeviceInfo version="2.0" xmlns="http://www.isapi.org/ver20/XMLSchema">
<deviceName>${NAME}</deviceName>
<model>DS-K1T808MFWX-B</model>
<serialNumber>${SERIAL}</serialNumber>
<firmwareVersion>V3.25.20</firmwareVersion>
<firmwareReleasedDate>build 241227</firmwareReleasedDate>
</DeviceInfo>`
        );
      }

      if (path === '/ISAPI/System/time') {
        return sendXml(
          200,
          `<Time version="2.0" xmlns="http://www.isapi.org/ver20/XMLSchema">
<timeMode>NTP</timeMode><localTime>${nowIso()}</localTime><timeZone>CST-5:30:00</timeZone>
</Time>`
        );
      }

      // ---- Event subscription ----
      if (path.startsWith('/ISAPI/Event/notification/httpHosts')) {
        if (req.method === 'GET') {
          const blocks = [...state.httpHosts.values()]
            .map(
              (h) => `<HttpHostNotification>
<id>${h.id}</id><url>${h.url}</url><protocolType>${h.protocolType}</protocolType>
<parameterFormatType></parameterFormatType>
<addressingFormatType>ipaddress</addressingFormatType>
<ipAddress>${h.ipAddress}</ipAddress><portNo>${h.portNo}</portNo>
<httpAuthenticationMethod>${h.httpAuthenticationMethod}</httpAuthenticationMethod>
</HttpHostNotification>`
            )
            .join('\n');
          return sendXml(
            200,
            `<HttpHostNotificationList version="2.0" xmlns="http://www.isapi.org/ver20/XMLSchema">\n${blocks}\n</HttpHostNotificationList>`
          );
        }

        // The real device rejects a JSON body here.
        if (!raw.trimStart().startsWith('<')) return sendJson(200, fail('badXmlFormat'));

        const pick = (t) => raw.match(new RegExp(`<${t}>([^<]*)</${t}>`))?.[1] ?? '';
        const authMethod = pick('httpAuthenticationMethod') || 'none';
        const password = pick('password');

        // Mirrors <passwordLen min="8" max="16"/> from the real capabilities.
        if (authMethod === 'basic' && (password.length < 8 || password.length > 16)) {
          console.log(`[sim] rejected webhook: password length ${password.length} (needs 8-16)`);
          return sendJson(200, fail('badXmlContent'));
        }

        const id = pick('id') || '1';
        const host = {
          id,
          url: pick('url'),
          protocolType: pick('protocolType') || 'HTTP',
          ipAddress: pick('ipAddress'),
          portNo: Number(pick('portNo')),
          httpAuthenticationMethod: authMethod,
          userName: pick('userName'),
          password,
        };
        state.httpHosts.set(id, host);
        console.log(
          `[sim] webhook registered -> http://${host.ipAddress}:${host.portNo}${host.url} (auth: ${authMethod})`
        );
        return sendJson(200, OK);
      }

      // ---- Persons ----
      if (path === '/ISAPI/AccessControl/UserInfo/Record') {
        const info = json.UserInfo;
        if (state.persons.has(info.employeeNo)) return sendJson(200, fail('deviceUserAlreadyExist', 6));
        state.persons.set(info.employeeNo, info);
        console.log(`[sim] person created: ${info.employeeNo} ${info.name}`);
        return sendJson(200, OK);
      }

      if (path === '/ISAPI/AccessControl/UserInfo/Modify') {
        state.persons.set(json.UserInfo.employeeNo, json.UserInfo);
        return sendJson(200, OK);
      }

      if (path === '/ISAPI/AccessControl/UserInfo/Delete') {
        for (const { employeeNo } of json.UserInfoDelCond?.EmployeeNoList ?? []) {
          state.persons.delete(employeeNo);
          state.fingerprints.delete(employeeNo);
          state.cards.delete(employeeNo);
        }
        return sendJson(200, OK);
      }

      if (path === '/ISAPI/AccessControl/UserInfo/Count') {
        return sendJson(200, { UserInfoCount: { userNumber: state.persons.size } });
      }

      if (path === '/ISAPI/AccessControl/UserInfo/Search') {
        const cond = json.UserInfoSearchCond;
        const all = [...state.persons.values()].map((p) => ({
          ...p,
          numOfCard: (state.cards.get(p.employeeNo) || []).length,
          numOfFP: state.fingerprints.get(p.employeeNo)?.size ?? 0,
          numOfFace: state.faces.has(p.employeeNo) ? 1 : 0,
        }));
        const page = all.slice(cond.searchResultPosition, cond.searchResultPosition + cond.maxResults);
        return sendJson(200, {
          UserInfoSearch: {
            searchID: cond.searchID,
            responseStatusStrg:
              cond.searchResultPosition + page.length < all.length ? 'MORE' : 'OK',
            numOfMatches: page.length,
            totalMatches: all.length,
            UserInfo: page,
          },
        });
      }

      // ---- Cards ----
      if (path === '/ISAPI/AccessControl/CardInfo/Record') {
        const { employeeNo, cardNo } = json.CardInfo;
        state.cards.set(employeeNo, [...(state.cards.get(employeeNo) || []), cardNo]);
        console.log(`[sim] card ${cardNo} -> ${employeeNo}`);
        return sendJson(200, OK);
      }

      // ---- Face ----
      if (path === '/ISAPI/Intelligent/FDLib/FaceDataRecord') {
        const fpid = raw.match(/"FPID"\s*:\s*"([^"]+)"/)?.[1];
        if (fpid) state.faces.add(fpid);
        console.log(`[sim] face enrolled -> ${fpid}`);
        return sendJson(200, OK);
      }

      // ---- Fingerprints ----
      if (path === '/ISAPI/AccessControl/CaptureFingerPrint') {
        // XML-only, and the reply is multipart, like the real device.
        const fingerNo = Number(raw.match(/<fingerNo>(\d+)<\/fingerNo>/)?.[1] ?? 1);
        const template = crypto.randomBytes(96).toString('base64');
        const b = '----simfp';
        const CRLF = '\r\n';
        const xml =
          `<?xml version="1.0" encoding="UTF-8"?><CaptureFingerPrint version="2.0" xmlns="http://www.isapi.org/ver20/XMLSchema">` +
          `<fingerData>${template}</fingerData><fingerNo>${fingerNo}</fingerNo>` +
          `<fingerPrintQuality>${60 + Math.floor(Math.random() * 40)}</fingerPrintQuality></CaptureFingerPrint>`;
        const body =
          `--${b}${CRLF}Content-Type: application/xml${CRLF}${CRLF}${xml}${CRLF}` +
          `--${b}${CRLF}Content-Type: image/jpeg${CRLF}${CRLF}SIMULATED_FP_IMAGE${CRLF}--${b}--${CRLF}`;
        console.log(`[sim] fingerprint captured (finger ${fingerNo})`);
        res.writeHead(200, { 'Content-Type': `multipart/form-data; boundary=${b}` });
        return res.end(body);
      }

      if (path === '/ISAPI/AccessControl/FingerPrintDownload') {
        const cfg = json.FingerPrintCfg;
        if (!state.persons.has(cfg.employeeNo)) return sendJson(200, fail('employeeNoNotExist'));
        const map = state.fingerprints.get(cfg.employeeNo) ?? new Map();
        if (map.size >= 10) return sendJson(200, fail('fingerPrintNumOverLimit'));
        map.set(Number(cfg.fingerPrintID), cfg.fingerData);
        state.fingerprints.set(cfg.employeeNo, map);
        console.log(`[sim] fingerprint ${cfg.fingerPrintID} applied -> ${cfg.employeeNo}`);
        return sendJson(200, OK);
      }

      if (path === '/ISAPI/AccessControl/FingerPrintProgress') {
        return sendJson(200, {
          FingerPrintStatus: { totalStatus: 1, StatusList: [{ id: 1, cardReaderRecvStatus: 1 }] },
        });
      }

      if (path === '/ISAPI/AccessControl/FingerPrintUpload') {
        const cond = json.FingerPrintCond;
        const map = state.fingerprints.get(String(cond.employeeNo)) ?? new Map();
        return sendJson(200, {
          FingerPrintInfo: {
            searchID: cond.searchID,
            status: 'NoFP', // single page: all results returned at once
            FingerPrintList: [...map.entries()].map(([id, data]) => ({
              cardReaderNo: 1,
              fingerPrintID: id,
              fingerType: 'normalFP',
              fingerData: data,
            })),
          },
        });
      }

      if (path === '/ISAPI/AccessControl/FingerPrintDelete') {
        const d = json.FingerPrintDelete?.EmployeeNoDetail;
        const map = state.fingerprints.get(String(d?.employeeNo));
        if (map) {
          if (d.fingerPrintID?.length) d.fingerPrintID.forEach((i) => map.delete(Number(i)));
          else map.clear();
        }
        return sendJson(200, OK);
      }

      // ---- Events ----
      if (path === '/ISAPI/AccessControl/AcsEvent') {
        const cond = json.AcsEventCond;
        // Compare as instants, not strings: the device stamps events with an
        // offset ("...+05:30") while search bounds arrive without one, so a
        // lexical compare silently excludes everything.
        const ms = (s) => (s ? new Date(s).getTime() : null);
        const from = ms(cond.startTime);
        const to = ms(cond.endTime);
        const matches = state.events.filter((e) => {
          if (e.heartbeat) return false;
          const t = ms(e.time);
          return (from === null || t >= from) && (to === null || t <= to);
        });
        const page = matches.slice(
          cond.searchResultPosition,
          cond.searchResultPosition + cond.maxResults
        );
        return sendJson(200, {
          AcsEvent: {
            searchID: cond.searchID,
            responseStatusStrg:
              cond.searchResultPosition + page.length < matches.length ? 'MORE' : 'OK',
            numOfMatches: page.length,
            totalMatches: matches.length,
            InfoList: page.map((e) => ({
              major: e.major,
              minor: e.minor,
              time: e.time,
              serialNo: e.serialNo,
              doorNo: e.doorNo,
              employeeNoString: e.employeeNoString,
              name: e.name,
              cardNo: e.cardNo,
              currentVerifyMode: e.currentVerifyMode,
              attendanceStatus: e.attendanceStatus,
            })),
          },
        });
      }

      if (path === '/ISAPI/AccessControl/AcsEventTotalNum') {
        return sendJson(200, {
          AcsEventTotalNum: { totalNum: state.events.filter((e) => !e.heartbeat).length },
        });
      }

      if (path.startsWith('/ISAPI/AccessControl/RemoteControl/door/')) {
        console.log(`[sim] door command: ${json.RemoteControlDoor?.cmd}`);
        return sendJson(200, OK);
      }

      if (path === '/ISAPI/AccessControl/capabilities') {
        return sendXml(
          200,
          `<AcsCap version="2.0" xmlns="http://www.isapi.org/ver20/XMLSchema">
<isSupportUserInfo>true</isSupportUserInfo><isSupportCardInfo>true</isSupportCardInfo>
<isSupportFingerPrintCfg>true</isSupportFingerPrintCfg>
<isSupportCaptureFingerPrint>true</isSupportCaptureFingerPrint>
<isSupportAcsEvent>true</isSupportAcsEvent><isSupportAcsEventTotalNum>true</isSupportAcsEventTotalNum>
<isSupportRemoteControlDoor>true</isSupportRemoteControlDoor>
</AcsCap>`
        );
      }

      return sendJson(404, fail('notSupport'));
    } catch (err) {
      console.error('[sim] error:', err.message);
      return sendJson(400, { error: err.message });
    }
  });
});

// Register with the backend directly, so a fleet does not need one
// `npm run register` per device.
function selfRegister(url) {
  const parsed = new URL(url);
  state.httpHosts.set('1', {
    id: '1',
    url: parsed.pathname,
    protocolType: 'HTTP',
    ipAddress: parsed.hostname,
    portNo: Number(parsed.port || 80),
    httpAuthenticationMethod: parsed.username ? 'basic' : 'none',
    userName: parsed.username || '',
    password: parsed.password || '',
  });
  console.log(`[sim] self-registered webhook -> ${parsed.origin}${parsed.pathname}`);
}

if (WEBHOOK) selfRegister(WEBHOOK);

server.listen(PORT, () => {
  console.log(`
${NAME} (${DEVICE_IP}) listening on http://127.0.0.1:${PORT}
  ISAPI credentials : ${USER} / ${PASS}
  Event capacity    : ${CAPACITY} (ring buffer)

Point the backend at it:
  DEVICE_HOST=127.0.0.1 DEVICE_PORT=${PORT} DEVICE_USER=${USER} DEVICE_PASS=${PASS} npm start
  npm run register

Trigger punches:
  curl -X POST http://127.0.0.1:${PORT}/sim/punch -H 'Content-Type: application/json' -d '{"employeeNo":"1042","method":"fingerprint"}'
  methods: fingerprint | card | face | exitButton   (add "success":false for face)

Simulate an outage, then recovery:
  curl -X POST http://127.0.0.1:${PORT}/sim/offline
  ... punches still record to the device log but are not pushed ...
  curl -X POST http://127.0.0.1:${PORT}/sim/online
  ... the backend's auto-sync recovers them ...

  curl http://127.0.0.1:${PORT}/sim/status
`);
});

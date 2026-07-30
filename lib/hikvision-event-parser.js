/**
 * Hikvision access-event parser — standalone, zero-dependency.
 *
 * Drop this one file into any Node backend. Give it the webhook request (or just
 * the body) and get back a clean, flat event object. Handles every shape a
 * Hikvision terminal sends: application/json, XML (text/plain), and
 * multipart/form-data (JSON/XML part + JPEG), whether or not your framework has
 * already parsed the body.
 *
 * Usage (Express):
 *   const { parseDeviceEvent } = require('./lib/hikvision-event-parser');
 *   app.post('/webhook', (req, res) => {
 *     res.sendStatus(200);                 // ack fast — the device retries on non-2xx
 *     const event = parseDeviceEvent(req); // works with json / raw / multipart bodies
 *     if (!event || event.isHeartbeat) return;   // skip unparseable + keep-alives
 *     // event.employeeNo, event.cardNo, event.eventName, event.verifyMethod, ...
 *     saveAttendance(event);
 *   });
 *
 * Usage (you already have the body / a raw string):
 *   const event = parseDeviceEvent(rawBodyStringOrObject, { contentType });
 *
 * Returns: the event object (see fields below) with an added `isHeartbeat` flag,
 *          or `null` if the payload could not be parsed.
 */

'use strict';

// ── event codes (major 5 = access-control events) ──────────────────────────
const MAJOR = { 1: 'alarm', 2: 'exception', 3: 'operation', 5: 'event' };
const MINOR = {
  27:  { name: 'exitButtonPressed',      method: 'button',      success: true },
  38:  { name: 'cardAuthSuccess',        method: 'card',        success: true },
  75:  { name: 'faceAuthSuccess',        method: 'face',        success: true },
  76:  { name: 'faceAuthFail',           method: 'face',        success: false },
  113: { name: 'fingerprintAuthSuccess', method: 'fingerprint', success: true },
  // Add device-specific minors here as you observe them (see event.raw when eventName is null).
};

function describe(major, minor) {
  const entry = major === 5 ? MINOR[minor] : undefined;
  return {
    majorName: MAJOR[major] ?? null,
    minorName: entry?.name ?? null,
    method: entry?.method ?? null,
    success: entry?.success ?? null,
  };
}

// ── tiny XML → object (some firmwares send XML with text/plain) ────────────
function xmlToObject(xml) {
  const parseChildren = (body) => {
    const out = {};
    const tag = /<([\w:.-]+)(?:\s[^>]*?)?(?:\/>|>([\s\S]*?)<\/\1>)/g;
    let m, found = false;
    while ((m = tag.exec(body)) !== null) {
      found = true;
      const [, name, inner = ''] = m;
      const value = inner.includes('<') ? parseChildren(inner) : inner.trim();
      if (name in out) out[name] = Array.isArray(out[name]) ? [...out[name], value] : [out[name], value];
      else out[name] = value;
    }
    return found ? out : body.trim();
  };
  return parseChildren(String(xml).replace(/<\?xml[\s\S]*?\?>/, ''));
}

// ── helpers ────────────────────────────────────────────────────────────────
const num = (v) => (v === undefined || v === null || v === '' ? null : Number(v));

function getHeader(req, name) {
  if (typeof req.get === 'function') { try { return req.get(name); } catch { /* noop */ } }
  const h = req.headers || {};
  return h[name] ?? h[name.toLowerCase()];
}

/** Parse a text part (JSON or XML) into an object, or null. */
function parseText(buf) {
  const text = (Buffer.isBuffer(buf) ? buf.toString('utf8') : String(buf))
    .replace(/\0+$/, '').trim();
  if (!text) return null;
  if (text[0] === '<') return xmlToObject(text);
  try { return JSON.parse(text); } catch { return null; }
}

/** Devices may wrap the alert in EventNotificationAlert — unwrap to the alert. */
const unwrap = (o) => (o && o.EventNotificationAlert ? o.EventNotificationAlert : o);

/** Pull the JSON/XML text part out of a raw multipart body. */
function multipartTextPart(raw, contentType) {
  const text = Buffer.isBuffer(raw) ? raw.toString('binary') : String(raw);
  const m = /boundary=(?:"([^"]+)"|([^";]+))/i.exec(contentType || '');
  const boundary = m && (m[1] || m[2]);
  if (!boundary) return null;
  for (const part of text.split('--' + boundary)) {
    const sep = part.indexOf('\r\n\r\n');
    if (sep === -1) continue;
    const headers = part.slice(0, sep);
    if (/Content-Type:\s*image\//i.test(headers)) continue; // skip JPEG parts
    const content = part.slice(sep + 4).replace(/\r\n--\s*$/, '').replace(/\r\n$/, '').replace(/\0+$/, '').trim();
    if (content.startsWith('{') || content.startsWith('<')) return content;
  }
  return null;
}

/** Extract the raw alert object from a body of any shape. */
function extractAlert(body, contentType) {
  if (body == null) return null;

  if (Buffer.isBuffer(body) || typeof body === 'string') {
    if (contentType && /multipart/i.test(contentType)) {
      const part = multipartTextPart(body, contentType);
      return part ? unwrap(parseText(part)) : null;
    }
    return unwrap(parseText(body));
  }

  if (typeof body === 'object') {
    // multipart fields already parsed by your framework (e.g. multer) → text parts
    for (const k of ['event_log', 'EventLog', 'eventLog', 'AccessControllerEvent']) {
      if (typeof body[k] === 'string') return unwrap(parseText(body[k]));
    }
    return unwrap(body); // already an alert object
  }
  return null;
}

// ── normalise to a flat event ──────────────────────────────────────────────
function normalise(alert, { picturePath = null } = {}) {
  const root = (alert && alert.AccessControllerEvent) || {};
  const majorType = num(root.majorEventType);
  const minorType = num(root.subEventType);
  const { minorName, method, success } = describe(majorType, minorType);

  const employeeNo =
    root.employeeNoString ??
    (root.employeeNo !== undefined ? String(root.employeeNo) : null);

  return {
    serialNo: root.serialNo ?? null,
    deviceIp: (alert && alert.ipAddress) ?? null,
    eventType: (alert && alert.eventType) ?? null,
    majorType,
    minorType,
    eventName: minorName,          // null => unmapped code (inspect `raw` and add to MINOR)
    verifyMethod: method,          // fingerprint | card | face | button | null
    success: success === null ? null : success ? 1 : 0,
    employeeNo: employeeNo || null,
    personName: root.name ?? null,
    cardNo: root.cardNo ?? null,
    verifyMode: root.currentVerifyMode ?? null,
    attendance: root.attendanceStatus ?? null,
    doorNo: root.doorNo ?? null,
    eventTime: (alert && alert.dateTime) ?? null,
    picturePath,
    raw: JSON.stringify(alert),
    receivedAt: new Date().toISOString(),
  };
}

/** A keep-alive (no credential) — not a real punch. */
function isHeartbeat(event) {
  if (!event) return true;
  if (event.eventType !== 'AccessControllerEvent') return true;
  if (event.eventName === 'exitButtonPressed') return false; // real, but no person
  return !event.employeeNo && !event.cardNo && event.verifyMethod === null;
}

/**
 * THE function. Accepts an Express-style request, a raw body (string/Buffer),
 * or an already-parsed object. Returns the flat event (+ isHeartbeat) or null.
 */
function parseDeviceEvent(input, opts = {}) {
  let body = input;
  let contentType = opts.contentType;

  const looksLikeReq =
    input && typeof input === 'object' && !Buffer.isBuffer(input) &&
    ('body' in input || 'headers' in input || typeof input.get === 'function') &&
    !('AccessControllerEvent' in input) && !('EventNotificationAlert' in input) &&
    !('event_log' in input);

  if (looksLikeReq) {
    contentType = contentType || getHeader(input, 'content-type');
    body = 'body' in input ? input.body : undefined;
    const emptyObj = body && typeof body === 'object' && !Buffer.isBuffer(body) && Object.keys(body).length === 0;
    if ((body == null || emptyObj) && input.rawBody) body = input.rawBody; // raw multipart fallback
  }

  const alert = extractAlert(body, contentType);
  if (!alert) return null;

  const event = normalise(alert, opts);
  event.isHeartbeat = isHeartbeat(event);
  return event;
}

module.exports = { parseDeviceEvent, normalise, isHeartbeat, describe, xmlToObject, MAJOR, MINOR };

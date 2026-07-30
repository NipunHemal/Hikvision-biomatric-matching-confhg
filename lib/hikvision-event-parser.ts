/**
 * Hikvision access-event parser — standalone, zero-dependency, TypeScript.
 *
 * Drop this one file into any TS/Node backend. Give it the webhook request (or
 * just the body) and get back a typed, flat {@link DeviceEvent}. Handles every
 * shape a terminal sends: application/json, XML (text/plain), and
 * multipart/form-data (JSON/XML part + JPEG), parsed or raw.
 *
 * Usage (Express + TS):
 *   import { parseDeviceEvent, DeviceEvent } from './hikvision-event-parser';
 *   app.post('/webhook', (req, res) => {
 *     res.sendStatus(200);                          // ack fast (device retries on non-2xx)
 *     const event: DeviceEvent | null = parseDeviceEvent(req);
 *     if (!event || event.isHeartbeat) return;      // skip unparseable + keep-alives
 *     saveAttendance(event);                        // event.employeeNo, event.cardNo, ...
 *   });
 *
 * Usage (you already have the body):
 *   const event = parseDeviceEvent(rawStringOrObject, { contentType });
 *
 * Requires @types/node (for Buffer). No other dependencies.
 */

// ── public types ────────────────────────────────────────────────────────────
export type VerifyMethod = 'fingerprint' | 'card' | 'face' | 'button';

export interface DeviceEvent {
  serialNo: string | number | null;
  deviceIp: string | null;
  eventType: string | null;
  majorType: number | null;
  minorType: number | null;
  /** null => unmapped minor code; inspect `raw` and add it to MINOR. */
  eventName: string | null;
  verifyMethod: VerifyMethod | null;
  success: 0 | 1 | null;
  employeeNo: string | null;
  personName: string | null;
  cardNo: string | null;
  verifyMode: string | null;
  attendance: string | null;
  doorNo: number | string | null;
  eventTime: string | null;
  picturePath: string | null;
  /** The original alert JSON (stringified) — keep for replay / unmapped codes. */
  raw: string;
  receivedAt: string;
  /** True for keep-alives (no credential) — not a real punch. */
  isHeartbeat: boolean;
}

/** Minimal shape of an Express-style request. */
export interface RequestLike {
  body?: unknown;
  rawBody?: Buffer | string;
  headers?: Record<string, string | string[] | undefined>;
  get?(name: string): string | undefined;
}

export interface ParseOptions {
  contentType?: string;
  picturePath?: string | null;
}

export type ParserInput = RequestLike | string | Buffer | Record<string, unknown>;

// ── event codes (major 5 = access-control events) ───────────────────────────
interface MinorEntry { name: string; method: VerifyMethod; success: boolean; }

export const MAJOR: Record<number, string> = { 1: 'alarm', 2: 'exception', 3: 'operation', 5: 'event' };
export const MINOR: Record<number, MinorEntry> = {
  27:  { name: 'exitButtonPressed',      method: 'button',      success: true },
  38:  { name: 'cardAuthSuccess',        method: 'card',        success: true },
  75:  { name: 'faceAuthSuccess',        method: 'face',        success: true },
  76:  { name: 'faceAuthFail',           method: 'face',        success: false },
  113: { name: 'fingerprintAuthSuccess', method: 'fingerprint', success: true },
  // Add device-specific minors here as you observe them (see event.raw when eventName is null).
};

export function describe(major: number | null, minor: number | null): {
  majorName: string | null; minorName: string | null;
  method: VerifyMethod | null; success: boolean | null;
} {
  const entry = major === 5 && minor != null ? MINOR[minor] : undefined;
  return {
    majorName: (major != null ? MAJOR[major] : null) ?? null,
    minorName: entry?.name ?? null,
    method: entry?.method ?? null,
    success: entry?.success ?? null,
  };
}

// ── tiny XML → object (some firmwares send XML with text/plain) ─────────────
type XmlValue = string | XmlObject | Array<string | XmlObject>;
interface XmlObject { [key: string]: XmlValue; }

export function xmlToObject(xml: string): XmlValue {
  const parseChildren = (body: string): XmlValue => {
    const out: XmlObject = {};
    const tag = /<([\w:.-]+)(?:\s[^>]*?)?(?:\/>|>([\s\S]*?)<\/\1>)/g;
    let m: RegExpExecArray | null;
    let found = false;
    while ((m = tag.exec(body)) !== null) {
      found = true;
      const name = m[1];
      const inner = m[2] ?? '';
      const value: XmlValue = inner.includes('<') ? parseChildren(inner) : inner.trim();
      if (name in out) {
        const prev = out[name];
        out[name] = Array.isArray(prev) ? [...prev, value] : [prev, value];
      } else {
        out[name] = value;
      }
    }
    return found ? out : body.trim();
  };
  return parseChildren(String(xml).replace(/<\?xml[\s\S]*?\?>/, ''));
}

// ── helpers ─────────────────────────────────────────────────────────────────
type AnyRec = Record<string, any>;

const num = (v: unknown): number | null =>
  v === undefined || v === null || v === '' ? null : Number(v);

function getHeader(req: RequestLike, name: string): string | undefined {
  if (typeof req.get === 'function') {
    try { return req.get(name); } catch { /* noop */ }
  }
  const h = req.headers ?? {};
  const v = (h[name] ?? h[name.toLowerCase()]) as string | string[] | undefined;
  return Array.isArray(v) ? v[0] : v;
}

function parseText(buf: Buffer | string): AnyRec | null {
  const text = (Buffer.isBuffer(buf) ? buf.toString('utf8') : String(buf))
    .replace(/\0+$/, '').trim();
  if (!text) return null;
  if (text[0] === '<') return xmlToObject(text) as AnyRec;
  try { return JSON.parse(text) as AnyRec; } catch { return null; }
}

const unwrap = (o: AnyRec | null): AnyRec | null =>
  o && o.EventNotificationAlert ? (o.EventNotificationAlert as AnyRec) : o;

function multipartTextPart(raw: Buffer | string, contentType?: string): string | null {
  const text = Buffer.isBuffer(raw) ? raw.toString('binary') : String(raw);
  const m = /boundary=(?:"([^"]+)"|([^";]+))/i.exec(contentType ?? '');
  const boundary = m && (m[1] || m[2]);
  if (!boundary) return null;
  for (const part of text.split('--' + boundary)) {
    const sep = part.indexOf('\r\n\r\n');
    if (sep === -1) continue;
    const headers = part.slice(0, sep);
    if (/Content-Type:\s*image\//i.test(headers)) continue; // skip JPEG parts
    const content = part.slice(sep + 4)
      .replace(/\r\n--\s*$/, '').replace(/\r\n$/, '').replace(/\0+$/, '').trim();
    if (content.startsWith('{') || content.startsWith('<')) return content;
  }
  return null;
}

function extractAlert(body: unknown, contentType?: string): AnyRec | null {
  if (body == null) return null;

  if (Buffer.isBuffer(body) || typeof body === 'string') {
    if (contentType && /multipart/i.test(contentType)) {
      const part = multipartTextPart(body, contentType);
      return part ? unwrap(parseText(part)) : null;
    }
    return unwrap(parseText(body));
  }

  if (typeof body === 'object') {
    const rec = body as AnyRec;
    for (const k of ['event_log', 'EventLog', 'eventLog', 'AccessControllerEvent']) {
      if (typeof rec[k] === 'string') return unwrap(parseText(rec[k] as string));
    }
    return unwrap(rec); // already an alert object
  }
  return null;
}

// ── normalise to a flat event ────────────────────────────────────────────────
export function normalise(alert: AnyRec, opts: ParseOptions = {}): DeviceEvent {
  const root: AnyRec = (alert && alert.AccessControllerEvent) || {};
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
    eventName: minorName,
    verifyMethod: method,
    success: success === null ? null : success ? 1 : 0,
    employeeNo: (employeeNo as string) || null,
    personName: root.name ?? null,
    cardNo: root.cardNo ?? null,
    verifyMode: root.currentVerifyMode ?? null,
    attendance: root.attendanceStatus ?? null,
    doorNo: root.doorNo ?? null,
    eventTime: (alert && alert.dateTime) ?? null,
    picturePath: opts.picturePath ?? null,
    raw: JSON.stringify(alert),
    receivedAt: new Date().toISOString(),
    isHeartbeat: false, // set by parseDeviceEvent / isHeartbeat
  };
}

export function isHeartbeat(event: DeviceEvent | null): boolean {
  if (!event) return true;
  if (event.eventType !== 'AccessControllerEvent') return true;
  if (event.eventName === 'exitButtonPressed') return false; // real, but no person
  return !event.employeeNo && !event.cardNo && event.verifyMethod === null;
}

/**
 * THE function. Accepts an Express-style request, a raw body (string/Buffer),
 * or an already-parsed object. Returns the typed event (+ isHeartbeat) or null.
 */
export function parseDeviceEvent(input: ParserInput, opts: ParseOptions = {}): DeviceEvent | null {
  let body: unknown = input;
  let contentType = opts.contentType;

  const rec = input as AnyRec;
  const looksLikeReq =
    !!input && typeof input === 'object' && !Buffer.isBuffer(input) &&
    ('body' in rec || 'headers' in rec || typeof rec.get === 'function') &&
    !('AccessControllerEvent' in rec) && !('EventNotificationAlert' in rec) &&
    !('event_log' in rec);

  if (looksLikeReq) {
    const req = input as RequestLike;
    contentType = contentType || getHeader(req, 'content-type');
    body = 'body' in rec ? req.body : undefined;
    const emptyObj = !!body && typeof body === 'object' && !Buffer.isBuffer(body) &&
      Object.keys(body as AnyRec).length === 0;
    if ((body == null || emptyObj) && req.rawBody) body = req.rawBody; // raw multipart fallback
  }

  const alert = extractAlert(body, contentType);
  if (!alert) return null;

  const event = normalise(alert, opts);
  event.isHeartbeat = isHeartbeat(event);
  return event;
}

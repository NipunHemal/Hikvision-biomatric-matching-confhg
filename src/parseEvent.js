// Normalises the payloads a Hikvision access terminal pushes to the webhook.
//
// The device sends one of two shapes depending on whether picture upload is on:
//   1. application/json    -> the alert object directly
//   2. multipart/form-data -> a JSON part (usually "event_log") + JPEG parts
// Both wrap the same alert body, so we only normalise that.
const { describe } = require('./eventCodes');

function parseJsonPart(buf) {
  const text = Buffer.isBuffer(buf) ? buf.toString('utf8') : String(buf);
  // Some firmwares pad the JSON part with NUL bytes / trailing whitespace.
  return JSON.parse(text.replace(/\0+$/, '').trim());
}

// Pulls the alert object out of whatever the request carried.
function extractAlert(req) {
  if (req.body && typeof req.body === 'object' && !Buffer.isBuffer(req.body)) {
    // multipart: multer puts text parts on req.body as strings
    for (const key of ['event_log', 'EventLog', 'eventLog', 'AccessControllerEvent']) {
      if (typeof req.body[key] === 'string') return parseJsonPart(req.body[key]);
    }
    if (Object.keys(req.body).length) return req.body;
  }
  if (Buffer.isBuffer(req.body) && req.body.length) return parseJsonPart(req.body);
  return null;
}

function normalise(alert, { picturePath = null } = {}) {
  const root = alert.AccessControllerEvent || {};

  const majorType = root.majorEventType ?? null;
  const minorType = root.subEventType ?? null;
  const { minorName, method, success } = describe(majorType, minorType);

  const employeeNo =
    root.employeeNoString ??
    (root.employeeNo !== undefined ? String(root.employeeNo) : null);

  return {
    serialNo: root.serialNo ?? null,
    deviceIp: alert.ipAddress ?? null,
    eventType: alert.eventType ?? null,
    majorType,
    minorType,
    eventName: minorName,
    // The minor code names the credential that actually matched.
    // currentVerifyMode only reports what the door *accepts* ("fingerprintOrCard"),
    // so it is kept as context but never used to decide the method.
    verifyMethod: method,
    success: success === null ? null : success ? 1 : 0,
    employeeNo: employeeNo || null,
    personName: root.name ?? null,
    cardNo: root.cardNo ?? null,
    verifyMode: root.currentVerifyMode ?? null,
    attendance: root.attendanceStatus ?? null,
    doorNo: root.doorNo ?? null,
    eventTime: alert.dateTime ?? null,
    picturePath,
    raw: JSON.stringify(alert),
    receivedAt: new Date().toISOString(),
  };
}

// The terminal posts a keep-alive every ~30s with the same envelope but no
// credential attached. Those are not punches and must not reach the DB.
function isHeartbeat(event) {
  if (event.eventType !== 'AccessControllerEvent') return true;
  if (event.eventName === 'exitButtonPressed') return false; // real, but has no person
  return !event.employeeNo && !event.cardNo && event.verifyMethod === null;
}

// Converts a stored AcsEvent InfoList row (from backfill) into the same shape
// the webhook produces, so both paths write identical records.
function fromAcsEvent(info, deviceIp) {
  return normalise({
    ipAddress: deviceIp,
    eventType: 'AccessControllerEvent',
    dateTime: info.time,
    AccessControllerEvent: {
      majorEventType: info.major,
      subEventType: info.minor,
      employeeNoString: info.employeeNoString ?? info.employeeNo,
      name: info.name,
      cardNo: info.cardNo,
      currentVerifyMode: info.currentVerifyMode,
      attendanceStatus: info.attendanceStatus,
      serialNo: info.serialNo,
      doorNo: info.doorNo,
    },
  });
}

module.exports = { extractAlert, normalise, isHeartbeat, fromAcsEvent };

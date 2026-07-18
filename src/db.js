const path = require('path');
const fs = require('fs');
const Database = require('better-sqlite3');

const dataDir = path.join(__dirname, '..', 'data');
fs.mkdirSync(dataDir, { recursive: true });

const db = new Database(path.join(dataDir, 'events.db'));
db.pragma('journal_mode = WAL');

db.exec(`
  CREATE TABLE IF NOT EXISTS events (
    id             INTEGER PRIMARY KEY AUTOINCREMENT,
    serial_no      INTEGER,
    device_ip      TEXT,
    event_type     TEXT,
    major_type     INTEGER,
    minor_type     INTEGER,
    event_name     TEXT,
    verify_method  TEXT,
    success        INTEGER,
    employee_no    TEXT,
    person_name    TEXT,
    card_no        TEXT,
    verify_mode    TEXT,
    attendance     TEXT,
    door_no        INTEGER,
    event_time     TEXT,
    picture_path   TEXT,
    source         TEXT NOT NULL DEFAULT 'webhook',
    raw            TEXT NOT NULL,
    received_at    TEXT NOT NULL
  );

  CREATE UNIQUE INDEX IF NOT EXISTS idx_events_dedup
    ON events (device_ip, serial_no, event_time);

  CREATE INDEX IF NOT EXISTS idx_events_employee ON events (employee_no);
  CREATE INDEX IF NOT EXISTS idx_events_time     ON events (event_time);
`);

const insertStmt = db.prepare(`
  INSERT OR IGNORE INTO events (
    serial_no, device_ip, event_type, major_type, minor_type, event_name,
    verify_method, success, employee_no, person_name, card_no, verify_mode,
    attendance, door_no, event_time, picture_path, source, raw, received_at
  ) VALUES (
    @serialNo, @deviceIp, @eventType, @majorType, @minorType, @eventName,
    @verifyMethod, @success, @employeeNo, @personName, @cardNo, @verifyMode,
    @attendance, @doorNo, @eventTime, @picturePath, @source, @raw, @receivedAt
  )
`);

// Returns the new row id, or null when the event was a duplicate.
function insertEvent(event, source = 'webhook') {
  const info = insertStmt.run({ ...event, source });
  return info.changes === 1 ? info.lastInsertRowid : null;
}

function listEvents({ limit = 50, employeeNo, method, since } = {}) {
  const where = [];
  const params = [];
  if (employeeNo) (where.push('employee_no = ?'), params.push(employeeNo));
  if (method) (where.push('verify_method = ?'), params.push(method));
  if (since) (where.push('event_time >= ?'), params.push(since));

  const sql =
    'SELECT * FROM events' +
    (where.length ? ` WHERE ${where.join(' AND ')}` : '') +
    ' ORDER BY id DESC LIMIT ?';
  return db.prepare(sql).all(...params, limit);
}

function getEvent(id) {
  return db.prepare('SELECT * FROM events WHERE id = ?').get(id);
}

// Newest event we hold, used as the starting point for backfill.
function latestEventTime() {
  return db.prepare('SELECT MAX(event_time) AS t FROM events').get().t;
}

module.exports = { db, insertEvent, listEvents, getEvent, latestEventTime };

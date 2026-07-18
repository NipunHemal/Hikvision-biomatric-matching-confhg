// Pulls stored access events off the device and merges them into the DB.
//
// The webhook only delivers events that happen while this server is reachable;
// anything during downtime lives only in the terminal's own log. This replays
// that log. Dedup on (device_ip, serial_no, event_time) makes overlap harmless.
//
//   node src/backfill.js              -> since the newest event we hold (or 24h)
//   node src/backfill.js 2026-07-01   -> since an explicit date
const config = require('./config');
const { IsapiClient } = require('./isapi');
const { insertEvent, latestEventTime } = require('./db');
const { fromAcsEvent, isHeartbeat } = require('./parseEvent');

// The device wants local ISO without a timezone suffix.
const isoLocal = (d) => d.toISOString().slice(0, 19);

async function backfill({ startTime, endTime } = {}) {
  const client = new IsapiClient();

  const start =
    startTime ||
    latestEventTime()?.slice(0, 19) ||
    isoLocal(new Date(Date.now() - 24 * 60 * 60 * 1000));
  const end = endTime || isoLocal(new Date());

  let scanned = 0;
  let inserted = 0;

  for await (const info of client.acsEvents({ startTime: start, endTime: end })) {
    scanned += 1;
    const event = fromAcsEvent(info, config.device.host);
    if (isHeartbeat(event)) continue;
    if (insertEvent(event, 'backfill') !== null) inserted += 1;
  }

  return { start, end, scanned, inserted, skipped: scanned - inserted };
}

module.exports = { backfill };

if (require.main === module) {
  const arg = process.argv[2];
  backfill({ startTime: arg && `${arg}T00:00:00` })
    .then((r) =>
      console.log(
        `Backfill ${r.start} -> ${r.end}: scanned ${r.scanned}, inserted ${r.inserted}, skipped ${r.skipped}`
      )
    )
    .catch((err) => {
      console.error(`Backfill failed: ${err.message}`);
      process.exit(1);
    });
}

// Keeps the local DB in step with the device's own log.
//
// The webhook only delivers punches that happen while this server is reachable.
// Anything during downtime — server restart, firewall, network, power — lives
// only on the terminal until it is pulled. This runs that pull:
//
//   * once at startup      (recovers whatever was missed while we were down)
//   * on a timer           (catches silent webhook failures)
//   * when the device      (a device that was unreachable has been queuing
//     comes back online     punches; sweep them as soon as it returns)
const config = require('./config');
const { backfill } = require('./backfill');

let running = false;

// Only one sweep at a time: the startup run, the timer and a recovery can
// otherwise overlap and re-scan the same window concurrently.
async function sync(reason) {
  if (running) return null;
  running = true;

  try {
    const result = await backfill();
    if (result.inserted > 0) {
      console.log(
        `[sync] ${reason}: recovered ${result.inserted} missed punch(es) ` +
          `from the device log (${result.start} -> ${result.end})`
      );
    } else if (reason !== 'scheduled') {
      console.log(`[sync] ${reason}: nothing missed (${result.scanned} scanned)`);
    }
    return result;
  } catch (err) {
    // A failed sweep is normal when the device is unreachable — the next run
    // picks it up. Don't let it take the process down.
    console.warn(`[sync] ${reason}: failed — ${err.message}`);
    return null;
  } finally {
    running = false;
  }
}

function start() {
  if (config.backfillOnStart) {
    // Delayed slightly so the listener is accepting requests first.
    setTimeout(() => sync('startup'), 2000).unref();
  }

  if (config.backfillIntervalMin > 0) {
    const timer = setInterval(
      () => sync('scheduled'),
      config.backfillIntervalMin * 60 * 1000
    );
    timer.unref();
    console.log(
      `Auto-sync    every ${config.backfillIntervalMin} min` +
        `${config.backfillOnStart ? ' + on startup' : ''} + on device recovery`
    );
  }
}

module.exports = { start, sync };

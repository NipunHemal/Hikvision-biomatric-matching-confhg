// Device liveness, tracked two ways:
//
//   active  - probe the device over ISAPI on demand (authoritative, but only
//             tells you about this instant)
//   passive - the terminal POSTs a keep-alive every ~30s, so the time since the
//             last inbound request tells you it was alive without asking
//
// Passive is the better signal for "is it still there": a device can answer a
// probe yet be unable to reach us, which is the failure that actually matters.
const config = require('./config');

// The device is considered stale if nothing has arrived in this long. Its
// keep-alive is ~30s, so this allows two missed beats plus slack.
const SILENCE_LIMIT_MS = 90_000;

let lastPostAt = null;
let lastPostFrom = null;
let online = null; // null = unknown, before the first observation
let onRecovery = null; // set by the server to trigger a catch-up sweep

function setRecoveryHandler(fn) {
  onRecovery = fn;
}

function markSeen(ip) {
  lastPostAt = Date.now();
  lastPostFrom = ip;

  // A device that was unreachable has been queuing punches locally. Pull them
  // the moment it can talk to us again, rather than waiting for the next tick.
  if (online === false) {
    console.log(`[device] back online — POST received from ${ip}`);
    if (onRecovery) onRecovery('device recovery');
  }
  online = true;
}

function silenceMs() {
  return lastPostAt === null ? null : Date.now() - lastPostAt;
}

// Called on a timer so a device that goes quiet is reported, not just noticed
// the next time someone asks.
function checkSilence() {
  const silent = silenceMs();
  if (silent === null) return;
  if (silent > SILENCE_LIMIT_MS && online !== false) {
    online = false;
    console.warn(
      `[device] OFFLINE — nothing received for ${Math.round(silent / 1000)}s ` +
        `(expected a keep-alive every ~30s)`
    );
  }
}

// Active probe. Reports reachability plus round-trip time.
async function probe(client) {
  const startedAt = Date.now();
  try {
    const { DeviceInfo = {} } = await client.deviceInfo();
    return {
      reachable: true,
      latencyMs: Date.now() - startedAt,
      model: DeviceInfo.model ?? null,
      firmware: DeviceInfo.firmwareVersion ?? null,
      serial: DeviceInfo.serialNumber ?? null,
      deviceName: DeviceInfo.deviceName ?? null,
    };
  } catch (err) {
    return {
      reachable: false,
      latencyMs: Date.now() - startedAt,
      error: err.message,
    };
  }
}

async function report(client) {
  const probed = await probe(client);
  const silent = silenceMs();

  return {
    // Healthy means both directions work: we can reach it AND it can reach us.
    healthy: probed.reachable && silent !== null && silent <= SILENCE_LIMIT_MS,
    device: { host: config.device.host, port: config.device.port, ...probed },
    webhook: {
      receiving: silent !== null && silent <= SILENCE_LIMIT_MS,
      lastPostAt: lastPostAt === null ? null : new Date(lastPostAt).toISOString(),
      lastPostFrom,
      secondsSinceLastPost: silent === null ? null : Math.round(silent / 1000),
      note:
        lastPostAt === null
          ? 'No POST has ever arrived. Check the firewall rule for the listener port and that the device is registered.'
          : undefined,
    },
  };
}

function startMonitor() {
  const timer = setInterval(checkSilence, 30_000);
  timer.unref(); // never hold the process open
  return timer;
}

module.exports = {
  markSeen,
  probe,
  report,
  startMonitor,
  setRecoveryHandler,
  SILENCE_LIMIT_MS,
};

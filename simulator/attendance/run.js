// Generates fake attendance and plays it into running simulators.
//
//   node simulator/attendance/run.js --period month
//   node simulator/attendance/run.js --period week --employees 8 --preset messy
//   node simulator/attendance/run.js --period day --dry-run
//
// Requires simulators to be running (npm run fleet). Each punch is delivered by
// the device it belongs to, so the backend sees four distinct terminals.
const { PRESETS } = require('./profile');
const { generate } = require('./generate');
const { IsapiClient } = require('../../src/isapi');

const arg = (name, fallback) => {
  const i = process.argv.indexOf(`--${name}`);
  return i === -1 ? fallback : process.argv[i + 1];
};
const flag = (name) => process.argv.includes(`--${name}`);

const period = arg('period', 'month');
const presetName = arg('preset', 'default');
const seed = Number(arg('seed', 42));
const employees = arg('employees', null);
const deviceCount = Number(arg('devices', 4));
const endDate = arg('end', null) ? new Date(arg('end')) : new Date();
const dryRun = flag('dry-run');
const skipEnrol = flag('skip-enrol');

async function main() {
  const base = PRESETS[presetName];
  if (!base) {
    console.error(`Unknown preset "${presetName}". Available: ${Object.keys(PRESETS).join(', ')}`);
    process.exit(1);
  }

  const profile = {
    ...base,
    devices: base.devices.slice(0, deviceCount),
    staff: { ...base.staff, ...(employees && { count: Number(employees) }) },
  };

  console.log(
    `Generating ${period} of attendance — preset "${presetName}", ` +
      `${profile.staff.count} employees, ${profile.devices.length} device(s), seed ${seed}\n`
  );

  const { punches, roster, summary } = generate({ profile, period, endDate, seed });

  console.log('Roster:');
  roster.slice(0, 8).forEach((p) =>
    console.log(
      `  ${p.employeeNo}  ${p.name.padEnd(24)} ${p.shift.padEnd(6)} ` +
        `usual door: ${profile.devices[p.homeDevice].name}`
    )
  );
  if (roster.length > 8) console.log(`  ... and ${roster.length - 8} more`);

  console.log(`
Plan:
  window            ${summary.from} -> ${summary.to}
  days / workdays   ${summary.days} / ${summary.workdays}
  punches           ${summary.punchCount}
  expected shifts   ${summary.expected}
  absent            ${summary.absent}
  late arrivals     ${summary.late}
  early leaves      ${summary.earlyLeave}
  overtime          ${summary.overtime}
  missing out-punch ${summary.missingOut}
  missing in-punch  ${summary.missingIn}
  double taps       ${summary.double}
`);

  if (dryRun) {
    console.log('Dry run — nothing sent. Sample of the first 8 punches:\n');
    punches.slice(0, 8).forEach((p) =>
      console.log(`  ${p.time}  ${p.employeeNo}  ${p.kind.padEnd(3)} ${p.method.padEnd(11)} ${p.deviceName}`)
    );
    return;
  }

  // --- enrol the roster on every device -----------------------------------
  // A person must exist on a terminal for its events to carry their name.
  if (!skipEnrol) {
    console.log('Enrolling roster on each device...');
    for (const device of profile.devices) {
      const client = new IsapiClient({
        host: '127.0.0.1',
        port: device.port,
        user: 'admin',
        pass: 'simulator123',
      });
      try {
        await client.deviceInfo();
      } catch {
        console.error(
          `  ${device.name} (port ${device.port}) is not responding — start it with 'npm run fleet'`
        );
        process.exit(1);
      }
      for (const person of roster) {
        await client.upsertPerson({ employeeNo: person.employeeNo, name: person.name });
      }
      console.log(`  ${device.name.padEnd(16)} ${roster.length} persons`);
    }
  }

  // --- deliver the punches -------------------------------------------------
  // Grouped per device so each terminal reports only its own traffic.
  const byDevice = new Map();
  for (const p of punches) {
    if (!byDevice.has(p.devicePort)) byDevice.set(p.devicePort, []);
    byDevice.get(p.devicePort).push(p);
  }

  console.log('\nInjecting punches...');
  let total = 0;
  let failed = 0;

  for (const [port, list] of byDevice) {
    const device = profile.devices.find((d) => d.port === port);
    // Chunked so a month of data does not become one enormous request body.
    const CHUNK = 250;
    let pushed = 0;
    let devFailed = 0;

    for (let i = 0; i < list.length; i += CHUNK) {
      const res = await fetch(`http://127.0.0.1:${port}/sim/inject`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          push: true,
          events: list.slice(i, i + CHUNK).map((p) => ({
            employeeNo: p.employeeNo,
            name: p.name,
            method: p.method,
            time: p.time,
            doorNo: p.doorNo,
            attendanceStatus: p.attendanceStatus,
          })),
        }),
      });
      const out = await res.json();
      pushed += out.pushed ?? 0;
      devFailed += out.failed ?? 0;
    }

    total += pushed;
    failed += devFailed;
    console.log(
      `  ${device.name.padEnd(16)} ${String(list.length).padStart(5)} punches -> ` +
        `${pushed} delivered${devFailed ? `, ${devFailed} failed` : ''}`
    );
  }

  console.log(`
Done. ${total} punches delivered${failed ? `, ${failed} failed` : ''}.

  curl 'http://localhost:8080/events?limit=20'
  curl 'http://localhost:8080/events?employeeNo=${roster[0].employeeNo}'

Re-run with the same --seed to reproduce this exact dataset.
`);
}

main().catch((err) => {
  console.error('Failed:', err.message);
  process.exit(1);
});

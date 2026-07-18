// Starts several simulated terminals at once, each self-registering to the
// same backend — the shape of a real multi-door site.
//
//   node simulator/fleet.js                          3 devices from 8100
//   node simulator/fleet.js --count 5 --from 8200
//   node simulator/fleet.js --webhook http://127.0.0.1:8080/hik/event
//
// Ctrl-C stops all of them.
const { spawn } = require('child_process');
const path = require('path');

const arg = (name, fallback) => {
  const i = process.argv.indexOf(`--${name}`);
  return i === -1 ? fallback : process.argv[i + 1];
};

const count = Number(arg('count', 3));
const from = Number(arg('from', 8100));
const webhook = arg('webhook', 'http://127.0.0.1:8080/hik/event');

// Names a real site might use, so log output is readable.
const NAMES = ['Main Entrance', 'Rear Door', 'Warehouse', 'Office Floor 2', 'Server Room'];

const children = [];

for (let i = 0; i < count; i += 1) {
  const port = from + i;
  const name = NAMES[i] ?? `Terminal ${i + 1}`;

  const child = spawn(
    process.execPath,
    [
      path.join(__dirname, 'device.js'),
      '--port', String(port),
      '--name', name,
      '--ip', `10.0.0.${10 + i}`,
      '--serial', `SIMFLEET${String(i + 1).padStart(3, '0')}`,
      '--webhook', webhook,
    ],
    { stdio: ['ignore', 'pipe', 'pipe'] }
  );

  // Prefix every line so interleaved output stays attributable.
  const tag = `[${name}:${port}]`;
  const relay = (stream, out) =>
    stream.on('data', (d) =>
      String(d)
        .split('\n')
        .filter((l) => l.trim())
        .forEach((l) => out(`${tag} ${l}`))
    );
  relay(child.stdout, console.log);
  relay(child.stderr, console.error);

  children.push({ child, name, port });
}

console.log(`
Fleet of ${count} simulated terminals, ports ${from}-${from + count - 1}
All pushing to ${webhook}

  Punch at a specific door:
    curl -X POST http://127.0.0.1:${from}/sim/punch -H 'Content-Type: application/json' \\
      -d '{"employeeNo":"1042","method":"fingerprint"}'

  Take one door offline (the others keep working):
    curl -X POST http://127.0.0.1:${from}/sim/offline

  Events arrive tagged by device_ip (10.0.0.10, 10.0.0.11, ...), so you can tell
  which door each punch came from:
    curl http://127.0.0.1:8080/events

Ctrl-C to stop all.
`);

const stopAll = () => {
  children.forEach(({ child }) => child.kill());
  process.exit(0);
};
process.on('SIGINT', stopAll);
process.on('SIGTERM', stopAll);

# How to use the simulator

A hands-on guide to the fake Hikvision terminal — for developing and testing the
backend with no physical device.

For the backend itself, see [HOW-TO-USE.md](HOW-TO-USE.md).

---

## Why it exists

You can't always have a real terminal plugged in — and you certainly can't ask a
real person to clock in a thousand times to test a report. The simulator is a
program that **behaves like a DS-K1T808MFWX-B**: it speaks the same protocol,
pushes the same punch events, and reproduces the same firmware quirks. Code that
works against it works against the real device.

It comes in three layers, smallest to largest:

| Tool | What it gives you |
| --- | --- |
| `npm run simulator` | One fake terminal |
| `npm run fleet` | Several fake terminals (a multi-door site) |
| `npm run attendance` | A day / week / month of realistic fake attendance |

---

## 1. One device

### Start it

```bash
npm run simulator
```

This runs a fake terminal on **port 8100** with login `admin` / `simulator123`.
It prints instructions on boot.

### Connect the backend to it

In another terminal:

```bash
DEVICE_HOST=127.0.0.1 DEVICE_PORT=8100 DEVICE_USER=admin DEVICE_PASS=simulator123 npm start
npm run register
```

The backend now treats the simulator exactly like real hardware.

### Trigger a punch

```bash
curl -X POST http://127.0.0.1:8100/sim/punch \
  -H 'Content-Type: application/json' \
  -d '{"employeeNo":"1042","method":"fingerprint"}'
```

Watch the backend window — it prints the punch and stores it. Confirm:

```bash
curl http://localhost:8080/events
```

### Punch types

```json
{ "employeeNo": "1042", "method": "fingerprint" }
{ "employeeNo": "2077", "method": "card" }
{ "employeeNo": "3001", "method": "face" }
{ "employeeNo": "3001", "method": "face", "success": false }
{ "method": "exitButton" }
```

`method`: `fingerprint` · `card` · `face` · `exitButton`.

> **PIN is not a built-in method.** No PIN minor code could be confirmed from
> Hikvision's docs, so the simulator doesn't invent one. To simulate it, pass a
> code yourself: `{ "employeeNo": "1042", "method": "pin", "minor": 1 }`.

### Command reference

These `/sim/*` routes drive the simulation (they are *not* part of the real
device API):

| Method | Route | Purpose |
| --- | --- | --- |
| `POST` | `/sim/punch` | Trigger a punch now |
| `POST` | `/sim/inject` | Add events at any past timestamp (bulk) |
| `POST` | `/sim/offline` | Device stops responding and stops pushing |
| `POST` | `/sim/online` | Device comes back |
| `POST` | `/sim/reset` | Clear its event log |
| `GET` | `/sim/status` | Persons, fingerprints, events held, push counts |

### Startup options

```bash
node simulator/device.js --port 8100 --name "Main Door" --capacity 500
```

| Flag | Default | Purpose |
| --- | --- | --- |
| `--port` | `8100` | Which port to listen on |
| `--user` / `--pass` | `admin` / `simulator123` | Login |
| `--name` | `Access Controller (SIMULATED)` | Device name in events |
| `--ip` | derived from port | The `device_ip` stamped on events |
| `--serial` | `SIM<port>` | Serial number |
| `--capacity` | `100000` | Event log size (set low to test overflow) |
| `--webhook` | none | Self-register this URL at boot (skips `npm run register`) |

---

## 2. Testing the offline-recovery path

This is the most valuable thing the simulator lets you rehearse: **proving no
punch is lost when the backend can't be reached.**

```bash
# take the device offline — punches now record to ITS log only
curl -X POST http://127.0.0.1:8100/sim/offline

curl -X POST http://127.0.0.1:8100/sim/punch -d '{"employeeNo":"1042","method":"fingerprint"}'

curl http://localhost:8080/events      # the punch is NOT here
curl http://127.0.0.1:8100/sim/status  # but the device's eventsStored went up

# bring it back — the backend recovers the gap
curl -X POST http://127.0.0.1:8100/sim/online
curl -X POST http://localhost:8080/events/backfill

curl http://localhost:8080/events      # now it's here, source: backfill
```

The backend also does this automatically (on startup, every 15 min, and on
device recovery) — the manual backfill above just avoids the wait.

---

## 3. Multiple devices (a fleet)

Real sites have several doors. Launch four at once:

```bash
npm run fleet
```

This starts terminals on ports 8100–8103, each with its own name, IP and serial,
each self-registering to the backend. Punch at a specific door by its port:

```bash
curl -X POST http://127.0.0.1:8100/sim/punch -d '{"employeeNo":"1042","method":"fingerprint"}'
curl -X POST http://127.0.0.1:8101/sim/punch -d '{"employeeNo":"2077","method":"card"}'
```

Events arrive tagged with which door they came from:

```
device=10.0.0.10  fingerprintAuthSuccess  emp=1042   (Main Entrance)
device=10.0.0.11  cardAuthSuccess         emp=2077   (Rear Door)
```

Options:

```bash
node simulator/fleet.js --count 5 --from 8200
node simulator/fleet.js --webhook http://127.0.0.1:8080/hik/event
```

Take one door offline (`/sim/offline` on its port) and the others keep working —
useful for testing partial outages.

---

## 4. Generating fake attendance

To test attendance reports you need volume — a month of punches for many people.
The generator produces exactly that, delivered across the fleet.

### Run it

```bash
npm run fleet                                          # 1. four doors
npm start                                              # 2. the backend
npm run attendance -- --period month --employees 20    # 3. generate
```

> Note the `--` before the flags. npm needs it to pass them through.

### Always preview first

```bash
npm run attendance -- --period month --employees 20 --dry-run
```

This prints the plan — how many punches, how many absent, late, missing — and
sends nothing. Look at it before committing data.

### Options

| Flag | Default | Purpose |
| --- | --- | --- |
| `--period` | `month` | `day` · `week` · `month` |
| `--employees` | `20` | Roster size |
| `--devices` | `4` | How many doors to spread punches across |
| `--preset` | `default` | `default` · `messy` · `clean` |
| `--seed` | `42` | Same seed ⇒ identical dataset |
| `--dry-run` | off | Print the plan, send nothing |

### Presets

| Preset | Use it for |
| --- | --- |
| `default` | Realistic mix — some late, absent, overtime, missed punches |
| `messy` | Heavy edge cases — stress-test how your report handles broken data |
| `clean` | Everyone present and on time — a baseline where totals have a known answer |

### What it simulates

The messy cases are the whole point — a report that only handles tidy in/out
pairs isn't tested:

- Late arrivals, early leaves, overtime
- **Missing out-punches** (an open shift — the classic payroll headache)
- Missing in-punches, double taps, absences
- Wrong door (someone used a terminal that isn't their usual one)
- Night shifts that clock out on the *next calendar day*

### Reproducibility

Same `--seed` always makes the same data, down to the second. If a report breaks
on seed 7, you can reproduce that exact dataset instead of regenerating and
hoping the bug comes back.

```bash
npm run attendance -- --period month --seed 7      # generate
# ...find a bug...
npm run attendance -- --period month --seed 7      # identical data, every time
```

### Customising

Edit `simulator/attendance/profile.js` to change shift times, workdays, holidays,
device names, or any behaviour probability. You can also supply a real roster
instead of generated names.

---

## Fidelity — why this isn't a toy

The simulator deliberately reproduces the awkward parts of the real V3.25.20
firmware, because a friendlier mock would let bugs through:

- Digest authentication on every request
- Answers **XML** even when you ask for JSON (`deviceInfo`, `httpHosts`)
- Rejects a JSON webhook config with `badXmlFormat`
- Rejects webhook passwords outside 8–16 characters with `badXmlContent`
- `CaptureFingerPrint` is XML-only and replies as multipart
- Duplicate `employeeNo` returns `deviceUserAlreadyExist`
- The event log is a ring buffer (`--capacity 500` to test overflow fast)
- Pushes a keep-alive every 30 seconds

Each of these caused real debugging against the actual device — reproducing them
here means you meet them in development, not in production.

---

## Keeping test data out of your real database

Point the backend at a throwaway database so a test run doesn't pollute
`data/events.db`:

```bash
DATA_DIR=/tmp/hik-test npm start
```

Delete that folder afterwards and your real data is untouched.

---

## Quick reference

```bash
npm run simulator                                   # one fake device (8100)
npm run fleet                                        # four fake devices (8100-8103)
npm run attendance -- --period week --dry-run        # preview a week of attendance
npm run attendance -- --period month --employees 20  # generate a month

curl -X POST 127.0.0.1:8100/sim/punch -d '{"employeeNo":"1042","method":"fingerprint"}'
curl -X POST 127.0.0.1:8100/sim/offline              # simulate an outage
curl      127.0.0.1:8100/sim/status                  # what the device holds
```

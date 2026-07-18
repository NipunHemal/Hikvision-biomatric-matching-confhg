# Device simulator

A fake DS-K1T808MFWX-B so you can build and test with no hardware.

It speaks real ISAPI — digest auth, person/card/fingerprint management, webhook
registration, a searchable event log — and lets you trigger punches on demand.

## Quick start

Two terminals.

```bash
# 1 — start the simulated device
npm run simulator
```

```bash
# 2 — point the backend at it and start
DEVICE_HOST=127.0.0.1 DEVICE_PORT=8100 DEVICE_USER=admin DEVICE_PASS=simulator123 npm start
npm run register
```

Then punch:

```bash
curl -X POST http://127.0.0.1:8100/sim/punch \
  -H 'Content-Type: application/json' \
  -d '{"employeeNo":"1042","method":"fingerprint"}'
```

The backend prints the punch block, exactly as it would for a real terminal.

## Options

| Flag | Default | Purpose |
| --- | --- | --- |
| `--port` | `8100` | ISAPI + control port |
| `--user` / `--pass` | `admin` / `simulator123` | digest credentials |
| `--name` | `Access Controller (SIMULATED)` | device name in events |
| `--ip` | derived from port | `device_ip` on stored events |
| `--serial` | `SIM<port>` | serial number |
| `--capacity` | `100000` | event ring-buffer size |
| `--webhook` | none | self-register this URL at boot, skipping `npm run register` |

## Control API

Not part of ISAPI — this is how you drive the simulation.

| Method | Route | Purpose |
| --- | --- | --- |
| POST | `/sim/punch` | trigger a punch |
| POST | `/sim/offline` | device stops responding and stops pushing |
| POST | `/sim/online` | device returns |
| POST | `/sim/reset` | clear the event log |
| GET | `/sim/status` | persons, fingerprints, events stored, webhook, push counts |

### Punch body

```json
{ "employeeNo": "1042", "method": "fingerprint", "success": true, "doorNo": 1 }
```

`method`: `fingerprint` · `card` · `face` · `exitButton`. Add `"success": false`
with `face` for a failed match.

**PIN has no simulated method.** I could not confirm a minor code for PIN in
Hikvision's documentation, and inventing one would teach you a wrong number.
Simulate it with an explicit code:

```json
{ "employeeNo": "1042", "method": "pin", "minor": 1 }
```

## Testing offline recovery

This is the scenario worth rehearsing — punches made while your backend can't be
reached must not be lost.

```bash
curl -X POST http://127.0.0.1:8100/sim/offline

# punches now record to the device log only
curl -X POST http://127.0.0.1:8100/sim/punch -d '{"employeeNo":"1042","method":"fingerprint"}'

curl http://127.0.0.1:8080/events        # not there
curl http://127.0.0.1:8100/sim/status    # but eventsStored went up

curl -X POST http://127.0.0.1:8100/sim/online
curl -X POST http://127.0.0.1:8080/events/backfill
curl http://127.0.0.1:8080/events        # recovered, source = backfill
```

Auto-sync does this on its own at startup, every `BACKFILL_INTERVAL_MIN`, and on
device recovery — the manual backfill above just avoids waiting.

## Multiple devices

Real sites have several doors. Start a fleet:

```bash
npm run fleet                                  # 3 devices from port 8100
node simulator/fleet.js --count 5 --from 8200
node simulator/fleet.js --webhook http://127.0.0.1:8080/hik/event
```

Each gets its own port, name, IP and serial, and self-registers to the same
backend. Punch at a specific door by port:

```bash
curl -X POST http://127.0.0.1:8100/sim/punch -d '{"employeeNo":"1042","method":"fingerprint"}'
curl -X POST http://127.0.0.1:8101/sim/punch -d '{"employeeNo":"2077","method":"card"}'
```

Events arrive tagged, so you can tell which door each punch came from:

```
device=10.0.0.10  fingerprintAuthSuccess  emp=1042     (Main Entrance)
device=10.0.0.11  cardAuthSuccess         emp=2077     (Rear Door)
device=10.0.0.12  faceAuthSuccess         emp=3001     (Warehouse)
```

Take one door offline and the others keep working — useful for testing partial
outages.

### Known limit

**The backend manages one device.** `DEVICE_HOST` is a single value, so
`/persons`, `/device/*` and backfill all talk to that one terminal. Event
*ingestion* is already multi-device (every punch is stored with its
`device_ip`), but enrolling a person across a fleet, or backfilling from all of
them, would need the config to become a list. Worth doing before you deploy more
than one door.

## Fidelity

The simulator deliberately reproduces quirks of the real V3.25.20 firmware,
because code that only works against a friendly mock breaks on the device:

- digest auth on every ISAPI request
- `deviceInfo` and `httpHosts` answer **XML** even with `?format=json`
- `httpHosts` PUT rejects a JSON body with `badXmlFormat`
- webhook passwords outside 8–16 characters rejected with `badXmlContent`
- `CaptureFingerPrint` is XML-only and replies `multipart/form-data`
- duplicate `employeeNo` returns `deviceUserAlreadyExist`
- more than 10 fingerprints per person returns `fingerPrintNumOverLimit`
- the event log is a ring buffer (`--capacity 500` to test rollover quickly)
- a keep-alive is pushed every 30s with no credential attached

Each of those cost real debugging time against the actual device.

## Testing against a throwaway database

Set `DATA_DIR` so a test run does not touch `data/events.db`:

```bash
DATA_DIR=/tmp/hik-test npm start
```

---

**Generating bulk attendance data?** See [ATTENDANCE.md](ATTENDANCE.md) — a day,
week or month of realistic punches across the fleet.

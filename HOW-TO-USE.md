# How to use this project

A step-by-step guide to running the backend, connecting a Hikvision terminal, and
working with attendance events.

New here? You do **not** need a physical device to try everything — jump to
[Path B](#path-b--no-hardware-the-simulator) and use the simulator. Full simulator
details are in [SIMULATOR-GUIDE.md](SIMULATOR-GUIDE.md).

---

## What this backend does

It sits between a Hikvision DS-K1T808MFWX-B access terminal and your systems:

- The terminal **pushes** a punch event (fingerprint / card / face / exit button)
  to this backend every time someone authenticates.
- The backend parses it, drops noise (heartbeats), and stores it in a local
  SQLite database.
- It also lets you **manage the device** — enrol people, cards, fingerprints,
  open doors — over a simple HTTP API.
- If the backend is down when a punch happens, it **recovers** the missed events
  from the device's own log automatically.

```
  Terminal ──push punch──> Backend ──> SQLite (data/events.db)
     ^                        │
     └──manage (enrol etc)────┘
```

---

## Install

```bash
npm install
cp .env.example .env
```

Then open `.env` and set the values. The ones that matter:

| Setting | What it is |
| --- | --- |
| `PORT` | Port this backend listens on (default `8080`) |
| `LISTENER_HOST` | **This machine's LAN IP** as the device sees it — not `127.0.0.1` |
| `WEBHOOK_PATH` | Where the device posts (default `/hik/event`) |
| `DEVICE_HOST` | The terminal's IP |
| `DEVICE_USER` / `DEVICE_PASS` | The terminal's admin login |
| `WEBHOOK_USER` / `WEBHOOK_PASS` | Optional auth the device uses when posting. Leave blank for none |

> **`LISTENER_HOST` is the #1 thing people get wrong.** The device is the client
> — it dials out to you — so it needs your real network address, not `localhost`.

---

## Path A — with a real terminal

### 1. Start the backend

```bash
npm start
```

You should see `Webhook listening on http://<your-ip>:8080/hik/event`.

### 2. Open the firewall (Windows)

Run once in an **Administrator** PowerShell, or the device's posts never arrive:

```powershell
New-NetFirewallRule -DisplayName "Hik webhook" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow -Profile Any
```

### 3. Confirm you can reach the device

```bash
curl http://localhost:8080/device/info
```

If this fails, stop here — nothing else will work. `401` = wrong password;
timeout = wrong `DEVICE_HOST`. (Careful: 5 wrong passwords locks the admin
account for 30 minutes.)

### 4. Point the device at the backend

```bash
npm run register      # tells the device where to post
npm run show-hosts    # confirm it stuck
```

### 5. Enrol a person and test

```bash
curl -X POST http://localhost:8080/persons \
  -H 'Content-Type: application/json' \
  -d '{"employeeNo":"1042","name":"Supun"}'
```

Fingerprints must be scanned at the terminal (**Menu → User → edit →
Fingerprint**) or enrolled via the API (below). Then present that finger — the
backend prints the punch:

```
================================================================
  PUNCH #1
  Event       fingerprintAuthSuccess  (major 5 / minor 113)
  Employee    1042
  Name        Supun
  Method      fingerprint   ->  SUCCESS
================================================================
```

### 6. Read the events

```bash
curl http://localhost:8080/events
curl "http://localhost:8080/events?employeeNo=1042"
curl "http://localhost:8080/events?method=fingerprint"
```

---

## Path B — no hardware (the simulator)

Three terminals, no device needed:

```bash
npm run simulator        # 1. a fake terminal on port 8100
```

```bash
# 2. the backend, pointed at the fake terminal
DEVICE_HOST=127.0.0.1 DEVICE_PORT=8100 DEVICE_USER=admin DEVICE_PASS=simulator123 npm start
npm run register
```

```bash
# 3. trigger a punch
curl -X POST http://127.0.0.1:8100/sim/punch \
  -H 'Content-Type: application/json' \
  -d '{"employeeNo":"1042","method":"fingerprint"}'
```

The backend reacts exactly as it would to a real device. Full details:
[SIMULATOR-GUIDE.md](SIMULATOR-GUIDE.md).

---

## The API

Everything the backend exposes. `localhost:8080` assumed.

### Events (attendance data)

| Method | Route | Purpose |
| --- | --- | --- |
| `GET` | `/events` | List punches. Filters: `?limit=&employeeNo=&method=&since=` |
| `GET` | `/events/:id/raw` | The exact payload the device sent for one event |
| `POST` | `/events/backfill` | Recover missed punches from the device log. `?start=&end=` |
| `DELETE` | `/events` | Purge local rows. `?before=&source=&confirm=true` (dry-run by default) |

```bash
curl "http://localhost:8080/events?since=2026-07-01T00:00:00&limit=100"
```

### People

| Method | Route | Purpose |
| --- | --- | --- |
| `GET` | `/persons` | List everyone enrolled on the device |
| `POST` | `/persons` | Create or update — `{employeeNo, name}` |
| `DELETE` | `/persons/:employeeNo` | Remove a person |
| `POST` | `/persons/:employeeNo/card` | Assign a card — `{cardNo}` |
| `POST` | `/persons/:employeeNo/face` | Enrol a face — JPEG as multipart field `face` |
| `GET` | `/persons/:employeeNo/fingerprints` | List a person's fingerprints |
| `POST` | `/persons/:employeeNo/fingerprint` | Enrol — scan now `{fingerPrintID}`, or apply `{fingerData}` |
| `DELETE` | `/persons/:employeeNo/fingerprints/:id` | Remove one finger |
| `POST` | `/fingerprint/capture` | Capture a template without assigning it |

```bash
# enrol a fingerprint by scanning at the terminal (blocks until a finger is presented)
curl -X POST http://localhost:8080/persons/1042/fingerprint \
  -H 'Content-Type: application/json' -d '{"fingerPrintID":1}'
```

### Device

| Method | Route | Purpose |
| --- | --- | --- |
| `GET` | `/device/health` | Online/offline — 200 healthy, 503 not |
| `GET` | `/device/info` | Model, firmware, serial |
| `GET` | `/device/time` | Device clock / NTP |
| `GET` | `/device/hosts` | Where the device is configured to post |
| `PUT` | `/doors/:doorNo` | `{cmd}` — open / close / alwaysOpen / alwaysClose / resume |

```bash
curl -X PUT http://localhost:8080/doors/1 -H 'Content-Type: application/json' -d '{"cmd":"open"}'
```

### Diagnostics

| Method | Route | Purpose |
| --- | --- | --- |
| `GET` | `/health` | Is the backend up |
| `GET` | `/debug/requests` | Last 20 raw inbound requests (set `DEBUG_REQUESTS=true`) |

---

## Understanding an event

Each stored punch has these key fields:

| Field | Meaning |
| --- | --- |
| `employee_no` | Your staff ID — the join key to your HR system |
| `person_name` | Name as enrolled on the device |
| `event_name` | `fingerprintAuthSuccess`, `cardAuthSuccess`, `faceAuthFail`, … |
| `verify_method` | `fingerprint` / `card` / `face` / `button` |
| `success` | `1` pass, `0` fail |
| `event_time` | When the punch happened (device clock) |
| `device_ip` | Which terminal it came from |
| `attendance` | `checkIn` / `checkOut` if the device is configured for it |

**Important:** `verify_method` comes from the event's *minor code*, not from
`currentVerifyMode`. The device only tells you what the door *accepts*
("fingerprintOrCard"); the minor code tells you what actually matched.

---

## Everyday tasks

**Recover punches after the backend was down**

```bash
npm run backfill                 # since the newest event you hold
npm run backfill 2026-07-01      # from a date
```

Also runs automatically — on startup, every 15 minutes, and when a device comes
back online.

**Check if the device is reachable**

```bash
curl http://localhost:8080/device/health
```

`reachable: true, receiving: false` means the device is up but its punches aren't
arriving — check the firewall and that `WEBHOOK_USER` in `.env` matches how the
device is configured.

**See exactly what the device sends** (debugging)

Set `DEBUG_REQUESTS=true` in `.env`, restart, then:

```bash
curl http://localhost:8080/debug/requests
```

**Clear old data**

```bash
curl -X DELETE "http://localhost:8080/events?before=2026-01-01"                # preview
curl -X DELETE "http://localhost:8080/events?before=2026-01-01&confirm=true"   # delete
```

This only touches your local database — the device's own log is never modified.

---

## Testing with Postman

Import both files from `postman/`:

- `hikvision-backend.postman_collection.json`
- `local.postman_environment.json`

Select the **"Hik backend - local"** environment. Most requests carry test
assertions, so you can run the whole collection at once.

---

## Troubleshooting

| Symptom | Likely cause |
| --- | --- |
| Simulated punches work, real ones don't | Firewall closed, or `WEBHOOK_USER` set but the device is configured for no auth |
| `/device/info` times out | Wrong `DEVICE_HOST`, or device on a different subnet |
| `/device/info` returns 401 | Wrong `DEVICE_PASS` — stop retrying, 5 fails locks the account 30 min |
| Nothing arrives, health shows `receiving: false` | The device can't reach you — firewall or wrong `LISTENER_HOST` |
| Events have wrong timestamps | Device clock drift — set NTP on the terminal |

---

## Where things live

| Path | What |
| --- | --- |
| `src/` | The backend code |
| `simulator/` | Fake device + attendance generator |
| `data/events.db` | Your stored events (created on first run) |
| `data/pictures/` | Face snapshots, if picture upload is on |
| `.env` | Your configuration |
| `postman/` | API collection |

For the simulator and fake attendance data, see
[SIMULATOR-GUIDE.md](SIMULATOR-GUIDE.md).

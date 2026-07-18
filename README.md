# Hikvision DS-K1T808MFWX-B — access control backend

Receives access events (fingerprint / face / card / exit button) pushed by the
terminal over ISAPI HTTP Host Notification, stores them in SQLite, and exposes
the device's person/card/fingerprint/door APIs.

## Documentation

| Doc | Covers |
| --- | --- |
| **README.md** (this file) | Backend setup, API routes, going live with real hardware |
| [SIMULATOR.md](SIMULATOR.md) | Fake terminal — develop and test with no device |
| [ATTENDANCE.md](ATTENDANCE.md) | Generate a day/week/month of fake attendance for HRM testing |

## I want to...

| Goal | Do this |
| --- | --- |
| Develop without hardware | `npm run simulator`, see [SIMULATOR.md](SIMULATOR.md) |
| Test 4 doors at once | `npm run fleet` |
| Fill a month of attendance | `npm run attendance -- --period month`, see [ATTENDANCE.md](ATTENDANCE.md) |
| Connect the real terminal | "Setup" and "Register the webhook" below |
| Check if the device is online | `curl localhost:8080/device/health` |
| Recover punches missed during downtime | `npm run backfill` (also automatic) |
| See exactly what the device sends | `DEBUG_REQUESTS=true` then `curl localhost:8080/debug/requests` |
| Exercise the API in Postman | import `postman/*.json` |

## Commands

```bash
npm start           # the backend
npm run register    # point the device at this server
npm run show-hosts  # read back the device's webhook config
npm run backfill    # pull missed punches from the device log
npm run simulator   # one fake terminal
npm run fleet       # four fake terminals
npm run attendance  # generate fake attendance data
```

## Setup

```bash
npm install
cp .env.example .env      # then edit it
```

`LISTENER_HOST` must be **this machine's LAN IP as the device sees it** — not
`127.0.0.1`. The terminal is the HTTP client; it dials out to you.

## Register the webhook on the device

```bash
npm run register      # PUT /ISAPI/Event/notification/httpHosts/1
npm run show-hosts    # read back what the device has configured
```

## Run

```bash
npm start
npm run backfill      # replay device log into the DB (also POST /events/backfill)
```

## Routes

**Events**

| Method | Route | Purpose |
| --- | --- | --- |
| POST | `/hik/event` | device posts here on every authentication |
| GET | `/events` | `?limit=&employeeNo=&method=&since=` |
| GET | `/events/:id/raw` | the exact payload the device sent |
| POST | `/events/backfill` | pull stored device events, `?start=&end=` |
| DELETE | `/events` | purge local rows — `?before=&source=&confirm=true` (dry run by default) |
| GET | `/pictures/*` | snapshots captured with events |

**Device**

| Method | Route | Purpose |
| --- | --- | --- |
| GET | `/device/health` | online/offline — 200 healthy, 503 not |
| GET | `/device/info` `/time` `/hosts` | identity, clock, notification targets |
| PUT | `/doors/:doorNo` | `{cmd}` — open/close/alwaysOpen/alwaysClose/resume |

**People**

| Method | Route | Purpose |
| --- | --- | --- |
| GET | `/persons` | all enrolled persons (auto-paginated) |
| POST | `/persons` | create or update — `{employeeNo, name}` |
| DELETE | `/persons/:employeeNo` | remove a person |
| POST | `/persons/:employeeNo/card` | assign a card — `{cardNo}` |
| POST | `/persons/:employeeNo/face` | enrol a face — JPEG as multipart field `face` |
| GET | `/persons/:employeeNo/fingerprints` | list enrolled fingers |
| POST | `/persons/:employeeNo/fingerprint` | enrol — scan now, or apply `{fingerData}` |
| DELETE | `/persons/:employeeNo/fingerprints/:id` | remove one finger |
| POST | `/fingerprint/capture` | capture a template without assigning it |

**Diagnostics**

| Method | Route | Purpose |
| --- | --- | --- |
| GET | `/health` | liveness |
| GET | `/debug/requests` | last 20 raw inbound requests (needs `DEBUG_REQUESTS=true`) |

```bash
curl -X POST localhost:8080/persons -H 'Content-Type: application/json' \
  -d '{"employeeNo":"1042","name":"Supun"}'
curl -X POST localhost:8080/persons/1042/face -F 'face=@supun.jpg'
curl -X PUT  localhost:8080/doors/1 -H 'Content-Type: application/json' -d '{"cmd":"open"}'
```

## Event codes

The **minor code identifies the credential** — this is the authoritative source,
not `currentVerifyMode`:

| Minor | `event_name`             | `verify_method` | `success` |
| ----- | ------------------------ | --------------- | --------- |
| 27    | `exitButtonPressed`      | button          | 1         |
| 38    | `cardAuthSuccess`        | card            | 1         |
| 75    | `faceAuthSuccess`        | face            | 1         |
| 76    | `faceAuthFail`           | face            | 0         |
| 113   | `fingerprintAuthSuccess` | fingerprint     | 1         |

`currentVerifyMode` reports what the door *accepts* (`"fingerprintOrCard"`), so it
is stored for context but never used to decide the method.

## Handled quirks

- **Two content types.** Plain JSON when picture upload is off; `multipart/form-data`
  (JSON part `event_log` + JPEG) when it's on. Both parsed.
- **Heartbeats.** The device pings every ~30s with no credential attached — dropped
  before insert. Exit-button events have no person either, but are kept.
- **Retries.** A non-2xx makes the device queue and re-send, so the webhook acks 200
  immediately; a unique index on `(device_ip, serial_no, event_time)` makes
  re-delivery idempotent.
- **Downtime gaps.** Events during an outage exist only in the terminal's log.
  `npm run backfill` replays them; dedup makes overlap harmless.
- **Upserts.** `POST /persons` creates, then falls back to Modify when the device
  answers `subStatusCode: deviceUserAlreadyExist`.
- **Pagination.** All Search endpoints use the `searchID` / `searchResultPosition` /
  `responseStatusStrg` cursor, iterated automatically.
- **Account lockout.** 5 bad logins lock admin for ~30 min. A `401` carrying
  `lockStatus` throws with the unlock time instead of retrying into a longer lock.
- **Face constraints.** JPEG, ≤200 KB, ≥80×80, single frontal face — size is
  checked before upload.

## Fingerprint enrolment

Fingerprints *can* be enrolled over the API on this model. Two modes:

```bash
# ask the terminal to scan now, then assign (blocks until a finger is presented)
curl -X POST localhost:8080/persons/1042/fingerprint \
  -H 'Content-Type: application/json' -d '{"fingerPrintID":1}'

# or apply a template captured earlier — use this to enrol the same finger
# onto several terminals without re-scanning
curl -X POST localhost:8080/persons/1042/fingerprint \
  -H 'Content-Type: application/json' -d '{"fingerPrintID":2,"fingerData":"<base64>"}'
```

`fingerPrintID` is the slot, 1–10. Max 10 fingers per person.

`FingerPrintDownload` is asynchronous: a 200 only means the job was accepted, so
the client polls `FingerPrintProgress` and reports the real per-module outcome
(`fingerprint already exists`, `memory full`, `quality poor, try again`, …).

Enrol two fingers per person — cuts and dry skin are routine, and one enrolled
finger means lockouts.

Enrolment can still be done at the terminal instead: **Menu → User → edit →
Fingerprint**.

## Before it works on a real device

- Open port `8080` inbound on the Windows firewall, or the POSTs never land.
- Keep device and server clocks NTP-synced — Digest auth rejects >5 min drift.
- If `WEBHOOK_USER` is set, the device is configured for HTTP **Basic** auth and
  the webhook enforces it. Clear it to disable.

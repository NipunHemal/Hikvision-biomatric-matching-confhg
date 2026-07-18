# Hikvision DS-K1T808MFWX-B — access control backend

Receives access events (fingerprint / face / card / exit button) pushed by the
terminal over ISAPI HTTP Host Notification, stores them in SQLite, and exposes
the device's person/card/face/door APIs.

Follows the conventions in
[uchkunr/hikvision-best-practices](https://github.com/uchkunr/hikvision-best-practices).

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

| Method | Route                            | Purpose                                       |
| ------ | -------------------------------- | --------------------------------------------- |
| POST   | `/hik/event`                     | device posts here on every authentication      |
| GET    | `/events`                        | `?limit=&employeeNo=&method=&since=`           |
| POST   | `/events/backfill`               | pull stored device events, `?start=&end=`      |
| GET    | `/pictures/*`                    | snapshots captured with events                 |
| GET    | `/device/info` `/time` `/hosts`  | device identity, clock, notification targets   |
| GET    | `/persons`                       | all enrolled persons (auto-paginated)          |
| POST   | `/persons`                       | create or update — `{employeeNo, name}`        |
| DELETE | `/persons/:employeeNo`           | remove a person                                |
| POST   | `/persons/:employeeNo/card`      | assign a card — `{cardNo}`                     |
| POST   | `/persons/:employeeNo/face`      | enrol a face — JPEG as multipart field `face`  |
| PUT    | `/doors/:doorNo`                 | `{cmd}` — open/close/alwaysOpen/alwaysClose/resume |
| GET    | `/health`                        | liveness                                       |

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

## Fingerprints cannot be enrolled over the API

There is no ISAPI endpoint to push a fingerprint template to this model — the
template must be captured by the terminal's own sensor. `POST /persons/:id/fingerprint`
returns `501` saying so. Enrol at the terminal: **Menu → User → edit → Fingerprint**,
or via the device web UI. Faces and cards *can* be pushed over the API.

Enrol two fingers per person — cuts and dry skin are routine, and one enrolled
finger means lockouts.

## Before it works on a real device

- Open port `8080` inbound on the Windows firewall, or the POSTs never land.
- Keep device and server clocks NTP-synced — Digest auth rejects >5 min drift.
- If `WEBHOOK_USER` is set, the device is configured for HTTP **Basic** auth and
  the webhook enforces it. Clear it to disable.

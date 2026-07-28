# Biometric Device Hub — API Reference

Complete reference for the HRM-facing HTTP/JSON API. Every endpoint, its payload,
its response, and a ready-to-run `curl`.

- **Base URL:** `http://<hub-host>:8090` (examples use `http://161.97.135.43:8090`)
- **Content type:** `application/json` for all request/response bodies
- **Auth:** `Authorization: Bearer <token>` on every route **except** `GET /health`
- **Postman:** import [`postman/biometric-hub.postman_collection.json`](../postman/biometric-hub.postman_collection.json)
  with [`postman/hub-production.postman_environment.json`](../postman/hub-production.postman_environment.json)

---

## Conventions

| Term | Meaning |
| --- | --- |
| `{id}` | A device's **ISUP Device ID** (e.g. `HRM02`, `SIM01`). Must be **online**. |
| `{employeeNo}` | The stable cross-system employee ID (e.g. `E100`). |
| **Device reply** | Write ops relay the device's raw ISAPI JSON, so the HRM sees exactly what the device returned. `statusCode:1` = success. |

### Status codes

| Code | Meaning |
| --- | --- |
| `200` | OK |
| `401` | Missing/invalid Bearer token |
| `403` | Simulation route called while `SIM_ENABLED=false` |
| `404` | Unknown route, or unknown card/finger/sim id |
| `502` | Device rejected the operation (see body) |
| `503` | Target device not online |
| `500` | Hub error |

### Error shape

```json
{ "ok": false, "error": "device not online: HRM02" }
```

### ⏱ Timing note (real devices)

Capture operations (`captureFingerprint`, `captureCard`) **block until the user
presents a finger/card**, and the device flushes its reply on its ~30 s ISUP
keepalive cycle — so a real capture call can take **up to ~30–45 s**. This is
normal. Simulated devices reply instantly.

---

## Authentication

Every request (except `GET /health`) must carry the token:

```bash
curl http://161.97.135.43:8090/devices \
  -H "Authorization: Bearer hub_tGCxvFTLuEG3SSHFWikXVnvxxCy1QdYW"
```

Missing/invalid token → `401`:
```json
{ "ok": false, "error": "unauthorized — provide Authorization: Bearer <token>" }
```

Tokens are set via the `API_TOKENS` env var (comma-separated). Empty = auth off
(dev only).

> In the examples below the header is abbreviated as `-H "$AUTH"` where
> `AUTH='Authorization: Bearer hub_tGCxvFTLuEG3SSHFWikXVnvxxCy1QdYW'`.

---

# 1. Hub

## GET /health `public`
Liveness + stack readiness. **No auth required.**

```bash
curl http://161.97.135.43:8090/health
```
```json
{ "ok": true, "isupReady": true, "devices": 2 }
```
`isupReady:true` = native ISUP stack loaded and accepting device registrations.

## GET /devices
All registered devices (online and offline).

```bash
curl http://161.97.135.43:8090/devices -H "$AUTH"
```
```json
[
  { "deviceId": "HRM01", "model": "DS-K1T808MFWX-B", "online": true,
    "adapter": "DsK1T808Adapter", "simulated": false },
  { "deviceId": "SIM01", "model": "DS-K1T808MFWX-B", "online": true,
    "adapter": "SimulatedDeviceAdapter", "simulated": true }
]
```

---

# 2. Device

## GET /devices/{id}
Brief info for one device.
```bash
curl http://161.97.135.43:8090/devices/HRM01 -H "$AUTH"
```
```json
{ "deviceId": "HRM01", "model": "DS-K1T808MFWX-B", "online": true,
  "adapter": "DsK1T808Adapter", "simulated": false }
```

## GET /devices/{id}/info
Live `GET /ISAPI/System/deviceInfo` over ISUP (relayed device reply).
```bash
curl http://161.97.135.43:8090/devices/HRM01/info -H "$AUTH"
```
```json
{ "DeviceInfo": { "deviceName": "Access Controller", "model": "DS-K1T808MFWX-B",
  "serialNumber": "...", "firmwareVersion": "V3.2.x" } }
```

## GET /devices/{id}/capabilities
What the model supports (drives the HRM UI).
```bash
curl http://161.97.135.43:8090/devices/HRM01/capabilities -H "$AUTH"
```
```json
{ "persons": true, "cards": true, "fingerprint": true, "face": true,
  "maxFingerprintsPerPerson": 10 }
```

## POST /devices/{id}/door
Remote door control.

| Field | Type | Default | Notes |
| --- | --- | --- | --- |
| `doorNo` | int | `1` | Door/relay number |
| `cmd` | string | `open` | `open` · `close` · `alwaysOpen` · `alwaysClose` · `resume` |

```bash
curl -X POST http://161.97.135.43:8090/devices/HRM01/door -H "$AUTH" \
  -H "Content-Type: application/json" -d '{"doorNo":1,"cmd":"open"}'
```
```json
{ "statusCode": 1, "statusString": "OK", "subStatusCode": "ok" }
```

---

# 3. Persons

## GET /devices/{id}/persons
List enrolled persons (auto-paginated).
```bash
curl http://161.97.135.43:8090/devices/HRM01/persons -H "$AUTH"
```
```json
[ { "employeeNo": "E100", "name": "Kamal Perera", "userType": "normal",
    "beginTime": "2026-01-01T00:00:00", "endTime": "2030-12-31T23:59:59" } ]
```

## POST /devices/{id}/persons
Create or update a person (auto-falls back to Modify if `employeeNo` exists).

| Field | Type | Required | Default |
| --- | --- | --- | --- |
| `employeeNo` | string | ✅ | — |
| `name` | string | ✅ | — |
| `beginTime` | string | — | `2026-01-01T00:00:00` |
| `endTime` | string | — | `2030-12-31T23:59:59` |
| `pin` | string | — | — |

```bash
curl -X POST http://161.97.135.43:8090/devices/HRM01/persons -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d '{"employeeNo":"E100","name":"Kamal Perera"}'
```
```json
{ "statusCode": 1, "statusString": "OK", "subStatusCode": "ok" }
```

## DELETE /devices/{id}/persons/{employeeNo}
Remove a person from one device.
```bash
curl -X DELETE http://161.97.135.43:8090/devices/HRM01/persons/E100 -H "$AUTH"
```
```json
{ "statusCode": 1, "statusString": "OK", "subStatusCode": "ok" }
```

## GET /devices/{id}/persons/{employeeNo}/exists
Is this employee enrolled on this device?
```bash
curl http://161.97.135.43:8090/devices/HRM01/persons/E100/exists -H "$AUTH"
```
```json
{ "employeeNo": "E100", "exists": true, "name": "Kamal Perera" }
```

## GET /devices/{id}/persons/{employeeNo}/details
Full profile in one call — person + fingerprints (with templates) + cards + pin.
```bash
curl http://161.97.135.43:8090/devices/HRM01/persons/E100/details -H "$AUTH"
```
```json
{
  "employeeNo": "E100", "exists": true,
  "person": { "employeeNo": "E100", "name": "Kamal Perera", "userType": "normal",
              "beginTime": "2026-01-01T00:00:00", "endTime": "2030-12-31T23:59:59",
              "pin": null },
  "fingerprints": [ { "fingerPrintID": 1, "fingerType": "normalFP",
                      "cardReaderNo": 1, "fingerData": "<base64>" } ],
  "fingerprintCount": 1,
  "cards": { "CardInfoSearch": { "...": "device reply" } },
  "pinNote": "PIN is write-only on hardware devices; null means not retrievable"
}
```
> **PIN:** real terminals never return the password (write-only) → `pin: null`.
> The simulator does return it.

---

# 4. PIN

## POST /devices/{id}/persons/{employeeNo}/pin
Set a person's access PIN / password.

| Field | Type | Required |
| --- | --- | --- |
| `pin` | string | ✅ |

```bash
curl -X POST http://161.97.135.43:8090/devices/HRM01/persons/E100/pin -H "$AUTH" \
  -H "Content-Type: application/json" -d '{"pin":"123456"}'
```
```json
{ "statusCode": 1, "statusString": "OK", "subStatusCode": "ok" }
```

---

# 5. Cards

## POST /devices/{id}/persons/{employeeNo}/card
Assign a known card number to a person.

| Field | Type | Required | Default |
| --- | --- | --- | --- |
| `cardNo` | string | ✅ | — |
| `cardType` | string | — | `normalCard` |

```bash
curl -X POST http://161.97.135.43:8090/devices/HRM01/persons/E100/card -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d '{"cardNo":"0012345678","cardType":"normalCard"}'
```
```json
{ "statusCode": 1, "statusString": "OK", "subStatusCode": "ok" }
```

## GET /devices/{id}/persons/{employeeNo}/cards
List a person's cards (relayed device reply).
```bash
curl http://161.97.135.43:8090/devices/HRM01/persons/E100/cards -H "$AUTH"
```
```json
{ "CardInfoSearch": { "searchID": "...", "responseStatusStrg": "OK",
  "CardInfo": [ { "employeeNo": "E100", "cardNo": "0012345678",
    "cardType": "normalCard" } ] } }
```

## DELETE /devices/{id}/persons/{employeeNo}/cards/{cardNo}
Remove one card.
```bash
curl -X DELETE http://161.97.135.43:8090/devices/HRM01/persons/E100/cards/0012345678 \
  -H "$AUTH"
```
```json
{ "statusCode": 1, "statusString": "OK", "subStatusCode": "ok" }
```

## POST /devices/{id}/card/capture
**Read a card at the reader** (no assignment). The user taps a card; the hub
returns its number. Blocks until a card is presented (see timing note).
```bash
curl -X POST http://161.97.135.43:8090/devices/HRM01/card/capture -H "$AUTH" \
  -H "Content-Type: application/json" -d '{}'
```
Success:
```json
{ "cardNo": "100001000", "cardType": "normalCard" }
```
No card / failure (raw device reply included for diagnosis):
```json
{ "ok": false, "error": "no card captured", "deviceReply": "..." }
```

---

# 6. Fingerprints

## GET /devices/{id}/persons/{employeeNo}/fingerprints
List a person's fingerprints, including the Base64 template.
```bash
curl http://161.97.135.43:8090/devices/HRM01/persons/E100/fingerprints -H "$AUTH"
```
```json
[ { "employeeNo": "E100", "fingerPrintID": 1, "fingerType": "normalFP",
    "cardReaderNo": 1, "fingerData": "<base64>" } ]
```

## POST /devices/{id}/persons/{employeeNo}/fingerprint
Push a single template to the device.

| Field | Type | Required | Default |
| --- | --- | --- | --- |
| `fingerData` | string (base64) | ✅ | — |
| `fingerPrintID` | int (1–10) | — | `1` |

```bash
curl -X POST http://161.97.135.43:8090/devices/HRM01/persons/E100/fingerprint \
  -H "$AUTH" -H "Content-Type: application/json" \
  -d '{"fingerPrintID":1,"fingerData":"<base64>"}'
```
```json
{ "statusCode": 1, "statusString": "OK", "subStatusCode": "ok" }
```

## POST /devices/{id}/fingerprint/capture
**Scan a fingerprint at the terminal** and return the template. Blocks until a
finger is presented (see timing note).

| Field | Type | Default |
| --- | --- | --- |
| `fingerNo` | int | `1` |

```bash
curl -X POST http://161.97.135.43:8090/devices/HRM01/fingerprint/capture -H "$AUTH" \
  -H "Content-Type: application/json" -d '{"fingerNo":1}'
```
```json
{ "fingerNo": 1, "fingerPrintQuality": 73, "fingerData": "<base64>" }
```

## DELETE /devices/{id}/persons/{employeeNo}/fingerprints/{fingerPrintID}
Remove **one** finger.
```bash
curl -X DELETE \
  http://161.97.135.43:8090/devices/HRM01/persons/E100/fingerprints/1 -H "$AUTH"
```

## DELETE /devices/{id}/persons/{employeeNo}/fingerprints
Remove **all** of a person's fingerprints on this device.
```bash
curl -X DELETE \
  http://161.97.135.43:8090/devices/HRM01/persons/E100/fingerprints -H "$AUTH"
```

## PUT /devices/{id}/persons/{employeeNo}/fingerprints ⭐ override
**Replace the whole set:** delete every existing finger, then insert the supplied
templates (one or many). Idempotent re-enrolment.

| Field | Type | Required |
| --- | --- | --- |
| `fingerprints` | array | ✅ |
| `fingerprints[].fingerData` | string (base64) | ✅ |
| `fingerprints[].fingerPrintID` | int | — (defaults to index) |
| `fingerprints[].fingerType` | string | — (`normalFP`) |

```bash
curl -X PUT http://161.97.135.43:8090/devices/HRM01/persons/E100/fingerprints \
  -H "$AUTH" -H "Content-Type: application/json" -d '{
    "fingerprints": [
      {"fingerPrintID":1,"fingerData":"<base64-1>"},
      {"fingerPrintID":2,"fingerData":"<base64-2>"}
    ] }'
```
```json
{ "employeeNo": "E100", "previousDeleted": true, "requested": 2, "inserted": 2,
  "fingers": [ { "fingerPrintID": 1, "ok": true, "detail": "..." },
               { "fingerPrintID": 2, "ok": true, "detail": "..." } ] }
```

---

# 7. Multi-device operations

These act across **all online devices** (or a `targetDeviceIds` subset).

## POST /persons/broadcast
Create/update a person on **every** online device.

| Field | Type | Required |
| --- | --- | --- |
| `employeeNo` | string | ✅ |
| `name` | string | ✅ |

```bash
curl -X POST http://161.97.135.43:8090/persons/broadcast -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d '{"employeeNo":"E100","name":"Kamal Perera"}'
```
```json
[ { "deviceId": "HRM01", "ok": true, "reply": "..." },
  { "deviceId": "HRM02", "ok": true, "reply": "..." } ]
```

## DELETE /persons/broadcast/{employeeNo}
Remove a person from **every** online device.
```bash
curl -X DELETE http://161.97.135.43:8090/persons/broadcast/E100 -H "$AUTH"
```
```json
[ { "deviceId": "HRM01", "ok": true, "reply": "..." } ]
```

## GET /persons/{employeeNo}/exists
Check the employee **across every device** — which branches have them, and how
many fingerprints each holds.
```bash
curl http://161.97.135.43:8090/persons/E100/exists -H "$AUTH"
```
```json
{
  "employeeNo": "E100", "existsOnCount": 2,
  "devices": [
    { "deviceId": "HRM01", "online": true, "exists": true, "fingerprintCount": 2 },
    { "deviceId": "HRM02", "online": true, "exists": false },
    { "deviceId": "HRM03", "online": false, "exists": false, "reason": "offline" }
  ]
}
```

## PUT /persons/{employeeNo}/fingerprints/broadcast ⭐ override on many
Override an employee's fingerprints on **every** online device at once (or a
subset via `targetDeviceIds`). Each device is wiped then re-inserted.

| Field | Type | Required |
| --- | --- | --- |
| `fingerprints` | array | ✅ (same shape as single override) |
| `targetDeviceIds` | array | — (omit = all online) |

```bash
curl -X PUT http://161.97.135.43:8090/persons/E100/fingerprints/broadcast \
  -H "$AUTH" -H "Content-Type: application/json" -d '{
    "fingerprints": [ {"fingerPrintID":1,"fingerData":"<base64>"} ],
    "targetDeviceIds": ["HRM02","HRM03"] }'
```
```json
{ "employeeNo": "E100", "fingerprintsPerDevice": 1, "devicesUpdated": 2,
  "results": [
    { "deviceId": "HRM02", "previousDeleted": true, "requested": 1, "inserted": 1 },
    { "deviceId": "HRM03", "previousDeleted": true, "requested": 1, "inserted": 1 }
  ] }
```

## POST /fingerprints/sync ⭐ cross-branch
Read a person's template(s) from a **source** device and push them to every other
online device (or a subset). This is how a fingerprint enrolled at one branch
reaches all branches.

| Field | Type | Required |
| --- | --- | --- |
| `sourceDeviceId` | string | ✅ |
| `employeeNo` | string | ✅ |
| `targetDeviceIds` | array | — (omit = all other online) |

```bash
curl -X POST http://161.97.135.43:8090/fingerprints/sync -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d '{"sourceDeviceId":"HRM01","employeeNo":"E100"}'
```
```json
{
  "employeeNo": "E100", "sourceDeviceId": "HRM01", "templatesFound": 2,
  "targets": [
    { "deviceId": "HRM02", "ok": true, "pushed": 2, "detail": "" },
    { "deviceId": "HRM03", "ok": true, "pushed": 2, "detail": "" }
  ]
}
```

---

# 8. Enrolment workflows (capture + assign)

Composite operations — the multi-step, real-world flows the HRM wants as one call.

## POST /devices/{id}/persons/enroll
Create a person, then optionally capture & assign a fingerprint in the same call.

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `employeeNo` | string | ✅ | |
| `name` | string | ✅ | |
| `pin` | string | — | |
| `fingerPrintID` | int | — | if present, capture+assign that finger |

```bash
curl -X POST http://161.97.135.43:8090/devices/HRM01/persons/enroll -H "$AUTH" \
  -H "Content-Type: application/json" \
  -d '{"employeeNo":"E100","name":"Kamal Perera","fingerPrintID":1}'
```
```json
{ "employeeNo": "E100", "personCreated": true, "personReply": "...",
  "fingerprintCaptured": true,
  "fingerprint": { "employeeNo": "E100", "fingerPrintID": 1, "quality": 73,
                   "assigned": true } }
```

## POST /devices/{id}/persons/{employeeNo}/fingerprint/capture
Capture **one** finger at the reader and assign it to the person.

| Field | Type | Default |
| --- | --- | --- |
| `fingerPrintID` | int | `1` |

```bash
curl -X POST \
  http://161.97.135.43:8090/devices/HRM01/persons/E100/fingerprint/capture \
  -H "$AUTH" -H "Content-Type: application/json" -d '{"fingerPrintID":1}'
```
```json
{ "employeeNo": "E100", "fingerPrintID": 1, "quality": 73, "assigned": true,
  "deviceReply": "..." }
```

## POST /devices/{id}/persons/{employeeNo}/fingerprint/capture-bulk
Capture **several** fingers in sequence and assign each. Supply explicit IDs or a
count.

| Field | Type | Notes |
| --- | --- | --- |
| `fingerPrintIDs` | array of int | e.g. `[1,2,3]` |
| `count` | int | alternative — captures fingers `1..count` (default `2`) |

```bash
curl -X POST \
  http://161.97.135.43:8090/devices/HRM01/persons/E100/fingerprint/capture-bulk \
  -H "$AUTH" -H "Content-Type: application/json" -d '{"fingerPrintIDs":[1,2]}'
```
```json
{ "employeeNo": "E100", "requested": 2, "succeeded": 2,
  "fingers": [ { "fingerPrintID": 1, "ok": true, "detail": "..." },
               { "fingerPrintID": 2, "ok": true, "detail": "..." } ] }
```

## POST /devices/{id}/persons/{employeeNo}/card/capture
Capture a card at the reader and assign it to the person.

| Field | Type | Default |
| --- | --- | --- |
| `cardType` | string | `normalCard` |

```bash
curl -X POST \
  http://161.97.135.43:8090/devices/HRM01/persons/E100/card/capture \
  -H "$AUTH" -H "Content-Type: application/json" -d '{"cardType":"normalCard"}'
```
```json
{ "employeeNo": "E100", "cardNo": "100001000", "assigned": true,
  "deviceReply": "..." }
```

---

# 9. Simulation (test without hardware)

Only when `SIM_ENABLED=true`; otherwise every `/sim/*` route returns `403`. All
`/sim/*` routes still require the Bearer token. Full guide:
[`docs/SIMULATOR.md`](SIMULATOR.md).

## POST /sim/devices
Add an in-memory device (comes online immediately).

| Field | Type | Required | Default |
| --- | --- | --- | --- |
| `deviceId` | string | ✅ | — |
| `model` | string | — | `DS-K1T808MFWX-B` |

```bash
curl -X POST http://161.97.135.43:8090/sim/devices -H "$AUTH" \
  -H "Content-Type: application/json" -d '{"deviceId":"SIM01"}'
```
```json
{ "deviceId": "SIM01", "model": "DS-K1T808MFWX-B", "online": true,
  "adapter": "SimulatedDeviceAdapter", "simulated": true }
```

## POST /sim/devices/{id}/offline · POST /sim/devices/{id}/online
Toggle a simulated device (mimic power/network loss).
```bash
curl -X POST http://161.97.135.43:8090/sim/devices/SIM01/offline -H "$AUTH"
curl -X POST http://161.97.135.43:8090/sim/devices/SIM01/online  -H "$AUTH"
```
```json
{ "deviceId": "SIM01", "online": false }
```

## DELETE /sim/devices/{id}
Remove a simulated device.
```bash
curl -X DELETE http://161.97.135.43:8090/sim/devices/SIM01 -H "$AUTH"
```
```json
{ "removed": "SIM01" }
```

---

# Endpoint index

| # | Method | Path | Section |
| --- | --- | --- | --- |
| 1 | GET | `/health` | Hub (public) |
| 2 | GET | `/devices` | Hub |
| 3 | GET | `/devices/{id}` | Device |
| 4 | GET | `/devices/{id}/info` | Device |
| 5 | GET | `/devices/{id}/capabilities` | Device |
| 6 | POST | `/devices/{id}/door` | Device |
| 7 | GET | `/devices/{id}/persons` | Persons |
| 8 | POST | `/devices/{id}/persons` | Persons |
| 9 | DELETE | `/devices/{id}/persons/{employeeNo}` | Persons |
| 10 | GET | `/devices/{id}/persons/{employeeNo}/exists` | Persons |
| 11 | GET | `/devices/{id}/persons/{employeeNo}/details` | Persons |
| 12 | POST | `/devices/{id}/persons/{employeeNo}/pin` | PIN |
| 13 | POST | `/devices/{id}/persons/{employeeNo}/card` | Cards |
| 14 | GET | `/devices/{id}/persons/{employeeNo}/cards` | Cards |
| 15 | DELETE | `/devices/{id}/persons/{employeeNo}/cards/{cardNo}` | Cards |
| 16 | POST | `/devices/{id}/card/capture` | Cards |
| 17 | GET | `/devices/{id}/persons/{employeeNo}/fingerprints` | Fingerprints |
| 18 | POST | `/devices/{id}/persons/{employeeNo}/fingerprint` | Fingerprints |
| 19 | POST | `/devices/{id}/fingerprint/capture` | Fingerprints |
| 20 | DELETE | `/devices/{id}/persons/{employeeNo}/fingerprints/{fingerPrintID}` | Fingerprints |
| 21 | DELETE | `/devices/{id}/persons/{employeeNo}/fingerprints` | Fingerprints |
| 22 | PUT | `/devices/{id}/persons/{employeeNo}/fingerprints` | Fingerprints (override) |
| 23 | POST | `/persons/broadcast` | Multi-device |
| 24 | DELETE | `/persons/broadcast/{employeeNo}` | Multi-device |
| 25 | GET | `/persons/{employeeNo}/exists` | Multi-device |
| 26 | PUT | `/persons/{employeeNo}/fingerprints/broadcast` | Multi-device (override) |
| 27 | POST | `/fingerprints/sync` | Multi-device (cross-branch) |
| 28 | POST | `/devices/{id}/persons/enroll` | Enrolment workflows |
| 29 | POST | `/devices/{id}/persons/{employeeNo}/fingerprint/capture` | Enrolment workflows |
| 30 | POST | `/devices/{id}/persons/{employeeNo}/fingerprint/capture-bulk` | Enrolment workflows |
| 31 | POST | `/devices/{id}/persons/{employeeNo}/card/capture` | Enrolment workflows |
| 32 | POST | `/sim/devices` | Simulation |
| 33 | POST | `/sim/devices/{id}/online` | Simulation |
| 34 | POST | `/sim/devices/{id}/offline` | Simulation |
| 35 | DELETE | `/sim/devices/{id}` | Simulation |

---

# Typical HRM flows

**Enroll an employee across all branches**
```
POST /persons/broadcast                     {employeeNo, name}   # create everywhere
# person enrolls a fingerprint at ONE branch terminal:
POST /devices/HRM01/persons/E100/fingerprint/capture  {fingerPrintID:1}
POST /fingerprints/sync                     {sourceDeviceId:"HRM01", employeeNo:"E100"}  # replicate
```

**Re-enroll (replace) fingerprints everywhere**
```
PUT  /persons/E100/fingerprints/broadcast   {fingerprints:[...]}
```

**Check where an employee exists**
```
GET  /persons/E100/exists
```

**Remote unlock**
```
POST /devices/HRM01/door                    {doorNo:1, cmd:"open"}
```

**Offboard**
```
DELETE /persons/broadcast/E100
```

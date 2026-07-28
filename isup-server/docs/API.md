# HTTP API reference

The HRM-facing API. Base URL: `http://<hub>:8090`. All bodies are JSON.
`{id}` is a device's ISUP Device ID (e.g. `HRM02`); it must be **online**
(`GET /devices`) or the call returns `503`.

A Postman collection with every request is at
[`postman/biometric-hub.postman_collection.json`](../postman/biometric-hub.postman_collection.json) (import with `hub-production.postman_environment.json`).

## Conventions

- Write operations relay the device's raw ISAPI reply (JSON), so the HRM sees
  exactly what the device returned (e.g. `statusCode:1` = success).
- `503` = target device not online. `502` = device rejected the operation.
  `500` = hub error. `404` = unknown route.

---

## Hub

### GET /health
Liveness. → `{"ok":true}`

### GET /devices
All online devices.
```json
[{"deviceId":"HRM01","model":"DS-K1T808MFWX-B","online":true,"adapter":"DsK1T808Adapter"}]
```

---

## Device

### GET /devices/{id}
Brief: id, model, online, adapter.

### GET /devices/{id}/info
Live `/ISAPI/System/deviceInfo` over ISUP.

### GET /devices/{id}/capabilities
What the model supports.
```json
{"persons":true,"cards":true,"fingerprint":true,"face":true,"maxFingerprintsPerPerson":10}
```

---

## Persons

### GET /devices/{id}/persons
List enrolled persons (auto-paginated).

### POST /devices/{id}/persons
Create or update (auto Modify fallback if the employeeNo exists).
```json
{ "employeeNo": "E123", "name": "Kamal Perera" }
```
Optional: `beginTime`, `endTime` (validity window).

### DELETE /devices/{id}/persons/{employeeNo}
Remove a person.

### GET /devices/{id}/persons/{employeeNo}/exists
Is this employee enrolled on this device?
```json
{ "employeeNo": "E123", "exists": true, "name": "Kamal Perera" }
```

### GET /devices/{id}/persons/{employeeNo}/details
Full profile in one call — person data + fingerprints (with templates) + cards + pin.
```json
{
  "employeeNo": "E123", "exists": true,
  "person": { "name": "Kamal Perera", "userType": "normal",
              "beginTime": "...", "endTime": "...", "pin": null },
  "fingerprints": [ { "fingerPrintID": 1, "fingerType": "normalFP", "fingerData": "<base64>" } ],
  "fingerprintCount": 1,
  "cards": { "CardInfoSearch": { "...": "device reply" } },
  "pinNote": "PIN is write-only on hardware devices; null means not retrievable"
}
```

### GET /persons/{employeeNo}/exists
Check the employee across **every** device (which branches has them, and how many
fingerprints each holds).
```json
{
  "employeeNo": "E123", "existsOnCount": 2,
  "devices": [
    { "deviceId": "HRM01", "online": true, "exists": true, "fingerprintCount": 2 },
    { "deviceId": "HRM02", "online": true, "exists": false },
    { "deviceId": "HRM03", "online": false, "exists": false, "reason": "offline" }
  ]
}
```

### POST /persons/broadcast
Register/update on **every** online device at once.
```json
{ "employeeNo": "E123", "name": "Kamal Perera" }
```
→ per-device result array.

### DELETE /persons/broadcast/{employeeNo}
Remove from every online device.

---

## Cards

### POST /devices/{id}/persons/{employeeNo}/card
```json
{ "cardNo": "0012345678", "cardType": "normalCard" }
```

---

## Fingerprints

### GET /devices/{id}/persons/{employeeNo}/fingerprints
List the person's fingerprints, including the Base64 template.
```json
[{"employeeNo":"E123","fingerPrintID":1,"fingerType":"normalFP","cardReaderNo":1,"fingerData":"<base64>"}]
```

### POST /devices/{id}/persons/{employeeNo}/fingerprint
Push a template to the device.
```json
{ "fingerPrintID": 1, "fingerData": "<base64>" }
```

### DELETE /devices/{id}/persons/{employeeNo}/fingerprints/{fingerPrintID}
Remove one finger.

### DELETE /devices/{id}/persons/{employeeNo}/fingerprints
Remove **all** of a person's fingerprints on this device.

### PUT /devices/{id}/persons/{employeeNo}/fingerprints  ⭐ override
Replace the whole set: delete every existing finger, then insert the supplied
templates (one or many). Idempotent re-enrolment.
```json
{ "fingerprints": [
    { "fingerPrintID": 1, "fingerData": "<base64>" },
    { "fingerPrintID": 2, "fingerData": "<base64>" }
] }
```
```json
{ "employeeNo": "E123", "previousDeleted": true, "requested": 2, "inserted": 2,
  "fingers": [ {"fingerPrintID":1,"ok":true}, {"fingerPrintID":2,"ok":true} ] }
```

### PUT /persons/{employeeNo}/fingerprints/broadcast  ⭐ override on many
Override an employee's fingerprints on **every** online device at once (or a
subset via `targetDeviceIds`).
```json
{ "fingerprints": [ {"fingerPrintID":1,"fingerData":"<base64>"} ],
  "targetDeviceIds": ["HRM02","HRM03"] }
```
```json
{ "employeeNo": "E123", "fingerprintsPerDevice": 1, "devicesUpdated": 2,
  "results": [ {"deviceId":"HRM02","previousDeleted":true,"inserted":1}, ... ] }
```

### POST /fingerprints/sync  ⭐
Cross-branch: read a person's template(s) from the source device and push them to
every other online device.
```json
{ "sourceDeviceId": "HRM01", "employeeNo": "E123" }
```
Optional `"targetDeviceIds": ["HRM03","HRM04"]` to restrict targets.
```json
{
  "employeeNo": "E123",
  "sourceDeviceId": "HRM01",
  "templatesFound": 2,
  "targets": [
    {"deviceId":"HRM02","ok":true,"pushed":2,"detail":""},
    {"deviceId":"HRM03","ok":true,"pushed":2,"detail":""}
  ]
}
```

---

## Doors

### POST /devices/{id}/door
```json
{ "doorNo": 1, "cmd": "open" }
```
`cmd`: `open` · `close` · `alwaysOpen` · `alwaysClose` · `resume`

---

## Simulation (test without hardware)

When `SIM_ENABLED=true`, in-memory simulated devices can be created and driven
through every endpoint above — no terminal required. See
[`docs/SIMULATOR.md`](SIMULATOR.md).

- `POST /sim/devices` `{deviceId, model?}` — add (comes online)
- `POST /sim/devices/{id}/offline` · `/online` — toggle
- `DELETE /sim/devices/{id}` — remove

All `/sim/*` routes require the Bearer token and return `403` when `SIM_ENABLED`
is off.

---

## Typical HRM flows

**Enroll an employee across all branches**
```
POST /persons/broadcast            {employeeNo, name}     # create everywhere
# person enrolls fingerprint at ONE branch (at the terminal)
POST /fingerprints/sync            {sourceDeviceId, employeeNo}  # replicate to all
```

**Remote unlock**
```
POST /devices/HRM01/door           {doorNo:1, cmd:"open"}
```

**Offboard**
```
DELETE /persons/broadcast/E123
```

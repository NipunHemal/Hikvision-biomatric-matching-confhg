# Device Simulator — test the hub without hardware

The hub has a built-in **device simulator**: a fully in-memory device
(`SimulatedDeviceAdapter`) that behaves like a DS-K1T808 terminal — it stores
persons, cards and fingerprints and answers every API call — but touches no
network and needs no real terminal. Use it to test the whole HTTP API, the
Postman collection, and your HRM integration end-to-end.

## Why not a network-level ISUP simulator?

A separate program that *dials into* the hub over the real **ISUP 5.0** protocol
is not practical to build: ISUP is a proprietary, encrypted, MQTT-based protocol
(that's the whole reason the server side needs Hikvision's native SDK). So
instead of faking the wire protocol, we simulate the device **inside the hub** —
one more `DeviceAdapter`, exactly what the adapter architecture is for. Every
route above the adapter (auth, JSON API, enrolment workflows, cross-branch sync,
Postman, HRM) runs unchanged; only the bottom layer is in-memory instead of ISUP.

```
HRM / Postman ──HTTP──▶ hub API ──▶ DeviceAdapter
                                     ├─ DsK1T808Adapter ──ISUP──▶ real terminal
                                     └─ SimulatedDeviceAdapter (in-memory)   ← this
```

## Enable it

Simulation is **off by default** and gated by an env var (so it can never be
switched on by accident in production):

```
SIM_ENABLED=true
```

Set it in Dokploy / `docker-compose.yml` / `config.properties`, then redeploy.
All `/sim/*` routes still sit **behind the Bearer token** — they are not public.

With `SIM_ENABLED=false`, every `/sim/*` call returns `403`.

## Control endpoints

All require `Authorization: Bearer <token>` (like the rest of the API).

| Method | Route | What it does |
|---|---|---|
| POST | `/sim/devices` | Add a simulated device (comes online now) |
| POST | `/sim/devices/{id}/offline` | Mimic power/network loss → calls return 503 |
| POST | `/sim/devices/{id}/online` | Bring it back online |
| DELETE | `/sim/devices/{id}` | Remove it |

### Add a device

```bash
curl -X POST http://161.97.135.43:8090/sim/devices \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"deviceId":"SIM01","model":"DS-K1T808MFWX-B"}'
# → {"deviceId":"SIM01","model":"DS-K1T808MFWX-B","online":true,
#    "adapter":"SimulatedDeviceAdapter","simulated":true}
```

`model` is optional (defaults to `DS-K1T808MFWX-B`). It then appears in
`GET /devices` next to any real devices, flagged `"simulated": true`.

## It works with EVERY normal endpoint

Once added, `SIM01` is just a device ID. All the real routes work against it:

```bash
# create a person
curl -X POST http://.../devices/SIM01/persons -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"employeeNo":"E100","name":"Test User"}'

# capture + assign a fingerprint in one call (returns a fake template)
curl -X POST http://.../devices/SIM01/persons/E100/fingerprint/capture \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"fingerPrintID":1}'

# list the person's fingerprints
curl http://.../devices/SIM01/persons/E100/fingerprints -H "Authorization: Bearer $TOKEN"
```

- `captureFingerprint` returns a real Base64 blob (a fake template) with quality 80.
- `captureCard` returns an auto-incrementing card number.
- persons / cards / fingerprints persist in memory until the process restarts or
  you delete the device.

## Multi-device & cross-branch test (no hardware)

Add several simulated branches and test the cross-branch sync flow:

```bash
for b in SIM01 SIM02 SIM03 SIM04; do
  curl -sX POST http://.../sim/devices -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" -d "{\"deviceId\":\"$b\"}" ; echo
done

# enrol everywhere, capture on one branch, replicate to the rest
curl -X POST http://.../persons/broadcast -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"employeeNo":"E100","name":"Test User"}'
curl -X POST http://.../devices/SIM01/persons/E100/fingerprint/capture \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"fingerPrintID":1}'
curl -X POST http://.../fingerprints/sync -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"sourceDeviceId":"SIM01","employeeNo":"E100"}'
```

## Offline behaviour

```bash
curl -X POST http://.../sim/devices/SIM01/offline -H "Authorization: Bearer $TOKEN"
# now any operation on SIM01 → 503 "device not online"
curl -X POST http://.../sim/devices/SIM01/online  -H "Authorization: Bearer $TOKEN"
```

This lets you test how the HRM handles an offline branch without unplugging
anything.

## Postman

The collection has a **"Simulation (no hardware - SIM_ENABLED=true)"** folder with
all four control requests (add / offline / online / remove). After adding a
device, point `deviceId` at `SIM01` and every other folder works against it.

## Notes / limits

- Data is **in-memory** — it resets on restart. It is a test aid, not storage.
- No real fingerprint *matching* happens; templates are opaque blobs. The
  simulator verifies the **integration** (enrol → assign → list → sync → offline),
  which is what the HRM depends on.
- Keep `SIM_ENABLED=false` in production so no fake devices can be created.

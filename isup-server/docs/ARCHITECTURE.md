# Architecture

The hub is layered so the two things that change most often — **device model**
and **how a device is reached** — are isolated behind interfaces.

## The problem it solves

Branch terminals sit on private LANs behind NAT with dynamic IPs and no
on-premise PC. A cloud server cannot open a connection *to* them. ISUP inverts
this: the **device dials out** to the hub and holds the connection open; the hub
sends commands back down that tunnel using **ISAPI passthrough**
(`NET_ECMS_ISAPIPassThrough`).

## Layers

```
        HRM system
            │  HTTP / JSON
            ▼
┌───────────────────────────┐
│ api/ApiServer             │  routes, resolves device by ID
└───────────┬───────────────┘
            ▼
┌───────────────────────────┐
│ device/DeviceManager      │  registry: deviceId → ConnectedDevice
└───────────┬───────────────┘
            ▼
┌───────────────────────────┐
│ device/DeviceAdapter      │  WHAT operations (per model)
│  AbstractIsapiAdapter     │    standard ISAPI payloads
│   ├ DsK1T808Adapter       │
│   └ GenericIsapiAdapter   │
└───────────┬───────────────┘
            ▼
┌───────────────────────────┐
│ transport/Transport       │  HOW to reach the device
│  IsupTransport            │    ISAPI over the ISUP tunnel
└───────────┬───────────────┘
            ▼
┌───────────────────────────┐
│ server/IsupServer         │  CMS + alarm listeners (native SDK)
│ sdk/HCISUPCMS, HCISUPAlarm │  JNA bindings
└───────────────────────────┘
```

## The two axes of change

**1. Adapter = WHAT (per device model).**
`DeviceAdapter` declares every operation (persons, cards, fingerprints, doors).
`AbstractIsapiAdapter` implements them with standard ISAPI requests. A model that
behaves differently overrides only the methods that differ. Adding a model is one
class — see [ADD-MODEL.md](ADD-MODEL.md).

**2. Transport = HOW (reach mechanism).**
`Transport` abstracts sending an ISAPI request. `IsupTransport` does it through
the ISUP tunnel (bound to a device's login session). A future `DirectHttpTransport`
(for a device reachable on the LAN) would be a new class — no adapter changes.

Because these are separate, you can mix: DS-K1T808 over ISUP, another model over
ISUP, and (later) a LAN device over direct HTTP — all through the same API.

## Key components

| Component | Responsibility |
| --- | --- |
| `server/IsupServer` | Starts CMS (registration) + alarm (events) listeners. On registration, resolves the device's model and registers it in `DeviceManager`. |
| `device/DeviceManager` | Live registry of connected devices; builds the transport + adapter per device. |
| `device/ConnectedDevice` | One device: id, model, transport (session), adapter, online state. |
| `device/AdapterFactory` | Maps a model string to its adapter — the one place to register a model. |
| `device/FingerprintSyncService` | Cross-branch sync: read templates from one device, push to others. |
| `transport/IsupTransport` | `NET_ECMS_ISAPIPassThrough` wrapper bound to a login session. |
| `event/EventSink` | Normalised punch events → HRM webhook. |
| `api/ApiServer` | JDK HttpServer; routes to the target device's adapter. |

## Registration flow (ISUP 5.0)

The device handshake, handled in `IsupServer.RegisterCallback`:

```
device dials in
  → ENUM_DEV_AUTH (3)       hub returns ISUPKey  (must match device Encryption Key)
  → ENUM_DEV_SESSIONKEY (4) hub sets the session key (enables passthrough + alarms)
  → ENUM_DEV_DAS_REQ (5)    hub returns DAS address (this server, reachable IP)
  → ENUM_DEV_ON (0)         device online → DeviceManager.online(deviceId, loginId)
```

Miss any step and the device loops without coming online. ISUP 5.0 also requires
the crypto/SSL libraries registered before `NET_ECMS_Init` (that is why 4.0 works
without them but 5.0 does not).

## Command flow

```
HRM: POST /devices/HRM02/persons {employeeNo,name}
  → ApiServer resolves HRM02 in DeviceManager
  → dev.adapter.upsertPerson(person)
  → AbstractIsapiAdapter builds ISAPI JSON
  → IsupTransport.post("/ISAPI/AccessControl/UserInfo/Record", json)
  → NET_ECMS_ISAPIPassThrough(loginId, ...)   → device executes, replies
  → reply relayed back to the HRM
```

## Event flow

```
punch at device
  → device pushes over the ISUP alarm channel
  → IsupServer.AlarmCallback → AccessEvent
  → EventSink → HRM webhook (HrmEventUrl)
```

(Field extraction from the alarm struct is plumbed; map it against live events.
Meanwhile punches can also be taken via the device's HTTP webhook.)

## Why native (JNA) and not pure HTTP

ISUP is a proprietary binary protocol; there is no pure-HTTP way for a server to
terminate a NAT'd device's outbound tunnel. The Hikvision ISUP SDK (C `.dll`/`.so`)
does that, and JNA lets Java call it. The `sdk/` classes are the SDK's own JNA
bindings; everything above `transport/` is plain Java.

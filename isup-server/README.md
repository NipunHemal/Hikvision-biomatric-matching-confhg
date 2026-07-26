# Biometric Device Hub (Java / ISUP)

A central hub that controls Hikvision access terminals sitting behind NAT with
**no on-premise PC**. Branch terminals dial **out** over ISUP 5.0; the hub sends
person / fingerprint / card / door commands back down via ISAPI passthrough, and
exposes a clean HTTP/JSON API to the HRM system.

```
   Branches (no PC, dynamic IP)                 Server
┌─────────────────────────┐          ┌──────────────────────────────┐
│ DS-K1T808 (HRM01) ─ISUP─┼─────────►│  Hub (this app)              │
│ DS-K1T808 (HRM02) ─ISUP─┼─────────►│   CMS + alarm listeners      │
│ DS-K1T808 (HRM03) ─ISUP─┼─────────►│   DeviceManager (adapters)   │
│ DS-K1T808 (HRM04) ─ISUP─┼─────────►│         ▲ HTTP/JSON          │
└─────────────────────────┘          │         │                    │
                                      │   HRM system                 │
                                      └──────────────────────────────┘
```

## Documentation

| Doc | Covers |
| --- | --- |
| **README.md** (this file) | Overview, quick start |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | How it is built, adapter/transport design, data flow |
| [docs/DEVICE-SETUP.md](docs/DEVICE-SETUP.md) | Add & configure a device (multi-device) |
| [docs/ADD-MODEL.md](docs/ADD-MODEL.md) | Support a new device **model** (adapter) |
| [docs/API.md](docs/API.md) | Full HTTP API reference |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | Docker deployment to a server |

## Quick start (local dev — Windows)

```bat
build.bat
run.bat        REM starts CMS + alarm + HTTP API
```

Requires JDK 17+ and the ISUP SDK libraries in `lib/` (Windows `.dll` for local
dev; Linux `.so` for Docker — see [DEPLOYMENT.md](docs/DEPLOYMENT.md)).

Then configure a device to dial in ([DEVICE-SETUP.md](docs/DEVICE-SETUP.md)) and:

```bash
curl http://localhost:8090/devices          # ["HRM01", ...]
```

## What it does

- **Register / manage employees** on any device from the HRM (person, card, fingerprint)
- **Cross-branch fingerprint sync** — enroll once, works at every branch
- **Door control** and **device status**
- **Punch events** forwarded to the HRM
- **Multiple device models** via a pluggable adapter architecture

## Project layout

```
src/main/java/com/hrm/isup/
├── App.java  Config.java
├── api/          HTTP/JSON API (ApiServer)
├── device/       DeviceManager, adapters, AdapterFactory, FingerprintSyncService
├── transport/    Transport + IsupTransport (ISAPI over ISUP)
├── server/       IsupServer (CMS + alarm listeners)
├── event/        EventSink (events → HRM)
├── model/        Person, Fingerprint, AccessEvent, Result
└── sdk/          HCISUPCMS, HCISUPAlarm (JNA bindings)
```

## Status

- ✅ Compiles (JDK 21 + bundled JNA); boots (CMS + alarm + HTTP).
- ✅ ISUP 5.0 registration + ISAPI passthrough verified against a real device.
- ⏳ Alarm-event field extraction is plumbed; map against live events (or take
  punches via the device HTTP webhook meanwhile).
- ⚠️ Docker/Linux needs the Linux `.so` ISUP SDK — see [DEPLOYMENT.md](docs/DEPLOYMENT.md).

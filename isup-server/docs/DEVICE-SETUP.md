# Adding & configuring devices

Devices **dial in** to the hub, so there is no per-device configuration on the
server. The hub listens; any device with the right server address + key + a
unique Device ID registers automatically.

## Server config is shared across all devices

`config.properties` — the same values apply to every device:

```properties
CmsServerPort=7660              # devices dial this port
ISUPKey=Hrm12345Key             # ALL devices use this key (8–16 chars)
AlarmServerIP=<server LAN/public IP>   # NOT 0.0.0.0 / 127.0.0.1
DasServerIP=<server LAN/public IP>     # same reachable IP
DasServerPort=7660
HttpApiPort=8090
```

> `AlarmServerIP` and `DasServerIP` must be the address the **devices** can reach
> the server at. On a LAN that is the server's LAN IP; behind Docker/NAT it is the
> host's published IP. Never `0.0.0.0` or `127.0.0.1` — the device dials it.

## Per device: only the Device ID differs

On each terminal: **Configuration → Network → Device Access → ISUP**

| Field | Value |
| --- | --- |
| Enable | ON |
| Protocol Version | **ISUP5.0** |
| Server IP Address | the hub server's IP |
| Port | `7660` (= `CmsServerPort`) |
| **Device ID** | **unique per device** — `HRM01`, `HRM02`, `HRM03`, `HRM04` |
| Encryption Key | `Hrm12345Key` (= `ISUPKey`, exact match) |

Save. The device dials in and appears in the hub:

```bash
curl http://<hub>:8090/devices
# ["HRM01","HRM02","HRM03","HRM04"]
```

## A four-branch example

| Branch | Device ID | Server IP | Port | Key |
| --- | --- | --- | --- | --- |
| Main | HRM01 | 203.0.113.10 | 7660 | Hrm12345Key |
| Rear | HRM02 | 203.0.113.10 | 7660 | Hrm12345Key |
| Warehouse | HRM03 | 203.0.113.10 | 7660 | Hrm12345Key |
| Office | HRM04 | 203.0.113.10 | 7660 | Hrm12345Key |

All four dial the same server; the hub tells them apart by Device ID. Commands
route by that ID:

```bash
curl -X POST http://<hub>:8090/devices/HRM03/persons \
  -H "Content-Type: application/json" -d '{"employeeNo":"E123","name":"Kamal"}'
```

## Adding another branch later

1. Set the new terminal's ISUP config as above with the next Device ID (`HRM05`).
2. Save. It registers automatically — **no server change, no restart.**

## The Device ID is your routing key

The HRM should store, per branch, which `deviceId` it maps to, so it can target
the right terminal (or use `POST /persons/broadcast` / `POST /fingerprints/sync`
to hit all of them). Keep Device IDs stable — they identify the device everywhere.

## Firewall

The server must accept inbound on the ISUP + alarm + API ports:

```
7660/tcp   ISUP registration (devices → hub)
7663/tcp   alarm events (devices → hub)
7662/udp   alarm events (devices → hub)
8090/tcp   HTTP API (HRM → hub)
```

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| Device stays **Offline** | Wrong server IP/port, or `ISUPKey` ≠ device Encryption Key |
| Registers then loops, never online | DAS address unreachable — set `DasServerIP` to a reachable IP |
| `EHOME50_EHOMEKEY_ERROR` in `EHomeSDKLog` | Key mismatch — re-enter the device Encryption Key exactly |
| Only ISUP 4.0 works, 5.0 doesn't | crypto/SSL libs missing (handled in `IsupServer`; ensure lib/ has them) |
| Command returns `503` | That device is not currently registered — check `GET /devices` |

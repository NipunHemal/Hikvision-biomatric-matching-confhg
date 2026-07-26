# Deployment (Docker)

Deploy the hub to a server as a Docker container. Branch terminals dial in over
ISUP; the HRM calls the HTTP API.

## ⚠️ The one hard requirement: Linux ISUP libraries

The ISUP SDK is **native**. The `lib/` used for local Windows dev contains
Windows `.dll` files — **these do not run in a Linux container.** Before building
the image, replace `lib/` with the **Linux build** of the ISUP SDK:

```
lib/
├── libHCISUPCMS.so      libHCISUPAlarm.so   libHCISUPSS.so   libHCISUPStream.so
├── libcrypto.so         libssl.so           (+ HCAapSDKCom .so components)
├── jna.jar              examples.jar        gson-2.8.9.jar
```

These come from the **same Hikvision ISUP SDK package, Linux build**. The Java
code is already cross-platform: `IsupServer` selects `libcrypto.so`/`libssl.so`
on Linux, and `Native.loadLibrary("HCISUPCMS")` resolves `libHCISUPCMS.so`.

> Without the Linux `.so` files the container builds but fails at startup when it
> tries to load the native SDK.

## Build & run with docker-compose

```bash
cd isup-server
# 1. put the Linux .so ISUP libs in lib/  (see above)
# 2. edit config.properties — set AlarmServerIP / DasServerIP to the SERVER's
#    public/reachable IP (what the devices dial), not a container/internal IP
docker compose up -d --build
docker compose logs -f
```

Expected logs:
```
[isup] CMS listening on 0.0.0.0:7660
[isup] alarm listening on <AlarmServerIP>:7663
[api] HTTP API on http://0.0.0.0:8090
```

## Or plain Docker

```bash
docker build -t biometric-hub .
docker run -d --name biometric-hub \
  -p 7660:7660 -p 7663:7663 -p 7662:7662/udp -p 8090:8090 \
  -v "$PWD/config.properties:/app/config.properties:ro" \
  biometric-hub
```

## Ports (publish all)

| Port | Proto | Direction | Purpose |
| --- | --- | --- | --- |
| 7660 | tcp | devices → hub | ISUP registration |
| 7663 | tcp | devices → hub | alarm/punch events |
| 7662 | udp | devices → hub | alarm/punch events |
| 8090 | tcp | HRM → hub | HTTP API |

Open these inbound on the server firewall / cloud security group.

## Configuration in containers — the IP gotcha

Inside a container the app binds `0.0.0.0`, which is fine for listening. But the
values it hands to devices — `AlarmServerIP`, `DasServerIP` — must be the address
the **device** reaches the server at:

- Cloud server with a public IP → that public IP (or domain resolved to it).
- Behind a load balancer / NAT → the externally reachable IP, with the four ports
  forwarded to the container host.

Set them in `config.properties` (mounted read-only) and restart:
```bash
docker compose restart
```

## TLS / security notes

- ISUP 5.0 is already encrypted (device ↔ hub). Keep `ISUPKey` secret and unique.
- The HTTP API (8090) has no auth built in — do **not** expose it to the public
  internet. Keep it on a private network, or put it behind a reverse proxy that
  adds authentication (and TLS) before the HRM.
- Biometric templates cross this API; terminate TLS at the proxy in production.

## Health & logs

```bash
curl http://<server>:8090/health          # {"ok":true}
curl http://<server>:8090/devices         # registered devices
docker compose logs -f                     # app + registration logs
```
SDK-level logs are written to `/app/EHomeSDKLog` (mount it to persist).

## Updating

```bash
git pull
docker compose up -d --build     # rebuild + restart; devices re-register automatically
```

## Java-only (no Docker) alternative

On a Linux host with JDK 17+ and the Linux `.so` libs in `lib/`:
```bash
find src/main/java -name '*.java' > sources.txt
javac -encoding UTF-8 -cp "lib/*" -d out @sources.txt
LD_LIBRARY_PATH=lib java -Djna.library.path=lib -cp "out:lib/*:src/main/resources" com.hrm.isup.App
```
Run it under systemd for auto-restart.

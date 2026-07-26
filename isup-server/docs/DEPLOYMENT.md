# Deployment

Deploy the hub to a server. Branch terminals dial in over ISUP; the HRM calls
the HTTP API. Works with Docker directly or a git-based PaaS like **Dokploy**.

---

## Deploying with Dokploy (git-based UI)

Dokploy builds from your **git repository**, not your local disk. Two things
follow from that:

### 1. The native libs must be committed (git-tracked)

Local dev uses `lib/` (Windows `.dll`) which is **gitignored** — Dokploy never
sees it. For deployment the libs live in **`lib-linux/`** (git-tracked), and the
Dockerfile copies from there.

`lib-linux/` must contain the **Linux `.so`** ISUP SDK before you push:

```
lib-linux/
├── libHCISUPCMS.so  libHCISUPAlarm.so  libHCISUPSS.so  libHCISUPStream.so
├── libcrypto.so     libssl.so          (+ HCAapSDKCom components)
└── jna.jar  examples.jar  gson-2.8.9.jar   (already committed — cross-platform)
```

Get the Linux `.so` from the same Hikvision ISUP SDK package (Linux build), drop
them in `lib-linux/`, then commit and push:

```bash
git add isup-server/lib-linux
git commit -m "add Linux ISUP SDK libs for deploy"
git push
```

> Without the `.so` files the build's `COPY lib-linux ./lib` still succeeds but
> the container crashes at startup loading the native SDK. They are required.

### 2. Dokploy app settings

In the Dokploy UI, create an **Application** from your git repo and set:

| Setting | Value |
| --- | --- |
| Build Type | **Dockerfile** |
| Base Directory / Build Path | **`isup-server`** (the app is in a subfolder) |
| Dockerfile Path | `Dockerfile` (relative to base directory) |
| Branch | your branch |

### 3. Ports — ISUP needs raw TCP/UDP, not just a domain

Dokploy's domain routing (Traefik) is for **HTTP** only. The ISUP + alarm ports
are raw TCP/UDP that **devices** connect to, so add them as **Port mappings** in
the Dokploy UI (Advanced → Ports), not as domains:

| Container port | Proto | For |
| --- | --- | --- |
| 7660 | TCP | ISUP registration (devices) |
| 7663 | TCP | alarm events (devices) |
| 7662 | UDP | alarm events (devices) |
| 8090 | TCP | HTTP API (HRM) — may instead be a Dokploy domain |

The HTTP API (8090) can be exposed via a Dokploy **domain** (Traefik + TLS) if
the HRM calls it over HTTPS; keep it private or behind auth (it has none built in).

### 4. Config — set via environment variables (Dokploy UI)

The app reads **environment variables first** (then `config.properties`, then
defaults), so configure everything from the Dokploy **Environment** tab — no file
edits needed. Set these:

| Env var | Example | Meaning |
| --- | --- | --- |
| `ISUP_KEY` | `Hrm12345Key` | Must match each device's Encryption Key (8–16 chars) |
| `ALARM_SERVER_IP` | `161.97.135.43` | Server's **public IP/domain** (devices dial it) |
| `DAS_SERVER_IP` | `161.97.135.43` | Same public IP — DAS redirect target |
| `CMS_SERVER_PORT` | `7660` | ISUP listen port (default) |
| `DAS_SERVER_PORT` | `7660` | default |
| `ALARM_SERVER_TCP_PORT` | `7663` | default |
| `ALARM_SERVER_UDP_PORT` | `7662` | default |
| `HTTP_API_PORT` | `8090` | default |
| `API_TOKENS` | `hub_xxx,hub_yyy` | HRM Bearer token(s), comma-separated. Empty = auth OFF (dev only) |
| `SIM_ENABLED` | `false` | Enable in-memory simulated devices for testing. Keep `false` in prod |
| `HRM_EVENT_URL` | `https://hrm.example.com/api/events` | Forward punch events (optional) |

`ALARM_SERVER_IP` and `DAS_SERVER_IP` **must be the server's public IP/domain** —
the address the *devices* reach it at. An internal/`127.0.0.1` value makes the DAS
redirect unreachable and devices loop without coming online.

> Env var names accept either exact-key (`ISUPKey`) or UPPER_SNAKE (`ISUP_KEY`).
> Use UPPER_SNAKE in Dokploy.

### Graceful startup — no crash without the native libs

If the Linux `.so` ISUP libraries are missing, the hub **does not crash**: it logs
a warning, the HTTP API stays up, and `GET /health` reports `"isupReady":false`.
Add the `.so` files and redeploy to enable device registration
(`"isupReady":true`). This keeps the Dokploy deployment healthy while you finish
provisioning the SDK.

### 5. Deploy

Push to git → Dokploy builds and runs it. Check the app logs in the UI for:

```
[isup] CMS listening on 0.0.0.0:7660
[isup] alarm listening on <AlarmServerIP>:7663
[api] HTTP API on http://0.0.0.0:8090
```

Then point a device at the server (see [DEVICE-SETUP.md](DEVICE-SETUP.md)) and
open the app's HTTP API — `GET /devices` should list it.

---

## Deploying with Docker directly

Branch terminals dial in over ISUP; the HRM calls the HTTP API.

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

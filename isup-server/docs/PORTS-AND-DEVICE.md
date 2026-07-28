# Ports, firewall & device settings (events / card-tap)

If a device **registers and works** but **card taps / punches never reach the hub**
(no `[alarm] callback` log), the problem is almost always the **events port
(7663/7662)** being blocked, or the device not being told to report events. This
is the checklist.

## Ports

| Port | Proto | Purpose | Blocked → symptom |
| --- | --- | --- | --- |
| 7660 | TCP | ISUP CMS — device **registration** + ISAPI passthrough | device can't connect at all |
| **7663** | **TCP** | **Alarm — card taps / punch events** | registers & passthrough OK, **but no events** |
| **7662** | **UDP** | Alarm (UDP fallback) | as above |
| 8090 | TCP | HTTP API (HRM calls this) | HRM can't call the hub |

The events you're missing travel on **7663 (and 7662)** — a different port from
registration (7660). That's why the device works but taps don't show.

## The 3 layers a port must pass (all required)

```
device ──▶ [1] cloud provider firewall ──▶ [2] host OS firewall ──▶ [3] docker publish ──▶ container
```

| Layer | Who opens it | How |
| --- | --- | --- |
| 3. Docker publish | **compose / Dockerfile** (already done) | `ports: 7663:7663`, `EXPOSE` |
| 2. Host OS firewall | **you, on the server** | `scripts/open-ports.sh` |
| 1. Cloud firewall | **you, in the provider panel** | Contabo/AWS SG web UI |

> **Docker + ufw gotcha:** Docker publishes ports by writing its own iptables
> rules, which usually **bypass `ufw`**. So a published port is often reachable
> even if `ufw` "denies" it — meaning the real blocker is frequently **layer 1
> (the cloud provider firewall)**, not ufw. Don't stop at ufw; check the panel.

## Step 1 — host firewall (on the server)

```bash
sudo bash isup-server/scripts/open-ports.sh
```
Opens 7660/7663/8090 TCP + 7662 UDP (ufw / firewalld / iptables), then shows what
is listening.

## Step 2 — confirm it's listening on the host

```bash
ss -tulpn | grep -E '7660|7663|7662|8090'
```
You should see `7663` (LISTEN) and `7662` (udp). If not, the **container isn't up**
or the **alarm listener didn't bind** — check the startup log for
`[isup] alarm listening on ...:7663`.

## Step 3 — test reachability FROM OUTSIDE (run on your laptop)

```bash
nc -vz 161.97.135.43 7660      # registration — should already succeed
nc -vz 161.97.135.43 7663      # events       — MUST succeed for taps to arrive
```

| Result | Meaning | Fix |
| --- | --- | --- |
| 7660 ✅, 7663 ✅ | ports open — problem is the **device**, go to Step 4 | — |
| 7660 ✅, 7663 ❌ | **cloud provider firewall** blocks 7663 | open 7663/tcp + 7662/udp in the provider panel |
| both ❌ | host/provider firewall or container down | Step 1 + check container |

## Step 4 — device settings (DS-K1T808 over ISUP)

Over ISUP the hub **pushes the alarm-server address to the device during
registration** (`AlarmServerIP:7663`), so you normally don't set it on the device.
But verify on the terminal (or via the web UI at the device's LAN IP):

1. **Platform / Network access mode = ISUP (EHome)**, not Hik-Connect.
   - Server IP: `161.97.135.43`  ·  Port: `7660`
   - Device ID: e.g. `HRM02`  ·  Encryption key: matches `ISUP_KEY`
2. **Event upload enabled** — the device must be allowed to upload access events
   to the platform. On DS-K devices this is usually on by default once ISUP is
   registered; if there's an "Event/Alarm upload" or "Notify surveillance center"
   / linkage toggle, enable it.
3. **Correct device time** — events with a wildly wrong timestamp can be dropped.
   Set NTP or the correct time zone.
4. After changing settings, let the device **re-register** (reboot it if needed).

## Step 5 — verify events now arrive

Tap a card. Watch the hub log:

- `[alarm] callback fired, cmd=... xmlLen=...` → **events are arriving** ✅
  (send me a `[event] raw from ...` line so card/employee fields get parsed).
- still nothing → recheck Step 3 (7663 reachability) and Step 4.2 (upload enabled).

## Why the fix isn't "in" docker-compose

`docker-compose` `ports:` and `Dockerfile` `EXPOSE` only cover **layer 3**
(container → host). They **cannot** open the host OS firewall (layer 2) or the
cloud provider firewall (layer 1) — those need a command on the server and a
change in the provider panel, respectively. That's what Steps 1 and 3 are for.

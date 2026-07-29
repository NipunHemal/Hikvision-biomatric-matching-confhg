# MCP server — drive the hub from your AI tool

## What is MCP?

**MCP (Model Context Protocol)** is an open standard that lets an AI assistant
(Claude Desktop, Cursor, Claude Code, Windsurf, …) call external **tools**. You
register a small "MCP server"; the AI then sees its tools and can invoke them
during a conversation.

This project ships an MCP server, **`biometric-hub-mcp`**, that exposes the hub's
**simulator** as tools. So a developer can just *say* what they want —
"add a device, set a webhook, fire a fingerprint punch" — and the AI does it by
calling the hub for them. No curl, no Postman.

## What it does

It is a thin **bridge**: every MCP tool call becomes an authenticated HTTP request
to the hub's REST API. It holds no logic of its own — the hub does the work.

```
┌────────────┐  stdio (MCP)   ┌───────────────────┐  HTTP + Bearer  ┌──────────────┐
│  AI tool   │ ─────────────▶ │ biometric-hub-mcp │ ──────────────▶ │  Hub REST API │
│ (Claude…)  │ ◀───────────── │  (this server)    │ ◀────────────── │  + simulator  │
└────────────┘   tool result  └───────────────────┘   JSON reply    └──────────────┘
```

With it, from a chat you can:
- add / power / remove **simulated devices**,
- assign the **webhook** events are sent to (global or per-device),
- seed **people, fingerprints, cards**,
- fire **fake punches** (fingerprint / card / PIN / face / exit-button),
- **match a fingerprint template** and punch the owner,
- generate **check-in/out attendance**,
- **list** generated events and devices.

Because fake punches travel the *same* path as real device events, your HRM
webhook cannot tell them apart — perfect for end-to-end testing without hardware.

## Prerequisites

- **Node.js 18+**
- A **running hub** with `SIM_ENABLED=true` and an API token (`API_TOKENS`).
  (Local `http://localhost:8090`, or the deployed `http://161.97.135.43:8090`.)

## Install

```bash
cd isup-server/mcp
npm install
```

## Connect it to your AI tool

Fill in your hub URL + token. `HUB_URL` and `HUB_TOKEN` are how the server reaches
the hub.

### Claude Desktop
Edit `claude_desktop_config.json`
(Windows `%APPDATA%\Claude\`, macOS `~/Library/Application Support/Claude/`):
```json
{
  "mcpServers": {
    "biometric-sim": {
      "command": "node",
      "args": ["D:/Temp/biomatric_matching/isup-server/mcp/index.js"],
      "env": {
        "HUB_URL": "http://161.97.135.43:8090",
        "HUB_TOKEN": "hub_tGCxvFTLuEG3SSHFWikXVnvxxCy1QdYW"
      }
    }
  }
}
```
Restart Claude Desktop → the tools appear (plug icon).

### Claude Code
```bash
claude mcp add biometric-sim \
  --env HUB_URL=http://161.97.135.43:8090 \
  --env HUB_TOKEN=hub_tGCxvFTLuEG3SSHFWikXVnvxxCy1QdYW \
  -- node D:/Temp/biomatric_matching/isup-server/mcp/index.js
```

### Cursor / Windsurf
Same shape in their `mcp.json` — `command: "node"`, `args: [".../mcp/index.js"]`,
and the `env` block.

### Google Antigravity
Antigravity uses the **standard MCP `mcpServers` format**, so the same block works.

1. Open **Settings** (gear icon) and find the **MCP** / **MCP Servers** section
   (in the Agent/Tools settings). Choose **Add custom MCP server** → **Edit as JSON**
   (or open the MCP config file it points to).
2. Paste — merging into any existing `mcpServers`:
   ```json
   {
     "mcpServers": {
       "biometric-sim": {
         "command": "node",
         "args": ["D:/Temp/biomatric_matching/isup-server/mcp/index.js"],
         "env": {
           "HUB_URL": "http://161.97.135.43:8090",
           "HUB_TOKEN": "hub_tGCxvFTLuEG3SSHFWikXVnvxxCy1QdYW"
         }
       }
     }
   }
   ```
3. **Save**, then **refresh/reload the MCP servers** (or restart Antigravity). The
   `biometric-sim` tools appear in the Agent's tool list — enable them if prompted.

Notes for Antigravity:
- Use an **absolute path** in `args` (forward slashes work on Windows too).
- If `node` isn't found, put the full path to node in `command`
  (e.g. `C:/Program Files/nodejs/node.exe`), or run `npm install` in
  `isup-server/mcp` first so dependencies exist.
- The Agent must have tool use enabled; approve the tools when it first calls them.

### Environment variables

| Var | Default | Meaning |
| --- | --- | --- |
| `HUB_URL` | `http://localhost:8090` | Hub base URL |
| `HUB_TOKEN` | *(none)* | Bearer token; required unless the hub has auth off |

## The tools (16)

| Tool | What it does |
| --- | --- |
| `hub_health` | Check the hub is reachable / ISUP-ready |
| `list_devices` | List devices (real + simulated) |
| `sim_add_device` | Add an in-memory device (`deviceId`, `model?`) |
| `sim_device_power` | Bring a sim device online / offline |
| `sim_remove_device` | Remove a sim device |
| `sim_set_webhook` | Assign the **global** webhook (empty = clear) |
| `sim_get_webhook` | Show the global webhook |
| `sim_set_device_webhook` | Assign a **per-device** webhook (overrides global) |
| `sim_get_device_webhook` | Show a device's effective webhook |
| `seed_person` | Create a person (`employeeNo`, `name`) |
| `enroll_fingerprint` | Capture + assign a fake fingerprint |
| `assign_card` | Assign a card number to a person |
| `sim_punch` | Fire a fake punch (fingerprint/card/pin/face/button), optional `time` |
| `sim_punch_fingerprint_match` | Match a template → punch the owner if enrolled |
| `sim_attendance` | Check-in + check-out punch per employee |
| `sim_list_events` | List generated fake events |

## How to use it — a walkthrough

Once connected, talk to your AI naturally. It picks the right tools.

> **You:** Check the hub is up, then add a simulated device `SIM01`.

> **AI:** *(calls `hub_health`, then `sim_add_device`)* Hub is up and ISUP-ready.
> SIM01 is online (SimulatedDeviceAdapter).

> **You:** Send SIM01's events to `https://webhook.site/abc123`.

> **AI:** *(calls `sim_set_device_webhook`)* Done — SIM01 now posts to that URL.

> **You:** Create employee E100 "Kamal Perera", enroll fingerprint 1, then fire a
> fingerprint punch for him.

> **AI:** *(calls `seed_person`, `enroll_fingerprint`, `sim_punch`)* Created E100,
> enrolled finger 1, and fired a `fingerprintAuthSuccess` (minor 113). Your
> webhook just received the event.

> **You:** Generate today's attendance for E100 and E101.

> **AI:** *(calls `sim_attendance`)* Emitted check-in (09:00) and check-out (17:30)
> for both — 4 events sent to the webhook.

### More example prompts
- "List all devices and tell me which are simulated."
- "Take SIM01 offline, then bring it back online."
- "Assign card 3820181185 to E100 and fire a card punch."
- "Grab E100's enrolled template and match-punch it on SIM01."
- "Show the last 10 events on SIM01."

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| Tools don't appear | Restart the AI tool after editing its config; check the `args` path is absolute and correct |
| `Error: HTTP 401` | `HUB_TOKEN` missing/wrong — must match one of the hub's `API_TOKENS` |
| `Error: HTTP 403` | The hub has `SIM_ENABLED=false` — enable it and redeploy |
| `Error: HTTP 404 no online simulated device` | Add the device first (`sim_add_device`) |
| `fetch failed` / timeout | `HUB_URL` unreachable — check the hub is running and the URL/port |

## Notes

- The server logs only to **stderr** (stdout is the MCP channel).
- It exposes the **simulator**; it does not need the native ISUP libraries.
- Reference (install/config only): [`../mcp/README.md`](../mcp/README.md).
  Simulator details: [`SIMULATOR.md`](SIMULATOR.md). API: [`API.md`](API.md).

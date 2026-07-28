# Biometric Hub — MCP server

Drive the hub's **simulator** from any MCP-capable AI tool (Claude Desktop, Cursor,
Claude Code, Windsurf, …). Each tool call becomes an authenticated HTTP request to
the hub, so a developer can add devices, assign a webhook, and fire fake punch
events by just asking the AI.

```
AI tool ──stdio(MCP)──▶ biometric-hub-mcp ──HTTP(Bearer)──▶ Hub REST API ──▶ simulator
```

## Prerequisites

- Node.js 18+
- A running hub with **`SIM_ENABLED=true`** and an API token (`API_TOKENS`).

## Install

```bash
cd isup-server/mcp
npm install
```

## Configure your AI tool

Add this to the tool's MCP config, filling in your hub URL + token.

**Claude Desktop** — `claude_desktop_config.json`
(`%APPDATA%\Claude\` on Windows, `~/Library/Application Support/Claude/` on macOS):

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

**Claude Code** — `claude mcp add`:
```bash
claude mcp add biometric-sim \
  --env HUB_URL=http://161.97.135.43:8090 \
  --env HUB_TOKEN=hub_tGCxvFTLuEG3SSHFWikXVnvxxCy1QdYW \
  -- node D:/Temp/biomatric_matching/isup-server/mcp/index.js
```

**Cursor / Windsurf** — same shape in their `mcp.json` (`command` + `args` + `env`).

Restart the tool; the `biometric-sim` tools appear.

## Environment

| Var | Default | Meaning |
| --- | --- | --- |
| `HUB_URL` | `http://localhost:8090` | Hub base URL |
| `HUB_TOKEN` | *(none)* | Bearer token; required unless the hub has auth off |

## Tools

| Tool | What it does |
| --- | --- |
| `hub_health` | Check the hub is reachable / ISUP-ready |
| `list_devices` | List devices (real + simulated) |
| `sim_add_device` | Add an in-memory device (`deviceId`, `model?`) |
| `sim_device_power` | Bring a sim device online/offline |
| `sim_remove_device` | Remove a sim device |
| `sim_set_webhook` | Assign the GLOBAL webhook events POST to (empty = clear) |
| `sim_get_webhook` | Show the global webhook target |
| `sim_set_device_webhook` | Assign a PER-DEVICE webhook (overrides global) |
| `sim_get_device_webhook` | Show a device's effective webhook |
| `seed_person` | Create a person (`employeeNo`, `name`) |
| `enroll_fingerprint` | Capture + assign a fake fingerprint |
| `assign_card` | Assign a card number to a person |
| `sim_punch` | Fire a fake punch (fingerprint/card/pin/face/button), optional `time` |
| `sim_punch_fingerprint_match` | Match a template → punch the owner if enrolled |
| `sim_attendance` | Check-in + check-out punch per employee |
| `sim_list_events` | List generated fake events |

## Example prompts (once configured)

- "Add a simulated device SIM01 and set the webhook to https://webhook.site/abc."
- "Create employee E100 named Kamal, enroll fingerprint 1, then fire a fingerprint punch."
- "Generate today's attendance for E100 and E101, check-in 09:00, check-out 17:30."
- "List the last 10 events on SIM01."

## Notes

- The `sim_*` tools require `SIM_ENABLED=true` on the hub; otherwise they return 403.
- Fake punches travel the **same** path as real device events, so the HRM webhook
  cannot tell them apart. See [`../docs/SIMULATOR.md`](../docs/SIMULATOR.md).
- stdout is the MCP channel — the server logs only to stderr.

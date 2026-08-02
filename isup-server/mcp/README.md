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

## Tools (42) — full API coverage

Covers every request in the Postman collection.

**Hub / device**
| Tool | Does |
| --- | --- |
| `hub_health` | Hub reachable / ISUP-ready |
| `list_devices` | List devices (real + simulated) |
| `get_device` | Brief info for one device |
| `device_info` | Live `/ISAPI/System/deviceInfo` |
| `device_capabilities` | What the model supports |
| `control_door` | Open/close/hold a door |

**Persons**
| Tool | Does |
| --- | --- |
| `list_persons` | List enrolled persons |
| `seed_person` | Create/update a person (`pin`/`beginTime`/`endTime` optional) |
| `delete_person` | Remove from one device |
| `person_exists` | Exists on this device? |
| `person_details` | Full profile (person+fp+cards+pin) |
| `broadcast_person` | Create/update on ALL online devices |
| `broadcast_delete_person` | Remove from ALL online devices |
| `person_exists_across` | Exists across ALL devices (+ fp counts) |

**PIN / cards**
| Tool | Does |
| --- | --- |
| `set_pin` | Set a person's PIN |
| `assign_card` | Assign a known card number |
| `list_cards` | List a person's cards |
| `delete_card` | Remove one card |
| `capture_card` | Read a card at the reader (no assign) |
| `capture_assign_card` | Capture a card AND assign it |

**Fingerprints**
| Tool | Does |
| --- | --- |
| `list_fingerprints` | List a person's templates |
| `push_fingerprint` | Push a Base64 template |
| `enroll_fingerprint` | Capture + assign one finger |
| `enroll_fingerprint_bulk` | Capture + assign several fingers |
| `capture_fingerprint` | Scan a template only (no assign) |
| `delete_fingerprint` | Remove one finger |
| `delete_all_fingerprints` | Remove all fingers |
| `override_fingerprints` | Replace all fingers (one device) |
| `override_fingerprints_broadcast` | Replace fingers on many devices |
| `sync_fingerprints` | ⭐ Cross-branch: copy fingers source → others |
| `enroll_person` | Create person + optional capture+assign finger |

**Simulator** (needs `SIM_ENABLED=true`)
| Tool | Does |
| --- | --- |
| `sim_add_device` · `sim_device_power` · `sim_remove_device` | Device lifecycle |
| `sim_set_webhook` · `sim_get_webhook` | Global webhook target |
| `sim_set_device_webhook` · `sim_get_device_webhook` | Per-device webhook |
| `sim_punch` | Fire a fake punch (fingerprint/card/pin/face/button) |
| `sim_punch_fingerprint_match` | Match a template → punch the owner |
| `sim_attendance` | Check-in + check-out per employee |
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

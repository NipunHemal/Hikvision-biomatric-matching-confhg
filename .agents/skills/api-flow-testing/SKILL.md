---
name: api-flow-testing
description: Master skill for testing this repo's project flows end-to-end over HTTP — the Node hikvision-backend, its device simulator, and the Java biometric-hub. Drives real API calls with a bundled Python script and produces a pass/fail/error/skip report. Use whenever asked to test, verify, smoke-test, or regression-test the API, a punch/enrolment/device flow, or "does everything still work" after a change.
---

> This is the Antigravity twin of the Claude Code skill at
> [.claude/skills/api-flow-testing/SKILL.md](../../../.claude/skills/api-flow-testing/SKILL.md).
> Both files should stay in sync — if you change one, update the other. They
> share the same test runner script, so only the instructions are duplicated,
> not the logic.

# API flow testing — biomatric_matching

You are testing a physical access-control integration, not a typical REST CRUD
app. Two independent HTTP servers exist in this repo, and most "flows" are
**multi-step and asynchronous**: a punch on a device (or simulator) travels
through a webhook before it's queryable. A good report proves the whole path
worked, not just that one endpoint returned 200.

## The two API surfaces

| Surface | Entry point | Default URL | Docs |
| --- | --- | --- | --- |
| **Node hikvision-backend** | [src/server.js](../../../src/server.js) | `http://localhost:8080` | [README.md](../../../README.md) |
| ↳ its device simulator | [simulator/device.js](../../../simulator/device.js) | `http://127.0.0.1:8100` | [SIMULATOR.md](../../../SIMULATOR.md) |
| **Java biometric-hub** | `isup-server/` (Maven) | `http://localhost:8090` | [isup-server/docs/API.md](../../../isup-server/docs/API.md) |
| ↳ its in-memory sim (`SIM_ENABLED=true`) | same process | same URL | [isup-server/docs/SIMULATOR.md](../../../isup-server/docs/SIMULATOR.md) |

These are **separate projects that overlap in purpose** (both talk ISAPI/ISUP
to Hikvision terminals) — check with the user, or `git log`/recent activity,
if it's unclear which one is "the" current target before testing.

Postman collections document every route with example bodies — read them when
you need a payload shape the bundled script doesn't already cover:
- [postman/hikvision-backend.postman_collection.json](../../../postman/hikvision-backend.postman_collection.json)
- [isup-server/postman/biometric-hub.postman_collection.json](../../../isup-server/postman/biometric-hub.postman_collection.json)

## The golden rule: simulator first, real hardware never by accident

Every route tagged `[WRITES TO DEVICE]` or `[DESTRUCTIVE]` in the Postman
collections is relayed straight through to whatever physical terminal is
currently configured (`DEVICE_HOST` in `.env`, or the hub's registered ISUP
device). There is no separate "test device" flag in the code — safety comes
entirely from *what you point the backend at*.

Before running any flow that writes (persons, cards, fingerprints, doors, sim
device lifecycle):

1. Check what the backend is actually configured against — read `.env` for
   `DEVICE_HOST`/`DEVICE_PORT`, or ask the user.
2. If it's not clearly the simulator (`127.0.0.1:8100`) or a device the user
   has explicitly designated as disposable, **do not pass `--allow-writes` or
   `--allow-door`** — stick to the read-only + simulator-punch flows.
3. For the hub, never pass `--hub-sim` against a URL that looks like
   production (check `isup-server/postman/hub-production.postman_environment.json`
   for what "production" looks like — currently `161.97.135.43:8090`) unless
   the user explicitly confirms `SIM_ENABLED=true` there is intentional and
   safe to exercise.
4. `PUT /doors/:doorNo` actuates a physical door strike. Only include
   `--allow-door` when you're certain the target is the simulator.

When in doubt, ask. Running the read-only + simulator-punch flows needs no
confirmation — they touch no real hardware and clean up after themselves.

## Running the tests

The bundled runner lives in the Claude-side skill folder — it's shared, not
duplicated, so there's exactly one copy of the test logic:
[.claude/skills/api-flow-testing/scripts/run_api_flow_tests.py](../../../.claude/skills/api-flow-testing/scripts/run_api_flow_tests.py).
It's stdlib-only Python (`urllib`) — no `pip install` needed, works with any
`python3`. Invoke it with the repo-root-relative path shown below regardless
of which skill file (this one or the Claude one) pulled it in.

```bash
# 1. Make sure something is listening. Cheapest path — spin up the simulator + backend:
node simulator/device.js --port 8100 --webhook http://127.0.0.1:8080/hik/event &
DEVICE_HOST=127.0.0.1 DEVICE_PORT=8100 DEVICE_USER=admin DEVICE_PASS=simulator123 \
  BACKFILL_ON_START=false node src/server.js &

# 2. Read-only + simulator-punch flows (always safe):
python3 .claude/skills/api-flow-testing/scripts/run_api_flow_tests.py

# 3. Once you've confirmed DEVICE_HOST points at the simulator above, add write flows:
python3 .claude/skills/api-flow-testing/scripts/run_api_flow_tests.py --allow-writes --allow-door

# 4. Include the hub (only if it's running and reachable):
python3 .claude/skills/api-flow-testing/scripts/run_api_flow_tests.py \
  --hub-url http://localhost:8090 --hub-token <token> [--hub-sim]
```

Full flag reference: `python3 .claude/skills/api-flow-testing/scripts/run_api_flow_tests.py --help`.

If nothing is running yet and the user just wants "does the API still work,"
default to starting the simulator + backend yourself (command above) rather
than asking — it's non-destructive and self-contained. Only ask before
targeting a hub URL or a `DEVICE_HOST` you haven't verified.

### What it covers today

- `backend.health` — `GET /health`
- `backend.device_health` — `GET /device/health` (accepts online *or* offline;
  records which as an informational detail, not a failure)
- `backend.simulator_punch` — triggers a fingerprint punch on the simulator and
  polls `GET /events` until it lands, proving the whole webhook path works
- `backend.events` — `GET /events`, `GET /events/:id/raw`
- `backend.persons_crud` *(needs `--allow-writes`)* — create → verify listed →
  delete, with cleanup running even if an earlier assertion fails
- `backend.door_control` *(needs `--allow-writes --allow-door`)* — `PUT
  /doors/1 {cmd:"resume"}`, the least invasive door command
- `hub.health`, `hub.devices` *(needs `--hub-url`)*
- `hub.sim_lifecycle` *(needs `--hub-url --hub-token --hub-sim`)* — add a
  simulated device → enrol a person → capture a fingerprint → punch → verify
  the event → remove the device, cleanup in a `finally`

## Extending it for a new flow

When a route isn't covered yet — or a feature branch adds a new one — add a
`flow_*` function in
[the shared run_api_flow_tests.py](../../../.claude/skills/api-flow-testing/scripts/run_api_flow_tests.py)
rather than writing a one-off script (editing it here updates both skill
surfaces at once):

1. One function per logical flow (not per endpoint) — a flow is "punch a
   fingerprint and see it arrive," not "call POST /sim/punch."
2. Each step goes through `run_step(ctx, flow_name, step_name, fn)`. Use
   `require(condition, message)` for assertions — `AssertionError` → `FAIL`,
   anything else raised → `ERROR`, so failures (wrong behavior) stay visually
   distinct from errors (broken connection, unexpected exception).
3. If a flow can't run at all (missing config, feature disabled), `raise
   SkipFlow("why")` — it's recorded and shown, never silently dropped.
4. Writes get cleaned up in a `try/finally` so a failed assertion mid-flow
   never leaves test data behind (see `flow_backend_persons_crud` /
   `flow_hub_sim_lifecycle` for the pattern).
5. Register the new flow with `run_flow(ctx, "flow.name", lambda c: ...)` in
   `main()`, gated behind a new CLI flag if it writes or needs extra config.

## Reading and reporting results

The script prints a live PASS/FAIL/ERROR/SKIP line per step and, at the end,
writes both a Markdown and a JSON report into the shared
[reports/](../../../.claude/skills/api-flow-testing/reports/) directory
(its default output location, resolved relative to the script's own path —
no `--out-dir` needed unless you want it elsewhere). It exits non-zero if
anything failed or errored (skips don't affect the exit code).

After a run, don't just paste the raw table back at the user. Summarize like
an engineer handing off a test result:

- **Lead with the verdict** — "all N flows passed" or "M of N passed, X
  failed, Y need attention."
- **For each FAIL/ERROR**, name the flow, what was expected vs. observed, and
  point at the responsible code (e.g. "`POST /persons` returned 502 — see
  `src/server.js`'s `/persons` route calling `IsapiClient.upsertPerson`") —
  use the step's `detail` field and the route tables in README.md /
  isup-server/docs/API.md to trace status codes back to source.
- **For each SKIP**, say why (disabled by a flag, service not reachable) so
  the user knows it's an intentional gap, not a hole in coverage.
- **Don't silently drop scope** — if you only ran the read-only flows because
  writes weren't confirmed safe, say so explicitly and offer to re-run with
  `--allow-writes` once the user confirms the target.
- Link the generated Markdown report file so the user can open the full
  detail table themselves.

Old reports accumulate under `reports/` — they're timestamped and harmless to
leave, but feel free to clean up ones from your own run once you've reported
the results, so the directory doesn't silently grow across sessions.

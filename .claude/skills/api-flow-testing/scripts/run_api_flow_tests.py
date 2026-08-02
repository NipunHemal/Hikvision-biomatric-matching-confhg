#!/usr/bin/env python3
"""
API flow test runner for biomatric_matching.

Exercises the two HTTP API surfaces in this repo end-to-end and produces a
pass/fail/error report:

  1. Node "hikvision-backend"  (src/server.js)       default http://localhost:8080
     + its device simulator    (simulator/device.js) default http://127.0.0.1:8100
  2. Java "biometric-hub"      (isup-server)          default http://localhost:8090

No third-party packages required -- stdlib only (urllib), so it runs anywhere
python3 runs, no `pip install` needed.

Usage
-----
    python run_api_flow_tests.py                          # backend + simulator only, read-only + simulator flows
    python run_api_flow_tests.py --allow-writes            # also exercise persons/fingerprint CRUD on the backend's
                                                             #   *currently configured* device -- see WARNING below
    python run_api_flow_tests.py --hub-url http://localhost:8090 --hub-token hub_xxx
    python run_api_flow_tests.py --hub-url ... --hub-token ... --hub-sim   # also run the hub's in-memory SIM_ENABLED flow
    python run_api_flow_tests.py --base-url http://localhost:8080 --sim-url http://127.0.0.1:8100 --out-dir reports

WARNING on --allow-writes
--------------------------
The backend's /persons, /persons/:id/card, /persons/:id/fingerprint, and
/doors/:doorNo routes are relayed straight to whatever device is configured in
its .env (DEVICE_HOST/DEVICE_PORT). That may be the harmless local simulator,
or it may be a real access-control terminal. Only pass --allow-writes when you
have confirmed (ask the user, or check .env) that DEVICE_HOST points at the
simulator or a disposable test device -- never at production hardware.
--allow-door additionally exercises PUT /doors/:doorNo (physical door relay)
and requires --allow-writes to also be set.

Exit code is 0 only if every non-skipped step PASSed.
"""

import argparse
import json
import sys
import time
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path


# --------------------------------------------------------------------------
# tiny HTTP + assertion + reporting framework
# --------------------------------------------------------------------------

class SkipFlow(Exception):
    """Raise inside a flow to mark remaining steps as SKIP instead of FAIL/ERROR."""


@dataclass
class StepResult:
    flow: str
    name: str
    status: str  # PASS | FAIL | ERROR | SKIP
    detail: str = ""
    duration_ms: float = 0.0


@dataclass
class Ctx:
    results: list = field(default_factory=list)
    scratch: dict = field(default_factory=dict)  # cross-step values within a flow (ids, tokens, ...)


def http(method, url, json_body=None, headers=None, timeout=10, auth=None):
    """Minimal requests-like helper. Returns (status_code, parsed_body)."""
    data = None
    hdrs = dict(headers or {})
    if json_body is not None:
        data = json.dumps(json_body).encode("utf-8")
        hdrs.setdefault("Content-Type", "application/json")
    if auth:
        hdrs["Authorization"] = f"Bearer {auth}"
    req = urllib.request.Request(url, data=data, headers=hdrs, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read()
            return resp.status, _parse(body)
    except urllib.error.HTTPError as e:
        body = e.read()
        return e.code, _parse(body)
    except urllib.error.URLError as e:
        raise ConnectionError(f"{method} {url} -> {e.reason}") from e


def _parse(body: bytes):
    if not body:
        return None
    try:
        return json.loads(body)
    except json.JSONDecodeError:
        return body.decode("utf-8", errors="replace")


def run_step(ctx: Ctx, flow: str, name: str, fn):
    """fn may optionally return a short string; on PASS it's kept as an informational detail."""
    t0 = time.perf_counter()
    try:
        detail = fn() or ""
        ctx.results.append(StepResult(flow, name, "PASS", detail, (time.perf_counter() - t0) * 1000))
        print(f"  [PASS]  {name}" + (f" ({detail})" if detail else ""))
    except SkipFlow as e:
        ctx.results.append(StepResult(flow, name, "SKIP", str(e), (time.perf_counter() - t0) * 1000))
        print(f"  [SKIP]  {name}: {e}")
        raise
    except AssertionError as e:
        ctx.results.append(StepResult(flow, name, "FAIL", str(e), (time.perf_counter() - t0) * 1000))
        print(f"  [FAIL]  {name}: {e}")
    except Exception as e:
        ctx.results.append(StepResult(flow, name, "ERROR", f"{type(e).__name__}: {e}", (time.perf_counter() - t0) * 1000))
        print(f"  [ERROR] {name}: {e}")


def run_flow(ctx: Ctx, flow_name, fn):
    print(f"\n== {flow_name} ==")
    try:
        fn(ctx)
    except SkipFlow as e:
        # record the skip explicitly so the report never silently omits a whole flow
        ctx.results.append(StepResult(flow_name, "(entire flow)", "SKIP", str(e)))
        print(f"  [SKIP]  entire flow: {e}")
    except Exception as e:
        # a flow-level exception outside run_step (e.g. setup failure) -> record as ERROR
        ctx.results.append(StepResult(flow_name, "flow setup", "ERROR", f"{type(e).__name__}: {e}"))
        print(f"  [ERROR] flow setup: {e}")


def require(condition, message):
    if not condition:
        raise AssertionError(message)


# --------------------------------------------------------------------------
# Backend (Node hikvision-backend) flows -- src/server.js
# --------------------------------------------------------------------------

def flow_backend_health(ctx: Ctx, base_url):
    def step():
        status, body = http("GET", f"{base_url}/health")
        require(status == 200, f"expected 200, got {status}")
        require(isinstance(body, dict) and body.get("ok") is True, f"unexpected body: {body}")
    run_step(ctx, "backend.health", "GET /health", step)


def flow_backend_device_health(ctx: Ctx, base_url):
    def step():
        # 200 = device online, 503 = device offline -- both mean the route itself works.
        status, body = http("GET", f"{base_url}/device/health")
        require(status in (200, 503), f"unexpected status {status}: {body}")
        return f"device reports {'online' if status == 200 else 'offline'}"
    run_step(ctx, "backend.device_health", "GET /device/health", step)


def flow_backend_events(ctx: Ctx, base_url):
    def list_events():
        status, body = http("GET", f"{base_url}/events?limit=1")
        require(status == 200, f"expected 200, got {status}")
        require(isinstance(body, list), f"expected a list, got {type(body).__name__}")
        ctx.scratch["last_event_id"] = body[0]["id"] if body else None

    def raw_event():
        eid = ctx.scratch.get("last_event_id")
        if eid is None:
            raise SkipFlow("no events stored yet -- run the simulator-punch flow first")
        status, body = http("GET", f"{base_url}/events/{eid}/raw")
        require(status == 200, f"expected 200, got {status}")
        require(isinstance(body, dict), f"expected the raw device payload, got {body}")

    run_step(ctx, "backend.events", "GET /events?limit=1", list_events)
    run_step(ctx, "backend.events", "GET /events/:id/raw", raw_event)


def flow_simulator_punch_ingested(ctx: Ctx, base_url, sim_url):
    if not sim_url:
        raise SkipFlow("no --sim-url given")

    employee_no = f"T{uuid.uuid4().hex[:8]}"

    def status_ok():
        status, _ = http("GET", f"{sim_url}/sim/status")
        require(status == 200, f"expected 200, got {status}")

    def trigger_punch():
        status, body = http(
            "POST", f"{sim_url}/sim/punch",
            json_body={"employeeNo": employee_no, "method": "fingerprint", "success": True},
        )
        require(status == 200, f"expected 200, got {status}: {body}")

    def ingested_by_backend():
        # the simulator pushes to the backend's registered webhook async-ish; poll briefly.
        deadline = time.time() + 8
        found = None
        while time.time() < deadline and not found:
            status, body = http("GET", f"{base_url}/events?limit=10&employeeNo={employee_no}")
            require(status == 200, f"expected 200, got {status}")
            if body:
                found = body[0]
            else:
                time.sleep(0.5)
        require(found is not None, f"punch for {employee_no} never appeared in GET /events -- "
                                    "is the backend running with the simulator registered as its webhook target? "
                                    "(npm run register, or --webhook on the simulator)")
        require(found.get("verify_method") == "fingerprint", f"unexpected verify_method: {found}")
        require(found.get("success") in (1, True), f"expected success punch, got: {found}")

    run_step(ctx, "backend.simulator_punch", "GET /sim/status", status_ok)
    run_step(ctx, "backend.simulator_punch", "POST /sim/punch (fingerprint)", trigger_punch)
    run_step(ctx, "backend.simulator_punch", "punch lands in GET /events", ingested_by_backend)


def flow_backend_persons_crud(ctx: Ctx, base_url, allow_writes, allow_door):
    if not allow_writes:
        raise SkipFlow("writes to the device are disabled by default -- pass --allow-writes "
                        "only once you've confirmed DEVICE_HOST points at a safe test device")

    employee_no = f"T{uuid.uuid4().hex[:8]}"
    created = {"done": False}

    def create():
        status, body = http("POST", f"{base_url}/persons", json_body={"employeeNo": employee_no, "name": "Flow Test"})
        require(status == 200, f"expected 200, got {status}: {body}")
        created["done"] = True

    def list_contains():
        status, body = http("GET", f"{base_url}/persons")
        require(status == 200, f"expected 200, got {status}")
        ids = [p.get("employeeNo") for p in body] if isinstance(body, list) else []
        require(employee_no in ids, f"{employee_no} not found in GET /persons ({len(ids)} persons listed)")

    def delete():
        if not created["done"]:
            raise SkipFlow("create step never succeeded, nothing to clean up")
        status, body = http("DELETE", f"{base_url}/persons/{employee_no}")
        require(status == 200, f"expected 200, got {status}: {body}")

    try:
        run_step(ctx, "backend.persons_crud", "POST /persons (create)", create)
        run_step(ctx, "backend.persons_crud", "GET /persons (contains new person)", list_contains)
    finally:
        # always attempt cleanup so a failed assertion doesn't leave test data on the device
        run_step(ctx, "backend.persons_crud", "DELETE /persons/:id (cleanup)", delete)

    if allow_door:
        def door_resume():
            # 'resume' is the least invasive door command -- clears any hold state without
            # actuating the lock the way open/alwaysOpen/alwaysClose would.
            status, body = http("PUT", f"{base_url}/doors/1", json_body={"cmd": "resume"})
            require(status == 200, f"expected 200, got {status}: {body}")
        run_step(ctx, "backend.door_control", "PUT /doors/1 {cmd: resume}", door_resume)


# --------------------------------------------------------------------------
# Hub (Java isup-server "biometric-hub") flows
# --------------------------------------------------------------------------

def flow_hub_health(ctx: Ctx, hub_url):
    def step():
        status, body = http("GET", f"{hub_url}/health")
        require(status == 200, f"expected 200, got {status}")
        require(isinstance(body, dict) and body.get("ok") is True, f"unexpected body: {body}")
    run_step(ctx, "hub.health", "GET /health", step)


def flow_hub_devices(ctx: Ctx, hub_url, token):
    def step():
        status, body = http("GET", f"{hub_url}/devices", auth=token)
        require(status == 200, f"expected 200, got {status}: {body}")
        require(isinstance(body, list), f"expected a list, got {type(body).__name__}")
    run_step(ctx, "hub.devices", "GET /devices", step)


def flow_hub_sim_lifecycle(ctx: Ctx, hub_url, token, run_sim):
    if not run_sim:
        raise SkipFlow("pass --hub-sim to exercise this (requires SIM_ENABLED=true on the target hub)")

    device_id = f"SIMTEST{uuid.uuid4().hex[:6]}"
    employee_no = f"E{uuid.uuid4().hex[:6]}"
    added = {"done": False}

    def add_device():
        status, body = http("POST", f"{hub_url}/sim/devices", json_body={"deviceId": device_id}, auth=token)
        require(status == 200, f"expected 200, got {status}: {body} "
                                f"(403 usually means SIM_ENABLED=false on this hub)")
        added["done"] = True

    def create_person():
        status, body = http(
            "POST", f"{hub_url}/devices/{device_id}/persons",
            json_body={"employeeNo": employee_no, "name": "Flow Test"}, auth=token,
        )
        require(status == 200, f"expected 200, got {status}: {body}")

    def capture_fingerprint():
        status, body = http(
            "POST", f"{hub_url}/devices/{device_id}/persons/{employee_no}/fingerprint/capture",
            json_body={"fingerPrintID": 1}, auth=token,
        )
        require(status == 200, f"expected 200, got {status}: {body}")

    def punch_fingerprint():
        status, body = http(
            "POST", f"{hub_url}/sim/devices/{device_id}/punch/fingerprint",
            json_body={"employeeNo": employee_no, "fingerPrintID": 1}, auth=token,
        )
        require(status == 200, f"expected 200, got {status}: {body}")

    def events_contain_punch():
        status, body = http("GET", f"{hub_url}/sim/devices/{device_id}/events?limit=10", auth=token)
        require(status == 200, f"expected 200, got {status}: {body}")
        events = body if isinstance(body, list) else []
        require(any(e.get("employeeNo") == employee_no for e in events),
                f"punch for {employee_no} not found among {len(events)} simulated events")

    def remove_device():
        if not added["done"]:
            raise SkipFlow("device was never added, nothing to remove")
        status, body = http("DELETE", f"{hub_url}/sim/devices/{device_id}", auth=token)
        require(status == 200, f"expected 200, got {status}: {body}")

    try:
        run_step(ctx, "hub.sim_lifecycle", "POST /sim/devices (add)", add_device)
        run_step(ctx, "hub.sim_lifecycle", "POST /devices/:id/persons (create)", create_person)
        run_step(ctx, "hub.sim_lifecycle", "POST .../fingerprint/capture", capture_fingerprint)
        run_step(ctx, "hub.sim_lifecycle", "POST /sim/devices/:id/punch/fingerprint", punch_fingerprint)
        run_step(ctx, "hub.sim_lifecycle", "GET /sim/devices/:id/events (contains punch)", events_contain_punch)
    finally:
        run_step(ctx, "hub.sim_lifecycle", "DELETE /sim/devices/:id (cleanup)", remove_device)


# --------------------------------------------------------------------------
# reporting
# --------------------------------------------------------------------------

def build_report(ctx: Ctx, meta: dict) -> dict:
    counts = {"PASS": 0, "FAIL": 0, "ERROR": 0, "SKIP": 0}
    for r in ctx.results:
        counts[r.status] += 1
    return {
        "generated_at": meta["timestamp"],
        "target": meta["target"],
        "summary": counts,
        "total_steps": len(ctx.results),
        "all_passed": counts["FAIL"] == 0 and counts["ERROR"] == 0,
        "steps": [
            {
                "flow": r.flow,
                "step": r.name,
                "status": r.status,
                "detail": r.detail,
                "duration_ms": round(r.duration_ms, 1),
            }
            for r in ctx.results
        ],
    }


def render_markdown(report: dict) -> str:
    icon = {"PASS": "PASS", "FAIL": "FAIL", "ERROR": "ERROR", "SKIP": "SKIP"}
    lines = []
    lines.append(f"# API flow test report - {report['generated_at']}")
    lines.append("")
    t = report["target"]
    lines.append(f"- Backend: `{t['base_url']}`")
    lines.append(f"- Simulator: `{t['sim_url'] or '(skipped)'}`")
    lines.append(f"- Hub: `{t['hub_url'] or '(skipped)'}`")
    lines.append("")
    s = report["summary"]
    lines.append(f"**{s['PASS']} passed, {s['FAIL']} failed, {s['ERROR']} errored, {s['SKIP']} skipped** "
                  f"out of {report['total_steps']} steps.")
    lines.append("")
    lines.append("Overall: **" + ("SUCCESS" if report["all_passed"] else "ISSUES FOUND") + "**")
    lines.append("")
    lines.append("| Flow | Step | Status | Duration (ms) | Detail |")
    lines.append("| --- | --- | --- | ---: | --- |")
    for step in report["steps"]:
        detail = (step["detail"] or "").replace("|", "\\|").replace("\n", " ")
        lines.append(f"| {step['flow']} | {step['step']} | {icon[step['status']]} | "
                      f"{step['duration_ms']} | {detail} |")
    lines.append("")

    failures = [s for s in report["steps"] if s["status"] in ("FAIL", "ERROR")]
    if failures:
        lines.append("## Failures / errors")
        lines.append("")
        for s in failures:
            lines.append(f"- **[{s['status']}] {s['flow']} - {s['step']}**: {s['detail']}")
        lines.append("")

    skipped = [s for s in report["steps"] if s["status"] == "SKIP"]
    if skipped:
        lines.append("## Skipped")
        lines.append("")
        for s in skipped:
            lines.append(f"- {s['flow']} - {s['step']}: {s['detail']}")
        lines.append("")

    return "\n".join(lines)


# --------------------------------------------------------------------------
# main
# --------------------------------------------------------------------------

def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--base-url", default="http://localhost:8080", help="Node hikvision-backend base URL")
    p.add_argument("--sim-url", default="http://127.0.0.1:8100", help="device simulator URL (empty string to skip)")
    p.add_argument("--hub-url", default="", help="Java biometric-hub base URL (omit to skip hub flows)")
    p.add_argument("--hub-token", default="", help="hub Bearer token")
    p.add_argument("--hub-sim", action="store_true", help="also run the hub's SIM_ENABLED device lifecycle flow")
    p.add_argument("--allow-writes", action="store_true",
                    help="run backend persons/fingerprint CRUD flows (writes to whatever device the backend is "
                         "configured against -- confirm it's a simulator/test device first)")
    p.add_argument("--allow-door", action="store_true",
                    help="additionally exercise PUT /doors/:doorNo (requires --allow-writes)")
    p.add_argument("--out-dir", default=str(Path(__file__).resolve().parent.parent / "reports"),
                    help="directory to write the report files into")
    args = p.parse_args()

    if args.allow_door and not args.allow_writes:
        p.error("--allow-door requires --allow-writes")

    ctx = Ctx()
    timestamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H-%M-%SZ")

    print(f"API flow test run - {timestamp}")
    print(f"backend: {args.base_url}   simulator: {args.sim_url or '(skipped)'}   "
          f"hub: {args.hub_url or '(skipped)'}")
    if not args.allow_writes:
        print("(write flows disabled -- pass --allow-writes to include persons/fingerprint CRUD)")

    run_flow(ctx, "backend.health", lambda c: flow_backend_health(c, args.base_url))
    run_flow(ctx, "backend.device_health", lambda c: flow_backend_device_health(c, args.base_url))
    run_flow(ctx, "backend.simulator_punch", lambda c: flow_simulator_punch_ingested(c, args.base_url, args.sim_url))
    run_flow(ctx, "backend.events", lambda c: flow_backend_events(c, args.base_url))
    run_flow(ctx, "backend.persons_crud",
             lambda c: flow_backend_persons_crud(c, args.base_url, args.allow_writes, args.allow_door))

    if args.hub_url:
        run_flow(ctx, "hub.health", lambda c: flow_hub_health(c, args.hub_url))
        run_flow(ctx, "hub.devices", lambda c: flow_hub_devices(c, args.hub_url, args.hub_token))
        run_flow(ctx, "hub.sim_lifecycle",
                 lambda c: flow_hub_sim_lifecycle(c, args.hub_url, args.hub_token, args.hub_sim))

    meta = {
        "timestamp": timestamp,
        "target": {
            "base_url": args.base_url,
            "sim_url": args.sim_url,
            "hub_url": args.hub_url or None,
        },
    }
    report = build_report(ctx, meta)

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    json_path = out_dir / f"api-flow-report-{timestamp}.json"
    md_path = out_dir / f"api-flow-report-{timestamp}.md"
    json_path.write_text(json.dumps(report, indent=2), encoding="utf-8")
    md_path.write_text(render_markdown(report), encoding="utf-8")

    s = report["summary"]
    print(f"\n{s['PASS']} passed, {s['FAIL']} failed, {s['ERROR']} errored, {s['SKIP']} skipped "
          f"out of {report['total_steps']} steps.")
    print(f"Report written to:\n  {md_path}\n  {json_path}")

    sys.exit(0 if report["all_passed"] else 1)


if __name__ == "__main__":
    main()

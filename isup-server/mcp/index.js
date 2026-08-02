#!/usr/bin/env node
/**
 * Biometric Device Hub — MCP server.
 *
 * A thin stdio bridge: each MCP tool call is translated into an authenticated
 * HTTP request against the hub's REST API. Covers the FULL hub API — real-device
 * management (persons, cards, fingerprints, doors, cross-branch sync, employee
 * query/override, enrolment workflows) AND the in-memory simulator (devices,
 * webhooks, fake punches, attendance) — so an AI tool can drive everything the
 * Postman collection can.
 *
 * Config (env, set in your AI tool's MCP config):
 *   HUB_URL    base URL of the hub        (default http://localhost:8090)
 *   HUB_TOKEN  Bearer token (API_TOKENS)  (required unless the hub has auth off)
 *
 * The hub must have SIM_ENABLED=true for the sim_* tools to work.
 */
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

const BASE = (process.env.HUB_URL || "http://localhost:8090").replace(/\/+$/, "");
const TOKEN = process.env.HUB_TOKEN || "";

/** Authenticated call to the hub REST API. Throws on non-2xx with the body. */
async function hub(path, method = "GET", body) {
  const headers = { "Content-Type": "application/json" };
  if (TOKEN) headers["Authorization"] = `Bearer ${TOKEN}`;
  const res = await fetch(BASE + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  let data;
  try { data = text ? JSON.parse(text) : {}; } catch { data = { raw: text }; }
  if (!res.ok) {
    const e = new Error(`HTTP ${res.status} ${method} ${path} — ${text}`);
    e.status = res.status;
    throw e;
  }
  return data;
}

const ok = (d) => ({ content: [{ type: "text", text: typeof d === "string" ? d : JSON.stringify(d, null, 2) }] });
const fail = (e) => ({ content: [{ type: "text", text: `Error: ${e.message}` }], isError: true });

const server = new McpServer({ name: "biometric-hub-mcp", version: "1.0.0" });

/** Register a tool whose handler returns hub data (errors surfaced cleanly). */
function tool(name, description, shape, fn) {
  server.tool(name, description, shape, async (args) => {
    try { return ok(await fn(args ?? {})); } catch (e) { return fail(e); }
  });
}

// --- hub / devices ---
tool("hub_health", "Check the hub is reachable and ISUP-ready (no auth needed).", {},
  () => hub("/health"));

tool("list_devices", "List all devices (real + simulated) with online/adapter/simulated flags.", {},
  () => hub("/devices"));

// --- simulated device lifecycle (SIM_ENABLED=true) ---
tool("sim_add_device", "Add an in-memory simulated device.",
  { deviceId: z.string().describe("e.g. SIM01"), model: z.string().optional() },
  ({ deviceId, model }) => hub("/sim/devices", "POST", { deviceId, ...(model ? { model } : {}) }));

tool("sim_device_power", "Bring a simulated device online or offline (mimic power/network).",
  { deviceId: z.string(), online: z.boolean() },
  ({ deviceId, online }) => hub(`/sim/devices/${deviceId}/${online ? "online" : "offline"}`, "POST"));

tool("sim_remove_device", "Remove a simulated device.",
  { deviceId: z.string() },
  ({ deviceId }) => hub(`/sim/devices/${deviceId}`, "DELETE"));

// --- webhook target for events ---
tool("sim_set_webhook", "Assign the runtime webhook that punch events POST to (overrides HRM_EVENT_URL). Empty url clears it.",
  { url: z.string().describe("full URL, or empty string to clear") },
  ({ url }) => (url ? hub("/sim/webhook", "POST", { url }) : hub("/sim/webhook", "DELETE")));

tool("sim_get_webhook", "Show the current GLOBAL webhook target.", {},
  () => hub("/sim/webhook"));

tool("sim_set_device_webhook", "Assign a PER-DEVICE webhook (overrides the global one for this device). Empty url clears it.",
  { deviceId: z.string(), url: z.string().describe("full URL, or empty to clear") },
  ({ deviceId, url }) => (url
    ? hub(`/sim/devices/${deviceId}/webhook`, "POST", { url })
    : hub(`/sim/devices/${deviceId}/webhook`, "DELETE")));

tool("sim_get_device_webhook", "Show the effective webhook target for a device.",
  { deviceId: z.string() },
  ({ deviceId }) => hub(`/sim/devices/${deviceId}/webhook`));

// --- device (read + door) ---
tool("get_device", "Brief info for one device (id, model, online, adapter, simulated).",
  { deviceId: z.string() },
  ({ deviceId }) => hub(`/devices/${deviceId}`));

tool("device_info", "Live /ISAPI/System/deviceInfo over ISUP (relayed device reply).",
  { deviceId: z.string() },
  ({ deviceId }) => hub(`/devices/${deviceId}/info`));

tool("device_capabilities", "What the device model supports (persons/cards/fingerprint/face).",
  { deviceId: z.string() },
  ({ deviceId }) => hub(`/devices/${deviceId}/capabilities`));

tool("control_door", "Remote door control.",
  { deviceId: z.string(), doorNo: z.number().int().optional(),
    cmd: z.enum(["open", "close", "alwaysOpen", "alwaysClose", "resume"]).optional() },
  ({ deviceId, ...body }) => hub(`/devices/${deviceId}/door`, "POST", body));

// --- persons ---
tool("list_persons", "List enrolled persons on a device.",
  { deviceId: z.string() },
  ({ deviceId }) => hub(`/devices/${deviceId}/persons`));

tool("seed_person", "Create or update a person on a device.",
  { deviceId: z.string(), employeeNo: z.string(), name: z.string(),
    pin: z.string().optional(), beginTime: z.string().optional(), endTime: z.string().optional() },
  ({ deviceId, ...body }) => hub(`/devices/${deviceId}/persons`, "POST", body));

tool("delete_person", "Remove a person from one device.",
  { deviceId: z.string(), employeeNo: z.string() },
  ({ deviceId, employeeNo }) => hub(`/devices/${deviceId}/persons/${employeeNo}`, "DELETE"));

tool("person_exists", "Is this employee enrolled on this device?",
  { deviceId: z.string(), employeeNo: z.string() },
  ({ deviceId, employeeNo }) => hub(`/devices/${deviceId}/persons/${employeeNo}/exists`));

tool("person_details", "Full profile: person + fingerprints + cards + pin (one device).",
  { deviceId: z.string(), employeeNo: z.string() },
  ({ deviceId, employeeNo }) => hub(`/devices/${deviceId}/persons/${employeeNo}/details`));

tool("broadcast_person", "Create/update a person on EVERY online device at once.",
  { employeeNo: z.string(), name: z.string() },
  (body) => hub(`/persons/broadcast`, "POST", body));

tool("broadcast_delete_person", "Remove a person from EVERY online device.",
  { employeeNo: z.string() },
  ({ employeeNo }) => hub(`/persons/broadcast/${employeeNo}`, "DELETE"));

tool("person_exists_across", "Check an employee across ALL devices (which have them + fingerprint counts).",
  { employeeNo: z.string() },
  ({ employeeNo }) => hub(`/persons/${employeeNo}/exists`));

// --- PIN ---
tool("set_pin", "Set a person's access PIN / password.",
  { deviceId: z.string(), employeeNo: z.string(), pin: z.string() },
  ({ deviceId, employeeNo, pin }) => hub(`/devices/${deviceId}/persons/${employeeNo}/pin`, "POST", { pin }));

// --- cards ---
tool("assign_card", "Assign a known card number to a person.",
  { deviceId: z.string(), employeeNo: z.string(), cardNo: z.string(), cardType: z.string().optional() },
  ({ deviceId, employeeNo, ...body }) => hub(`/devices/${deviceId}/persons/${employeeNo}/card`, "POST", body));

tool("list_cards", "List a person's cards.",
  { deviceId: z.string(), employeeNo: z.string() },
  ({ deviceId, employeeNo }) => hub(`/devices/${deviceId}/persons/${employeeNo}/cards`));

tool("delete_card", "Remove one card from a person.",
  { deviceId: z.string(), employeeNo: z.string(), cardNo: z.string() },
  ({ deviceId, employeeNo, cardNo }) => hub(`/devices/${deviceId}/persons/${employeeNo}/cards/${cardNo}`, "DELETE"));

tool("capture_card", "Read a card at the reader (no assignment). Blocks until a card is tapped.",
  { deviceId: z.string() },
  ({ deviceId }) => hub(`/devices/${deviceId}/card/capture`, "POST", {}));

tool("capture_assign_card", "Capture a card at the reader AND assign it to a person.",
  { deviceId: z.string(), employeeNo: z.string(), cardType: z.string().optional() },
  ({ deviceId, employeeNo, ...body }) => hub(`/devices/${deviceId}/persons/${employeeNo}/card/capture`, "POST", body));

// --- fingerprints ---
tool("list_fingerprints", "List a person's fingerprints (with Base64 templates).",
  { deviceId: z.string(), employeeNo: z.string() },
  ({ deviceId, employeeNo }) => hub(`/devices/${deviceId}/persons/${employeeNo}/fingerprints`));

tool("push_fingerprint", "Push a single template to a person on the device.",
  { deviceId: z.string(), employeeNo: z.string(), fingerData: z.string().describe("Base64"),
    fingerPrintID: z.number().int().optional() },
  ({ deviceId, employeeNo, ...body }) => hub(`/devices/${deviceId}/persons/${employeeNo}/fingerprint`, "POST", body));

tool("enroll_fingerprint", "Capture + assign a fingerprint to a person (device scans; sim generates a fake template).",
  { deviceId: z.string(), employeeNo: z.string(), fingerPrintID: z.number().int().optional() },
  ({ deviceId, employeeNo, fingerPrintID }) =>
    hub(`/devices/${deviceId}/persons/${employeeNo}/fingerprint/capture`, "POST",
      fingerPrintID ? { fingerPrintID } : {}));

tool("enroll_fingerprint_bulk", "Capture + assign SEVERAL fingers to a person.",
  { deviceId: z.string(), employeeNo: z.string(),
    fingerPrintIDs: z.array(z.number().int()).optional(), count: z.number().int().optional() },
  ({ deviceId, employeeNo, ...body }) =>
    hub(`/devices/${deviceId}/persons/${employeeNo}/fingerprint/capture-bulk`, "POST", body));

tool("capture_fingerprint", "Scan a fingerprint at the terminal and return the template (no assignment).",
  { deviceId: z.string(), fingerNo: z.number().int().optional() },
  ({ deviceId, ...body }) => hub(`/devices/${deviceId}/fingerprint/capture`, "POST", body));

tool("delete_fingerprint", "Remove ONE finger from a person.",
  { deviceId: z.string(), employeeNo: z.string(), fingerPrintID: z.number().int() },
  ({ deviceId, employeeNo, fingerPrintID }) =>
    hub(`/devices/${deviceId}/persons/${employeeNo}/fingerprints/${fingerPrintID}`, "DELETE"));

tool("delete_all_fingerprints", "Remove ALL of a person's fingerprints on this device.",
  { deviceId: z.string(), employeeNo: z.string() },
  ({ deviceId, employeeNo }) => hub(`/devices/${deviceId}/persons/${employeeNo}/fingerprints`, "DELETE"));

tool("override_fingerprints", "Replace ALL of a person's fingerprints on this device (delete then insert).",
  { deviceId: z.string(), employeeNo: z.string(),
    fingerprints: z.array(z.object({
      fingerData: z.string(), fingerPrintID: z.number().int().optional(), fingerType: z.string().optional() })) },
  ({ deviceId, employeeNo, fingerprints }) =>
    hub(`/devices/${deviceId}/persons/${employeeNo}/fingerprints`, "PUT", { fingerprints }));

tool("override_fingerprints_broadcast", "Override an employee's fingerprints on MANY devices (all online, or targetDeviceIds).",
  { employeeNo: z.string(),
    fingerprints: z.array(z.object({
      fingerData: z.string(), fingerPrintID: z.number().int().optional(), fingerType: z.string().optional() })),
    targetDeviceIds: z.array(z.string()).optional() },
  ({ employeeNo, ...body }) => hub(`/persons/${employeeNo}/fingerprints/broadcast`, "PUT", body));

tool("sync_fingerprints", "Cross-branch: read a person's template(s) from a source device and push to every other online device.",
  { sourceDeviceId: z.string(), employeeNo: z.string(), targetDeviceIds: z.array(z.string()).optional() },
  (body) => hub(`/fingerprints/sync`, "POST", body));

// --- enrolment workflow ---
tool("enroll_person", "Create a person, then optionally capture+assign a fingerprint in one call.",
  { deviceId: z.string(), employeeNo: z.string(), name: z.string(),
    pin: z.string().optional(), fingerPrintID: z.number().int().optional() },
  ({ deviceId, ...body }) => hub(`/devices/${deviceId}/persons/enroll`, "POST", body));

// --- fire fake events ---
tool("sim_punch",
  "Fire a fake punch event; it POSTs to the webhook exactly like a real device event. " +
  "For a fingerprint punch you may pass fingerPrintID alone (owner is resolved). Omit time to use now.",
  {
    deviceId: z.string(),
    type: z.enum(["fingerprint", "card", "pin", "face", "button"]).default("fingerprint"),
    employeeNo: z.string().optional(),
    cardNo: z.string().optional(),
    fingerPrintID: z.number().int().optional(),
    success: z.boolean().optional(),
    time: z.string().optional().describe("ISO w/ offset, e.g. 2026-07-29T09:05:00+05:30; omit = now"),
    doorNo: z.number().int().optional(),
  },
  ({ deviceId, ...body }) => hub(`/sim/devices/${deviceId}/punch`, "POST", body));

tool("sim_punch_fingerprint_match",
  "Submit a fingerprint template; if it matches an enrolled template, trigger a punch for the matched employee. Returns matched:false if none match.",
  {
    deviceId: z.string(),
    fingerData: z.string().describe("Base64 template to match against enrolled fingers"),
    time: z.string().optional().describe("ISO w/ offset; omit = now"),
    doorNo: z.number().int().optional(),
  },
  ({ deviceId, ...body }) => hub(`/sim/devices/${deviceId}/punch/fingerprint/match`, "POST", body));

tool("sim_attendance",
  "Generate a check-in AND check-out punch per employee (fake daily attendance).",
  {
    deviceId: z.string(),
    employees: z.array(z.string()).describe("employeeNo list"),
    type: z.enum(["fingerprint", "card", "pin", "face"]).optional(),
    date: z.string().optional().describe("yyyy-MM-dd; omit = today"),
    checkInTime: z.string().optional().describe("HH:mm:ss, default 09:00:00"),
    checkOutTime: z.string().optional().describe("HH:mm:ss, default 17:30:00"),
  },
  ({ deviceId, ...body }) => hub(`/sim/devices/${deviceId}/attendance`, "POST", body));

tool("sim_list_events", "List recent fake events generated on a simulated device.",
  { deviceId: z.string(), limit: z.number().int().optional() },
  ({ deviceId, limit }) => hub(`/sim/devices/${deviceId}/events${limit ? `?limit=${limit}` : ""}`));

// --- go ---
const transport = new StdioServerTransport();
await server.connect(transport);
console.error(`[biometric-hub-mcp] connected · HUB_URL=${BASE} · auth=${TOKEN ? "on" : "off"}`);

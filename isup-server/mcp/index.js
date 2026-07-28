#!/usr/bin/env node
/**
 * Biometric Device Hub — MCP server.
 *
 * A thin stdio bridge: each MCP tool call is translated into an authenticated
 * HTTP request against the hub's REST API. Point it at a running hub and drive
 * the in-memory simulator (add devices, assign a webhook, fire fake punches,
 * generate attendance) straight from your AI tool.
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

// --- seed identity so events carry a real person ---
tool("seed_person", "Create or update a person on a device.",
  { deviceId: z.string(), employeeNo: z.string(), name: z.string() },
  ({ deviceId, employeeNo, name }) => hub(`/devices/${deviceId}/persons`, "POST", { employeeNo, name }));

tool("enroll_fingerprint", "Capture + assign a fingerprint to a person (the simulator generates a fake template).",
  { deviceId: z.string(), employeeNo: z.string(), fingerPrintID: z.number().int().optional() },
  ({ deviceId, employeeNo, fingerPrintID }) =>
    hub(`/devices/${deviceId}/persons/${employeeNo}/fingerprint/capture`, "POST",
      fingerPrintID ? { fingerPrintID } : {}));

tool("assign_card", "Assign a card number to a person.",
  { deviceId: z.string(), employeeNo: z.string(), cardNo: z.string() },
  ({ deviceId, employeeNo, cardNo }) =>
    hub(`/devices/${deviceId}/persons/${employeeNo}/card`, "POST", { cardNo }));

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

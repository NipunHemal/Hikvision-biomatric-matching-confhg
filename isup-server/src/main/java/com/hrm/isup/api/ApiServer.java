package com.hrm.isup.api;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.hrm.isup.Config;
import com.hrm.isup.device.ConnectedDevice;
import com.hrm.isup.device.DeviceAdapter;
import com.hrm.isup.device.DeviceManager;
import com.hrm.isup.device.EnrollmentService;
import com.hrm.isup.device.FingerprintSyncService;
import com.hrm.isup.model.Fingerprint;
import com.hrm.isup.model.Person;
import com.hrm.isup.model.Result;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The hub's HTTP/JSON API — what the HRM system calls to manage devices,
 * employees, fingerprints, and to run cross-branch sync. Every route resolves a
 * device by ISUP Device ID and delegates to that device's adapter, so the HRM is
 * decoupled from device model and transport.
 *
 * Uses the JDK's built-in HttpServer (no web-framework dependency).
 */
public final class ApiServer {

    private final DeviceManager manager;
    private final FingerprintSyncService sync;
    private final EnrollmentService enroll = new EnrollmentService();
    private final Gson gson = new Gson();
    private final java.util.Set<String> tokens = loadTokens();

    public ApiServer(DeviceManager manager, FingerprintSyncService sync) {
        this.manager = manager;
        this.sync = sync;
    }

    /** Bearer tokens the HRM must present. From API_TOKENS (comma-sep) / API_TOKEN. */
    private static java.util.Set<String> loadTokens() {
        java.util.Set<String> t = new java.util.HashSet<>();
        for (String x : Config.get("API_TOKENS").split(",")) {
            x = x.trim();
            if (!x.isEmpty()) t.add(x);
        }
        String single = Config.get("API_TOKEN");
        if (!single.isEmpty()) t.add(single);
        return t;
    }

    private boolean authorized(HttpExchange ex) {
        if (tokens.isEmpty()) return true; // no tokens configured -> open (dev only)
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h == null || !h.startsWith("Bearer ")) return false;
        byte[] provided = h.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        for (String t : tokens) {
            // constant-time compare to avoid leaking the token via timing
            if (java.security.MessageDigest.isEqual(t.getBytes(StandardCharsets.UTF_8), provided)) {
                return true;
            }
        }
        return false;
    }

    public void start() throws IOException {
        int port = Config.getInt("HttpApiPort", 8090);
        HttpServer http = HttpServer.create(new InetSocketAddress(port), 0);
        http.createContext("/", this::route);
        http.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
        http.start();
        System.out.println("[api] HTTP API on http://0.0.0.0:" + port
                + (tokens.isEmpty()
                    ? "  ⚠ AUTH DISABLED (set API_TOKENS to require a Bearer token)"
                    : "  (Bearer auth required, " + tokens.size() + " token(s))"));
    }

    private void route(HttpExchange ex) throws IOException {
        String m = ex.getRequestMethod();
        String[] s = ex.getRequestURI().getPath().replaceAll("^/|/$", "").split("/");
        try {
            // --- meta ---
            if (eq(s, "health")) {
                json(ex, 200, "{\"ok\":true,\"isupReady\":" + manager.isReady()
                        + ",\"devices\":" + manager.all().size() + "}");
                return;
            }

            // Everything else requires a valid Bearer token.
            if (!authorized(ex)) {
                ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                json(ex, 401, err("unauthorized — provide Authorization: Bearer <token>"));
                return;
            }

            // --- cross-branch fingerprint sync ---
            //   POST /fingerprints/sync {sourceDeviceId, employeeNo, targetDeviceIds?}
            if (m.equals("POST") && eq(s, "fingerprints", "sync")) {
                JsonObject b = body(ex);
                List<String> targets = new ArrayList<>();
                if (b.has("targetDeviceIds"))
                    b.getAsJsonArray("targetDeviceIds").forEach(e -> targets.add(e.getAsString()));
                var report = sync.sync(b.get("sourceDeviceId").getAsString(),
                        b.get("employeeNo").getAsString(), targets);
                json(ex, 200, gson.toJson(report));
                return;
            }

            // --- register/delete a person on ALL online devices at once ---
            if (m.equals("POST") && eq(s, "persons", "broadcast")) {
                JsonObject b = body(ex);
                Person p = new Person(b.get("employeeNo").getAsString(), b.get("name").getAsString());
                json(ex, 200, gson.toJson(broadcast(p, true)));
                return;
            }
            if (m.equals("DELETE") && s.length == 3 && s[0].equals("persons") && s[1].equals("broadcast")) {
                Person p = new Person(); p.employeeNo = s[2];
                json(ex, 200, gson.toJson(broadcast(p, false)));
                return;
            }

            // --- devices ---
            if (m.equals("GET") && eq(s, "devices")) { json(ex, 200, gson.toJson(deviceList())); return; }

            if (s.length >= 2 && s[0].equals("devices")) {
                ConnectedDevice dev = manager.get(s[1]);
                if (dev == null) { json(ex, 503, err("device not online: " + s[1])); return; }
                DeviceAdapter a = dev.adapter;

                if (m.equals("GET") && s.length == 2) { json(ex, 200, gson.toJson(brief(dev))); return; }
                if (m.equals("GET") && seg(s, 2, "info")) { relay(ex, a.deviceInfo()); return; }
                if (m.equals("GET") && seg(s, 2, "capabilities")) { json(ex, 200, gson.toJson(a.capabilities())); return; }

                // --- composite enrolment workflows ---
                // Add person + optionally capture & assign a fingerprint (one call)
                if (m.equals("POST") && seg(s, 2, "persons") && s.length == 4 && s[3].equals("enroll")) {
                    JsonObject b = body(ex);
                    Person p = new Person(b.get("employeeNo").getAsString(), b.get("name").getAsString());
                    if (b.has("pin")) p.pin = b.get("pin").getAsString();
                    Integer fpId = b.has("fingerPrintID") ? b.get("fingerPrintID").getAsInt() : null;
                    relay(ex, enroll.enrollPerson(a, p, fpId)); return;
                }
                // Capture a fingerprint AND assign it to a person (single)
                if (m.equals("POST") && seg(s, 2, "persons") && s.length == 6
                        && s[4].equals("fingerprint") && s[5].equals("capture")) {
                    JsonObject b = body(ex);
                    int id = b.has("fingerPrintID") ? b.get("fingerPrintID").getAsInt() : 1;
                    relay(ex, enroll.captureAndAssignFingerprint(a, s[3], id)); return;
                }
                // Capture MULTIPLE fingerprints and assign (bulk)
                if (m.equals("POST") && seg(s, 2, "persons") && s.length == 6
                        && s[4].equals("fingerprint") && s[5].equals("capture-bulk")) {
                    JsonObject b = body(ex);
                    List<Integer> ids = new ArrayList<>();
                    if (b.has("fingerPrintIDs"))
                        b.getAsJsonArray("fingerPrintIDs").forEach(e -> ids.add(e.getAsInt()));
                    else { int c = b.has("count") ? b.get("count").getAsInt() : 2;
                           for (int i = 1; i <= c; i++) ids.add(i); }
                    relay(ex, enroll.captureAndAssignFingerprintBulk(a, s[3], ids)); return;
                }
                // Capture a card AND assign it to a person
                if (m.equals("POST") && seg(s, 2, "persons") && s.length == 6
                        && s[4].equals("card") && s[5].equals("capture")) {
                    JsonObject b = body(ex);
                    relay(ex, enroll.captureAndAssignCard(a, s[3],
                            b.has("cardType") ? b.get("cardType").getAsString() : null)); return;
                }
                // Capture a card only (read the number, no assignment)
                if (m.equals("POST") && seg(s, 2, "card") && s.length == 4 && s[3].equals("capture")) {
                    relay(ex, a.captureCard()); return;
                }

                // persons
                if (m.equals("GET") && seg(s, 2, "persons") && s.length == 3) {
                    json(ex, 200, gson.toJson(a.listPersons())); return;
                }
                if (m.equals("POST") && seg(s, 2, "persons") && s.length == 3) {
                    JsonObject b = body(ex);
                    Person p = new Person(b.get("employeeNo").getAsString(), b.get("name").getAsString());
                    if (b.has("beginTime")) p.beginTime = b.get("beginTime").getAsString();
                    if (b.has("endTime")) p.endTime = b.get("endTime").getAsString();
                    if (b.has("pin")) p.pin = b.get("pin").getAsString();
                    relay(ex, a.upsertPerson(p)); return;
                }
                if (m.equals("DELETE") && seg(s, 2, "persons") && s.length == 4) {
                    relay(ex, a.deletePerson(s[3])); return;
                }
                // PIN
                if (m.equals("POST") && seg(s, 2, "persons") && s.length == 5 && s[4].equals("pin")) {
                    relay(ex, a.setPin(s[3], body(ex).get("pin").getAsString())); return;
                }
                // cards
                if (m.equals("POST") && seg(s, 2, "persons") && s.length == 5 && s[4].equals("card")) {
                    JsonObject b = body(ex);
                    relay(ex, a.assignCard(s[3], b.get("cardNo").getAsString(),
                            b.has("cardType") ? b.get("cardType").getAsString() : null)); return;
                }
                if (m.equals("GET") && seg(s, 2, "persons") && s.length == 5 && s[4].equals("cards")) {
                    relay(ex, a.listCards(s[3])); return;
                }
                if (m.equals("DELETE") && seg(s, 2, "persons") && s.length == 6 && s[4].equals("cards")) {
                    relay(ex, a.deleteCard(s[3], s[5])); return;
                }
                // fingerprints
                if (m.equals("GET") && seg(s, 2, "persons") && s.length == 5 && s[4].equals("fingerprints")) {
                    json(ex, 200, gson.toJson(a.listFingerprints(s[3]))); return;
                }
                if (m.equals("POST") && seg(s, 2, "persons") && s.length == 5 && s[4].equals("fingerprint")) {
                    JsonObject b = body(ex);
                    Fingerprint fp = new Fingerprint(s[3],
                            b.has("fingerPrintID") ? b.get("fingerPrintID").getAsInt() : 1,
                            b.get("fingerData").getAsString());
                    relay(ex, a.downloadFingerprint(fp)); return;
                }
                if (m.equals("DELETE") && seg(s, 2, "persons") && s.length == 6 && s[4].equals("fingerprints")) {
                    relay(ex, a.deleteFingerprint(s[3], Integer.valueOf(s[5]))); return;
                }
                // fingerprint capture (scan at the terminal)
                if (m.equals("POST") && seg(s, 2, "fingerprint") && s.length == 4 && s[3].equals("capture")) {
                    JsonObject b = body(ex);
                    relay(ex, a.captureFingerprint(b.has("fingerNo") ? b.get("fingerNo").getAsInt() : 1)); return;
                }
                // doors
                if (m.equals("POST") && seg(s, 2, "door") && s.length == 3) {
                    JsonObject b = body(ex);
                    relay(ex, a.controlDoor(
                            b.has("doorNo") ? b.get("doorNo").getAsInt() : 1,
                            b.has("cmd") ? b.get("cmd").getAsString() : "open")); return;
                }
            }

            json(ex, 404, err("not found"));
        } catch (IllegalStateException offline) {
            json(ex, 503, err(offline.getMessage()));
        } catch (Exception e) {
            json(ex, 500, err(e.getMessage()));
        }
    }

    // --- operations spanning devices ---

    private List<Map<String, Object>> broadcast(Person p, boolean create) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConnectedDevice d : manager.all()) {
            if (!d.online) continue;
            Result r = create ? d.adapter.upsertPerson(p) : d.adapter.deletePerson(p.employeeNo);
            out.add(Map.of("deviceId", d.deviceId, "ok", r.ok, "reply", r.body));
        }
        return out;
    }

    private List<Map<String, Object>> deviceList() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ConnectedDevice d : manager.all()) out.add(brief(d));
        return out;
    }

    private Map<String, Object> brief(ConnectedDevice d) {
        return Map.of("deviceId", d.deviceId, "model", d.model, "online", d.online,
                "adapter", d.adapter.getClass().getSimpleName());
    }

    // --- http helpers ---

    private boolean eq(String[] s, String... parts) {
        if (s.length != parts.length) return false;
        for (int i = 0; i < parts.length; i++) if (!s[i].equals(parts[i])) return false;
        return true;
    }

    private boolean seg(String[] s, int i, String v) { return s.length > i && s[i].equals(v); }

    private JsonObject body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            String str = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return str.isEmpty() ? new JsonObject() : gson.fromJson(str, JsonObject.class);
        }
    }

    private void relay(HttpExchange ex, Result r) throws IOException {
        json(ex, r.ok ? 200 : 502, r.body);
    }

    private void json(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private String err(String msg) { return "{\"ok\":false,\"error\":" + gson.toJson(msg) + "}"; }
}

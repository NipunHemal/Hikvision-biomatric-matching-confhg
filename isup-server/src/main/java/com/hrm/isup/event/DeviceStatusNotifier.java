package com.hrm.isup.event;

import com.google.gson.Gson;
import com.hrm.isup.Config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Notifies the HRM backend when a terminal connects or disconnects over ISUP.
 *
 * Edge-triggered: it only POSTs when a device's status actually CHANGES, so the
 * constant keepalive re-registrations don't spam the backend. Fire-and-forget
 * (async), so a slow/unreachable backend never blocks the ISUP callbacks.
 *
 * Config (env / config.properties):
 *   DEVICE_STATUS_WEBHOOK_URL      full endpoint, e.g. https://api/api/v1/devices/devices/status-event
 *   DEVICE_STATUS_WEBHOOK_ENABLED  1/0 — master on/off (default on; needs a URL to fire)
 *   DEVICE_STATUS_WEBHOOK_SECRET   sent as a header for the backend to verify (optional)
 *   DEVICE_STATUS_WEBHOOK_HEADER   header name for the secret (default X-Hub-Secret)
 */
public final class DeviceStatusNotifier {

    private final boolean enabled;
    private final String url;
    private final String secret;
    private final String header;
    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    /** Last status POSTed per deviceCode — for edge-triggering. */
    private final Map<String, Boolean> lastStatus = new ConcurrentHashMap<>();

    public DeviceStatusNotifier() {
        this.enabled = truthy(Config.get("DeviceStatusWebhookEnabled"), true);
        this.url = Config.get("DeviceStatusWebhookUrl");
        this.secret = Config.get("DeviceStatusWebhookSecret");
        String h = Config.get("DeviceStatusWebhookHeader");
        this.header = h.isEmpty() ? "X-Hub-Secret" : h;

        if (enabled && !url.isEmpty())
            System.out.println("[status] device status webhook -> " + url);
        else
            System.out.println("[status] device status webhook disabled"
                    + (url.isEmpty() ? " (no DEVICE_STATUS_WEBHOOK_URL)" : " (DEVICE_STATUS_WEBHOOK_ENABLED=0)"));
    }

    public void online(String deviceCode) { notify(deviceCode, true, "isup_registration"); }

    public void offline(String deviceCode) { notify(deviceCode, false, "isup_keepalive_timeout"); }

    private void notify(String deviceCode, boolean online, String source) {
        if (deviceCode == null || deviceCode.isEmpty()) return;

        // Edge-trigger: skip if the status hasn't changed since last time.
        Boolean prev = lastStatus.get(deviceCode);
        if (prev != null && prev == online) return;
        lastStatus.put(deviceCode, online);

        String status = online ? "online" : "offline";
        System.out.println("[status] " + deviceCode + " -> " + status);

        if (!enabled || url.isEmpty()) return;

        // Ordered map so the JSON matches the documented contract shape.
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deviceCode", deviceCode);
        body.put("status", status);
        body.put("timestamp", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        body.put("eventSource", source);

        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json");
            if (!secret.isEmpty()) b.header(header, secret);
            http.sendAsync(b.POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body))).build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            System.err.println("[status] notify failed for " + deviceCode + ": " + e.getMessage());
        }
    }

    private static boolean truthy(String v, boolean def) {
        if (v == null || v.isEmpty()) return def;
        return v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes");
    }
}

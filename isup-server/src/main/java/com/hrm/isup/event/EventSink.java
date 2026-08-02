package com.hrm.isup.event;

import com.google.gson.Gson;
import com.hrm.isup.Config;
import com.hrm.isup.model.AccessEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where normalised punch events go: forwarded to the HRM webhook and logged.
 * Both real (poll/alarm) and simulated events flow through here, so the HRM
 * cannot tell them apart.
 *
 * Webhook target resolution (first match wins):
 *   1. a per-device webhook  (POST /sim/devices/{id}/webhook)
 *   2. the global override    (POST /sim/webhook)
 *   3. the {@code HrmEventUrl} config / env value
 * If none is set, events are log-only.
 */
public final class EventSink {

    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final String hrmUrl = Config.get("HrmEventUrl");
    private volatile String webhookOverride;
    private final Map<String, String> deviceWebhooks = new ConcurrentHashMap<>();

    /** Set/clear the GLOBAL webhook (null/blank clears → falls back to HrmEventUrl). */
    public void setWebhook(String url) {
        this.webhookOverride = (url == null || url.isBlank()) ? null : url.trim();
    }

    /** Set/clear a PER-DEVICE webhook (null/blank clears → falls back to the global one). */
    public void setDeviceWebhook(String deviceId, String url) {
        if (deviceId == null) return;
        if (url == null || url.isBlank()) deviceWebhooks.remove(deviceId);
        else deviceWebhooks.put(deviceId, url.trim());
    }

    /** The global override target, or the HrmEventUrl, or null. */
    public String webhook() {
        if (webhookOverride != null) return webhookOverride;
        return (hrmUrl == null || hrmUrl.isEmpty()) ? null : hrmUrl;
    }

    /** The effective target for a device: per-device → global → HrmEventUrl. */
    public String webhookFor(String deviceId) {
        String d = deviceId == null ? null : deviceWebhooks.get(deviceId);
        return d != null ? d : webhook();
    }

    public void accept(AccessEvent event) {
        String target = webhookFor(event.deviceId);
        if (target == null) return; // log-only mode (caller already logged)

        Map<String, Object> payload = buildCustomPayload(event);
        String jsonStr = gson.toJson(payload);
        System.out.println("[hrm-forward] " + event.deviceId + " -> " + target + " payload: " + jsonStr);

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(target))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonStr))
                    .build();
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            System.err.println("[event] forward failed: " + e.getMessage());
        }
    }

    private static Map<String, Object> buildCustomPayload(AccessEvent event) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("deviceCode", event.deviceId != null ? event.deviceId : "");
        map.put("employeeNo", event.employeeNo != null ? event.employeeNo : "");
        map.put("punchTime", formatTime(event.time));
        map.put("verifyMode", deriveVerifyMode(event));
        return map;
    }

    private static String deriveVerifyMode(AccessEvent event) {
        if (event.verifyMethod != null && !event.verifyMethod.isBlank()) {
            return event.verifyMethod.toLowerCase();
        }
        if (event.verifyMode != null && !event.verifyMode.isBlank()) {
            return event.verifyMode.toLowerCase();
        }
        if (event.cardNo != null && !event.cardNo.isBlank()) {
            return "card";
        }
        return "fingerprint";
    }

    private static String formatTime(String timeStr) {
        if (timeStr == null || timeStr.isBlank()) {
            return java.time.Instant.now().toString();
        }
        return timeStr;
    }
}

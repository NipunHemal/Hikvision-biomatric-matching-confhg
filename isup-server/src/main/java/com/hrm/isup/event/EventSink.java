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
        System.out.println("[hrm-forward] " + event.deviceId + " -> " + target);

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(target))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(event)))
                    .build();
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            System.err.println("[event] forward failed: " + e.getMessage());
        }
    }
}

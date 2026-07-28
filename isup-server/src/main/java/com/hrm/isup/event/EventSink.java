package com.hrm.isup.event;

import com.google.gson.Gson;
import com.hrm.isup.Config;
import com.hrm.isup.model.AccessEvent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Where normalised punch events go: forwarded to the HRM webhook and logged.
 * Both real (poll/alarm) and simulated events flow through here, so the HRM
 * cannot tell them apart.
 *
 * Webhook target resolution: a runtime override (set via the simulator's
 * {@code POST /sim/webhook}) wins; otherwise the {@code HrmEventUrl} config /
 * env value. If neither is set, events are log-only.
 */
public final class EventSink {

    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final String hrmUrl = Config.get("HrmEventUrl");
    private volatile String webhookOverride;

    /** Set/clear the runtime webhook (null/blank clears → falls back to HrmEventUrl). */
    public void setWebhook(String url) {
        this.webhookOverride = (url == null || url.isBlank()) ? null : url.trim();
    }

    /** The effective webhook target, or null if none configured. */
    public String webhook() {
        if (webhookOverride != null) return webhookOverride;
        return (hrmUrl == null || hrmUrl.isEmpty()) ? null : hrmUrl;
    }

    public void accept(AccessEvent event) {
        String target = webhook();
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

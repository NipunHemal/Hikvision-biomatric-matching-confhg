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
 * Where normalised punch events go: forwarded to the HRM webhook (HrmEventUrl)
 * and logged. Swap or extend this to also persist locally, queue, etc.
 */
public final class EventSink {

    private final Gson gson = new Gson();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final String hrmUrl = Config.get("HrmEventUrl");

    public void accept(AccessEvent event) {
        if (hrmUrl == null || hrmUrl.isEmpty()) return; // log-only mode (caller already logged)
        System.out.println("[hrm-forward] " + event.deviceId + " -> " + hrmUrl);

        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(hrmUrl))
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

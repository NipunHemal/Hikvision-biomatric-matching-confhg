package com.hrm.isup.event;

import com.hrm.isup.Config;
import com.hrm.isup.model.AccessEvent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Inbound device-webhook gateway: validates a vendor's HTTP event push, picks the
 * right {@link WebhookParser}, normalizes to a canonical {@link AccessEvent}, and
 * forwards it to the backend via {@link EventSink} — the same path ISUP-polled
 * events take, so the backend gets one uniform stream from every device brand.
 *
 * Config:
 *   DEVICE_WEBHOOK_ENABLED        1/0 master switch (default on)
 *   DEVICE_WEBHOOK_BASIC_USER/PASS  optional HTTP Basic (Hikvision httpAuthenticationMethod)
 *   DEVICE_WEBHOOK_SECRET         optional shared secret
 *   DEVICE_WEBHOOK_SECRET_HEADER  header carrying the secret (default X-Webhook-Secret)
 * If no Basic user and no secret are set, the endpoint is open (dev only).
 */
public final class WebhookGateway {

    public enum Status { OK, SKIPPED, UNAUTHORIZED, DISABLED, UNKNOWN_VENDOR, BAD_REQUEST }

    public record Outcome(Status status, String message, AccessEvent event) {}

    private final EventSink sink;
    private final Map<String, WebhookParser> parsers = new HashMap<>();
    private final boolean enabled;
    private final String basicUser, basicPass, secret, secretHeader;

    /** Last serialNo forwarded per device — drops the device's immediate retries. */
    private final Map<String, String> lastSerial = new ConcurrentHashMap<>();

    public WebhookGateway(EventSink sink) {
        this.sink = sink;
        register(new HikvisionWebhookParser());

        this.enabled = truthy(Config.get("DeviceWebhookEnabled"), true);
        this.basicUser = Config.get("DeviceWebhookBasicUser");
        this.basicPass = Config.get("DeviceWebhookBasicPass");
        this.secret = Config.get("DeviceWebhookSecret");
        String h = Config.get("DeviceWebhookSecretHeader");
        this.secretHeader = h.isEmpty() ? "X-Webhook-Secret" : h;

        String auth = !basicUser.isEmpty() && !secret.isEmpty() ? "basic+secret"
                : !basicUser.isEmpty() ? "basic" : !secret.isEmpty() ? "secret" : "OPEN";
        System.out.println("[gateway] device webhook " + (enabled ? "enabled" : "disabled")
                + " (auth=" + auth + ", vendors=" + parsers.keySet() + ")");
    }

    public void register(WebhookParser p) { parsers.put(p.vendor().toLowerCase(), p); }

    public boolean enabled() { return enabled; }

    /** Header name that carries the secret (so the caller can read it off the request). */
    public String secretHeader() { return secretHeader; }

    /** True if the request satisfies the configured auth (open if none configured). */
    public boolean authorize(String authorizationHeader, String secretHeaderValue) {
        boolean needBasic = !basicUser.isEmpty();
        boolean needSecret = !secret.isEmpty();
        if (!needBasic && !needSecret) return true;                       // open (dev)
        if (needSecret && secretHeaderValue != null && constEq(secret, secretHeaderValue)) return true;
        if (needBasic && checkBasic(authorizationHeader)) return true;
        return false;
    }

    /** Parse + forward. Auth is checked by the caller via {@link #authorize}. */
    public Outcome handle(String vendor, String deviceCode, byte[] body, String contentType) {
        if (!enabled) return new Outcome(Status.DISABLED, "device webhook disabled", null);

        WebhookParser p = parsers.get(vendor == null ? "" : vendor.toLowerCase());
        if (p == null) return new Outcome(Status.UNKNOWN_VENDOR, "unknown vendor: " + vendor, null);

        AccessEvent evt = p.parse(body, contentType, deviceCode);
        if (evt == null) return new Outcome(Status.BAD_REQUEST, "unparseable payload", null);

        if (EventCodes.isHeartbeat(evt)) return new Outcome(Status.SKIPPED, "heartbeat", evt);

        if (evt.serialNo != null && !evt.serialNo.isEmpty()
                && evt.serialNo.equals(lastSerial.get(deviceCode))) {
            return new Outcome(Status.SKIPPED, "duplicate serialNo", evt);
        }
        if (evt.serialNo != null && !evt.serialNo.isEmpty()) lastSerial.put(deviceCode, evt.serialNo);

        System.out.println("[gateway] " + deviceCode + " "
                + (evt.eventName != null ? evt.eventName : "minor=" + evt.minorType)
                + " employee=" + evt.employeeNo + " card=" + evt.cardNo);
        sink.accept(evt);   // → HRM_EVENT_URL / per-device webhook
        return new Outcome(Status.OK, "forwarded", evt);
    }

    // --- helpers ---
    private boolean checkBasic(String h) {
        if (h == null || !h.startsWith("Basic ")) return false;
        try {
            String decoded = new String(Base64.getDecoder().decode(h.substring(6).trim()), StandardCharsets.UTF_8);
            int i = decoded.indexOf(':');
            if (i < 0) return false;
            return constEq(basicUser, decoded.substring(0, i)) && constEq(basicPass, decoded.substring(i + 1));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean constEq(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean truthy(String v, boolean def) {
        if (v == null || v.isEmpty()) return def;
        return v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes");
    }
}

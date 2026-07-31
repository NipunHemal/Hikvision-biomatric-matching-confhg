package com.hrm.isup.event;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.hrm.isup.model.AccessEvent;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a Hikvision access terminal's event webhook into the canonical
 * {@link AccessEvent}. Handles all three shapes a terminal sends: application/json,
 * XML (text/plain on some firmwares), and multipart/form-data (a JSON/XML part +
 * JPEG snapshots). Ported from the Node reference (src/parseEvent.js /
 * lib/hikvision-event-parser).
 *
 * The minor event code — not currentVerifyMode — decides the credential/method.
 */
public final class HikvisionWebhookParser implements WebhookParser {

    private final Gson gson = new Gson();

    @Override public String vendor() { return "hikvision"; }

    @Override
    public AccessEvent parse(byte[] body, String contentType, String deviceCode) {
        if (body == null || body.length == 0) return null;
        String ct = contentType == null ? "" : contentType.toLowerCase();

        String text;
        if (ct.contains("multipart")) {
            text = multipartTextPart(body, contentType);   // pull the JSON/XML part
            if (text == null) return null;
        } else {
            text = new String(body, StandardCharsets.UTF_8).replaceAll("\0+$", "").trim();
        }
        if (text.isEmpty()) return null;

        // XML (some firmwares send text/plain XML)
        if (text.charAt(0) == '<') {
            return normalise(xmlField(text), text, deviceCode);
        }
        // JSON
        try {
            JsonObject o = gson.fromJson(text, JsonObject.class);
            if (o == null) return null;
            JsonObject alert = o.has("EventNotificationAlert")
                    ? o.getAsJsonObject("EventNotificationAlert") : o;
            return normalise(jsonField(alert), text, deviceCode);
        } catch (Exception e) {
            return null;
        }
    }

    // --- a single field accessor over either JSON or XML ---
    private interface Field { String get(String key); }

    /** JSON: look inside AccessControllerEvent first, then the top-level alert. */
    private Field jsonField(JsonObject alert) {
        JsonObject ace = alert.has("AccessControllerEvent")
                ? alert.getAsJsonObject("AccessControllerEvent") : null;
        return key -> {
            if (ace != null && ace.has(key) && !ace.get(key).isJsonNull()) return ace.get(key).getAsString();
            if (alert.has(key) && !alert.get(key).isJsonNull()) return alert.get(key).getAsString();
            return null;
        };
    }

    /** XML: field names are unique, so a flat tag match works regardless of nesting. */
    private Field xmlField(String xml) {
        return key -> {
            Matcher m = Pattern.compile("<" + Pattern.quote(key) + ">([^<]*)</" + Pattern.quote(key) + ">").matcher(xml);
            return m.find() ? m.group(1).trim() : null;
        };
    }

    private AccessEvent normalise(Field f, String raw, String deviceCode) {
        AccessEvent e = new AccessEvent();
        e.deviceId = deviceCode;
        e.eventType = f.get("eventType");
        e.deviceIp = f.get("ipAddress");

        Integer major = intOrNull(f.get("majorEventType"));
        Integer minor = intOrNull(f.get("subEventType"));
        e.majorType = major != null ? major : 0;
        e.minorType = minor != null ? minor : 0;

        EventCodes.Desc d = EventCodes.describe(e.majorType, e.minorType);
        e.eventName = d.name();
        e.verifyMethod = d.method();
        e.success = d.success() == null ? null : (d.success() ? 1 : 0);

        String emp = f.get("employeeNoString");
        if (emp == null) emp = f.get("employeeNo");
        e.employeeNo = (emp == null || emp.isEmpty()) ? null : emp;

        e.personName = f.get("name");
        e.cardNo = f.get("cardNo");
        e.verifyMode = f.get("currentVerifyMode");
        e.attendanceStatus = f.get("attendanceStatus");
        e.serialNo = f.get("serialNo");
        Integer door = intOrNull(f.get("doorNo"));
        e.doorNo = door != null ? door : 0;
        e.time = f.get("dateTime");
        e.raw = raw;
        return e;
    }

    /** Pull the first JSON/XML text part out of a raw multipart body. */
    private String multipartTextPart(byte[] body, String contentType) {
        // Decode 1:1 so JPEG bytes don't corrupt boundary detection.
        String text = new String(body, StandardCharsets.ISO_8859_1);
        Matcher bm = Pattern.compile("boundary=(?:\"([^\"]+)\"|([^\";]+))", Pattern.CASE_INSENSITIVE)
                .matcher(contentType == null ? "" : contentType);
        if (!bm.find()) return null;
        String boundary = bm.group(1) != null ? bm.group(1) : bm.group(2);
        if (boundary == null || boundary.isEmpty()) return null;

        Pattern isImage = Pattern.compile("Content-Type:\\s*image/", Pattern.CASE_INSENSITIVE);
        for (String part : text.split("--" + Pattern.quote(boundary))) {
            int sep = part.indexOf("\r\n\r\n");
            if (sep < 0) continue;
            if (isImage.matcher(part.substring(0, sep)).find()) continue; // skip JPEG parts
            String content = part.substring(sep + 4)
                    .replaceAll("\r\n--\\s*$", "").replaceAll("\r\n$", "").replaceAll("\0+$", "").trim();
            if (content.startsWith("{") || content.startsWith("<")) {
                // re-decode the extracted text as UTF-8
                return new String(content.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private Integer intOrNull(String v) {
        if (v == null || v.isEmpty()) return null;
        try { return (int) Double.parseDouble(v.trim()); } catch (Exception e) { return null; }
    }
}

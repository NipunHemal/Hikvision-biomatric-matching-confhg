package com.hrm.isup.event;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hrm.isup.Config;
import com.hrm.isup.device.CardCaptureRegistry;
import com.hrm.isup.device.ConnectedDevice;
import com.hrm.isup.device.DeviceManager;
import com.hrm.isup.model.AccessEvent;
import com.hrm.isup.model.Result;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls each online device's access-event log ({@code /ISAPI/AccessControl/AcsEvent})
 * over the reliable ISAPI passthrough and forwards new punches to the HRM.
 *
 * This is the robust alternative to the push/alarm channel: the DS-K1T808
 * multiplexes over one MQTT connection and the SDK's auto event-host setup times
 * out, but plain passthrough queries work. Each device is polled on a fixed
 * interval for a sliding time window; rows are de-duplicated by serialNo so
 * overlapping windows never double-send, and the first poll only sets a baseline
 * (no history replay).
 */
public final class EventPollService {

    private final DeviceManager manager;
    private final EventSink sink;
    private final Gson gson = new Gson();

    private final int intervalSec = Config.getInt("EventPollIntervalSec", 15);
    private final int lookbackSec = Config.getInt("EventPollLookbackSec", 120);
    private final ZoneOffset tz = parseOffset(Config.get("EventPollTZ"));
    private final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    /** Highest AcsEvent serialNo already forwarded, per device. */
    private final Map<String, Long> lastSerial = new ConcurrentHashMap<>();
    private ScheduledExecutorService exec;

    public EventPollService(DeviceManager manager, EventSink sink) {
        this.manager = manager;
        this.sink = sink;
    }

    public void start() {
        if ("0".equals(Config.get("EventPollEnabled"))) {
            System.out.println("[poll] event polling disabled (EventPollEnabled=0)");
            return;
        }
        exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "event-poll");
            t.setDaemon(true);
            return t;
        });
        exec.scheduleWithFixedDelay(this::pollAllSafe, intervalSec, intervalSec, TimeUnit.SECONDS);
        System.out.println("[poll] event polling every " + intervalSec + "s (AcsEvent over passthrough, tz="
                + tz + ")");
    }

    public void stop() { if (exec != null) exec.shutdownNow(); }

    private void pollAllSafe() {
        try {
            for (ConnectedDevice d : manager.all()) {
                if (d.online && !d.simulated) pollDevice(d);
            }
        } catch (Throwable t) {
            System.out.println("[poll] cycle error: " + t.getMessage());
        }
    }

    private void pollDevice(ConnectedDevice d) {
        OffsetDateTime now = OffsetDateTime.now(tz);
        String start = now.minusSeconds(lookbackSec).format(fmt);
        String end = now.plusSeconds(5).format(fmt);

        long maxSeen = lastSerial.getOrDefault(d.deviceId, -1L);
        long batchMax = maxSeen;
        boolean firstPoll = !lastSerial.containsKey(d.deviceId);
        int position = 0, emitted = 0;

        for (int page = 0; page < 50; page++) {
            Result r = d.adapter.queryAcsEvents(start, end, position, 30);
            if (!r.ok) {
                if (firstPoll) System.out.println("[poll] " + d.deviceId + " AcsEvent query failed: "
                        + preview(r.body));
                return;
            }
            JsonObject acs = obj(r.body).getAsJsonObject("AcsEvent");
            if (acs == null) return;
            JsonArray list = acs.getAsJsonArray("InfoList");
            int got = list == null ? 0 : list.size();

            if (list != null) {
                for (JsonElement e : list) {
                    JsonObject row = e.getAsJsonObject();
                    long serial = asLong(row, "serialNo");
                    if (serial > batchMax) batchMax = serial;
                    if (firstPoll || serial <= maxSeen) continue; // baseline / already sent
                    emit(d.deviceId, row);
                    emitted++;
                }
            }

            String status = str(acs, "responseStatusStrg");
            position += got;
            if (got == 0 || !"MORE".equals(status)) break;
        }

        if (batchMax > maxSeen) lastSerial.put(d.deviceId, batchMax);
        else if (firstPoll) lastSerial.put(d.deviceId, maxSeen); // record baseline even if empty
        if (emitted > 0) System.out.println("[poll] " + d.deviceId + " forwarded " + emitted + " event(s)");
    }

    private void emit(String deviceId, JsonObject row) {
        AccessEvent evt = new AccessEvent();
        evt.deviceId = deviceId;
        evt.majorType = (int) asLong(row, "major");
        evt.minorType = (int) asLong(row, "minor");
        evt.eventName = "accessEvent";
        evt.time = str(row, "time");
        evt.cardNo = str(row, "cardNo");
        evt.employeeNo = row.has("employeeNoString") ? str(row, "employeeNoString") : str(row, "employeeNo");
        evt.personName = str(row, "name");
        evt.doorNo = (int) asLong(row, "doorNo");
        evt.raw = gson.toJson(row);

        System.out.println("[event] " + deviceId + " minor=" + evt.minorType
                + " employee=" + evt.employeeNo + " card=" + evt.cardNo + " time=" + evt.time);

        if (evt.cardNo != null && !evt.cardNo.isEmpty() && !"0".equals(evt.cardNo))
            CardCaptureRegistry.offer(deviceId, evt.cardNo);

        sink.accept(evt);
    }

    // --- helpers ---
    private JsonObject obj(String json) {
        try {
            JsonElement e = gson.fromJson(json, JsonElement.class);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (Exception ex) { return new JsonObject(); }
    }

    private String str(JsonObject o, String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private long asLong(JsonObject o, String k) {
        try { return o != null && o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsLong() : -1L; }
        catch (Exception e) { return -1L; }
    }

    private String preview(String s) { return s == null ? "" : s.substring(0, Math.min(160, s.length())); }

    private static ZoneOffset parseOffset(String v) {
        try { return (v == null || v.isEmpty()) ? ZoneOffset.of("+05:30") : ZoneOffset.of(v); }
        catch (Exception e) { return ZoneOffset.of("+05:30"); }
    }
}

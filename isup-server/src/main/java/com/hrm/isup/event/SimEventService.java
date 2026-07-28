package com.hrm.isup.event;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hrm.isup.Config;
import com.hrm.isup.device.CardCaptureRegistry;
import com.hrm.isup.device.ConnectedDevice;
import com.hrm.isup.device.DeviceAdapter;
import com.hrm.isup.device.SimulatedDeviceAdapter;
import com.hrm.isup.model.AccessEvent;
import com.hrm.isup.model.Person;
import com.hrm.isup.model.Result;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Generates FAKE punch events for the in-memory simulator and pushes them through
 * the same {@link EventSink} the real device path uses — so the HRM receives a
 * payload identical to a genuine punch (only the values are synthetic).
 *
 * Event codes mirror a real Hikvision access terminal (major 5): fingerprint 113,
 * card 38, face 75/76, exit-button 27. PIN has no canonical code in this project,
 * so it uses a configurable default ({@code SimPinMinor}) and can be overridden.
 */
public final class SimEventService {

    public static final int MAJOR = 5;
    private static final int HISTORY_CAP = 200;

    private final EventSink sink;
    private final Gson gson = new Gson();
    private final ZoneOffset tz = parseOffset(Config.get("EventPollTZ"));
    private final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");
    private final int pinMinor = Config.getInt("SimPinMinor", 1);

    private final Map<String, Long> serial = new ConcurrentHashMap<>();
    private final Map<String, Deque<AccessEvent>> history = new ConcurrentHashMap<>();

    public SimEventService(EventSink sink) { this.sink = sink; }

    /** A punch request. Any null field is defaulted / resolved from the sim store. */
    public static final class Punch {
        public String type = "fingerprint";   // fingerprint|card|pin|face|button
        public String employeeNo;
        public String cardNo;
        public Integer fingerPrintID;
        public Integer success;                // 1/0, default 1
        public String time;                    // ISO w/ offset; blank => now
        public Integer doorNo;
        public Integer minor;                  // override the derived minor code
        public String verifyMethod;            // override the derived verify method
        public String attendanceStatus;        // e.g. checkIn / checkOut
    }

    /** Build and push a fake punch. Returns the emitted event. */
    public AccessEvent emit(ConnectedDevice dev, Punch p) {
        String deviceId = dev.deviceId;
        String type = (p.type == null ? "fingerprint" : p.type.toLowerCase());

        int minor;
        String eventName, verify;
        switch (type) {
            case "card"     -> { minor = 38;  eventName = "cardAuthSuccess";  verify = "card"; }
            case "face"     -> { boolean fail = p.success != null && p.success == 0;
                                 minor = fail ? 76 : 75;
                                 eventName = fail ? "faceAuthFail" : "faceAuthSuccess"; verify = "face"; }
            case "button", "exit" -> { minor = 27; eventName = "exitButtonPressed"; verify = "button"; }
            case "pin", "password" -> { minor = pinMinor; eventName = "pinAuthSuccess"; verify = "password"; }
            default          -> { minor = 113; eventName = "fingerprintAuthSuccess"; verify = "fingerprint"; }
        }
        if (p.minor != null) minor = p.minor;
        if (p.verifyMethod != null) verify = p.verifyMethod;

        DeviceAdapter a = dev.adapter;

        // Resolve the employee: explicit, or (for a fingerprint punch) by finger id.
        String employeeNo = p.employeeNo;
        if (employeeNo == null && p.fingerPrintID != null && a instanceof SimulatedDeviceAdapter sim) {
            employeeNo = sim.employeeOfFingerprint(p.fingerPrintID);
        }
        String name = null;
        if (employeeNo != null) {
            Person person = a.getPerson(employeeNo);
            if (person != null) name = person.name;
        }
        String cardNo = p.cardNo;
        if (cardNo == null && type.equals("card") && employeeNo != null) {
            cardNo = firstCard(a, employeeNo);
        }

        AccessEvent evt = new AccessEvent();
        evt.deviceId = deviceId;
        evt.majorType = MAJOR;
        evt.minorType = minor;
        evt.eventName = eventName;
        evt.verifyMethod = verify;
        evt.success = (p.success != null) ? p.success : 1;
        evt.employeeNo = employeeNo;
        evt.personName = name;
        evt.cardNo = cardNo;
        evt.doorNo = (p.doorNo != null) ? p.doorNo : 1;
        evt.time = (p.time != null && !p.time.isBlank()) ? p.time : OffsetDateTime.now(tz).format(fmt);

        long sn = serial.merge(deviceId, 1L, Long::sum);
        evt.raw = buildRaw(evt, sn, p.attendanceStatus);

        System.out.println("[sim-event] " + deviceId + " " + eventName + " employee=" + employeeNo
                + " card=" + cardNo + " time=" + evt.time);

        if (cardNo != null && !cardNo.isEmpty()) CardCaptureRegistry.offer(deviceId, cardNo);

        record(deviceId, evt);
        sink.accept(evt);   // → HRM webhook (immediate)
        return evt;
    }

    /** Recent fake events for a device (most recent last), capped by {@code limit}. */
    public List<AccessEvent> history(String deviceId, int limit) {
        Deque<AccessEvent> d = history.get(deviceId);
        if (d == null) return List.of();
        List<AccessEvent> all = new ArrayList<>(d);
        int from = Math.max(0, all.size() - Math.max(1, limit));
        return all.subList(from, all.size());
    }

    // --- helpers ---
    private void record(String deviceId, AccessEvent evt) {
        Deque<AccessEvent> d = history.computeIfAbsent(deviceId, k -> new ConcurrentLinkedDeque<>());
        d.addLast(evt);
        while (d.size() > HISTORY_CAP) d.pollFirst();
    }

    private String firstCard(DeviceAdapter a, String employeeNo) {
        try {
            Result r = a.listCards(employeeNo);
            JsonObject o = gson.fromJson(r.body, JsonObject.class);
            JsonArray arr = o == null ? null : o.getAsJsonArray("cards");
            if (arr != null && arr.size() > 0) return arr.get(0).getAsString();
        } catch (Exception ignored) { }
        return null;
    }

    private String buildRaw(AccessEvent e, long serialNo, String attendanceStatus) {
        JsonObject ace = new JsonObject();
        ace.addProperty("majorEventType", e.majorType);
        ace.addProperty("subEventType", e.minorType);
        if (e.employeeNo != null) ace.addProperty("employeeNoString", e.employeeNo);
        if (e.personName != null) ace.addProperty("name", e.personName);
        if (e.cardNo != null) ace.addProperty("cardNo", e.cardNo);
        ace.addProperty("serialNo", serialNo);
        ace.addProperty("doorNo", e.doorNo);
        if (attendanceStatus != null) ace.addProperty("attendanceStatus", attendanceStatus);
        JsonObject alert = new JsonObject();
        alert.addProperty("ipAddress", "simulated");
        alert.addProperty("eventType", "AccessControllerEvent");
        alert.addProperty("dateTime", e.time);
        alert.add("AccessControllerEvent", ace);
        return gson.toJson(alert);
    }

    private static ZoneOffset parseOffset(String v) {
        try { return (v == null || v.isEmpty()) ? ZoneOffset.of("+05:30") : ZoneOffset.of(v); }
        catch (Exception e) { return ZoneOffset.of("+05:30"); }
    }
}

package com.hrm.isup.device;

import com.google.gson.Gson;
import com.hrm.isup.model.Fingerprint;
import com.hrm.isup.model.Person;
import com.hrm.isup.model.Result;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A fully in-memory device — no ISUP, no network. Lets the whole HTTP API,
 * adapter contract, Postman collection and HRM integration be tested without a
 * real terminal. Behaves like a DS-K1T808: stores persons/cards/fingerprints and
 * returns device-style replies.
 */
public final class SimulatedDeviceAdapter implements DeviceAdapter {

    private final String model;
    private final Gson gson = new Gson();
    private final Map<String, Person> persons = new ConcurrentHashMap<>();
    private final Map<String, List<String>> cards = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, Fingerprint>> fps = new ConcurrentHashMap<>();
    private int cardSeq = 1000;

    public SimulatedDeviceAdapter(String model) {
        this.model = (model == null || model.isBlank()) ? "DS-K1T808MFWX-B" : model;
    }

    @Override public String model() { return model; }

    @Override public Capabilities capabilities() {
        Capabilities c = new Capabilities();
        c.face = true;
        return c;
    }

    private Result ok() { return Result.ok("{\"statusCode\":1,\"statusString\":\"OK\",\"subStatusCode\":\"ok\"}"); }

    @Override public Result deviceInfo() {
        return Result.ok("{\"DeviceInfo\":{\"deviceName\":\"Simulated\",\"model\":\"" + model
                + "\",\"serialNumber\":\"SIM-" + model + "\",\"firmwareVersion\":\"V3.25.20\"}}");
    }

    // --- persons ---
    @Override public Result upsertPerson(Person p) { persons.put(p.employeeNo, p); return ok(); }

    @Override public Result deletePerson(String employeeNo) {
        persons.remove(employeeNo); cards.remove(employeeNo); fps.remove(employeeNo);
        return ok();
    }

    @Override public List<Person> listPersons() { return new ArrayList<>(persons.values()); }

    @Override public Person getPerson(String employeeNo) { return persons.get(employeeNo); }

    @Override public Result setPin(String employeeNo, String pin) {
        Person p = persons.get(employeeNo);
        if (p == null) return Result.fail("{\"subStatusCode\":\"employeeNoNotExist\"}");
        p.pin = pin; return ok();
    }

    // --- cards ---
    @Override public Result assignCard(String employeeNo, String cardNo, String cardType) {
        cards.computeIfAbsent(employeeNo, k -> new ArrayList<>()).add(cardNo); return ok();
    }

    @Override public Result listCards(String employeeNo) {
        return Result.ok(gson.toJson(Map.of("employeeNo", employeeNo,
                "cards", cards.getOrDefault(employeeNo, List.of()))));
    }

    @Override public Result deleteCard(String employeeNo, String cardNo) {
        List<String> l = cards.get(employeeNo); if (l != null) l.remove(cardNo); return ok();
    }

    @Override public Result captureCard() {
        return Result.ok("{\"cardNo\":\"" + (100000000L + (cardSeq++)) + "\"}");
    }

    // --- fingerprints ---
    @Override public Result captureFingerprint(int fingerNo) {
        String template = java.util.Base64.getEncoder()
                .encodeToString(("SIMFP-" + fingerNo + "-" + System.nanoTime()).getBytes());
        return Result.ok(gson.toJson(Map.of(
                "fingerNo", fingerNo, "fingerPrintQuality", 80, "fingerData", template)));
    }

    @Override public Result downloadFingerprint(Fingerprint fp) {
        fps.computeIfAbsent(fp.employeeNo, k -> new ConcurrentHashMap<>()).put(fp.fingerPrintID, fp);
        return ok();
    }

    @Override public List<Fingerprint> listFingerprints(String employeeNo) {
        Map<Integer, Fingerprint> m = fps.get(employeeNo);
        return m == null ? List.of() : new ArrayList<>(m.values());
    }

    @Override public Result deleteFingerprint(String employeeNo, Integer fingerPrintID) {
        Map<Integer, Fingerprint> m = fps.get(employeeNo);
        if (m != null) { if (fingerPrintID == null) m.clear(); else m.remove(fingerPrintID); }
        return ok();
    }

    // --- door ---
    @Override public Result controlDoor(int doorNo, String cmd) {
        return Result.ok("{\"statusCode\":1,\"statusString\":\"OK\",\"door\":" + doorNo
                + ",\"cmd\":\"" + cmd + "\"}");
    }
}

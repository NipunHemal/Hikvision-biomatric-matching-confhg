package com.hrm.isup.device;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hrm.isup.model.Fingerprint;
import com.hrm.isup.model.Person;
import com.hrm.isup.model.Result;

import java.util.List;

/**
 * Employee query + maintenance operations that read/aggregate across a device's
 * primitives: existence checks, a full profile (person + fingerprints + cards +
 * pin), and fingerprint replacement (wipe-then-insert). Built on DeviceAdapter,
 * so they work identically for real terminals and the simulator.
 */
public final class EmployeeService {

    private final Gson gson = new Gson();

    /** True if the employee is enrolled on this device. */
    public boolean exists(DeviceAdapter a, String employeeNo) {
        return a.getPerson(employeeNo) != null;
    }

    /** {employeeNo, exists, person?} — a light existence check with basic info. */
    public JsonObject existsReport(DeviceAdapter a, String employeeNo) {
        Person p = a.getPerson(employeeNo);
        JsonObject out = new JsonObject();
        out.addProperty("employeeNo", employeeNo);
        out.addProperty("exists", p != null);
        if (p != null) out.addProperty("name", p.name);
        return out;
    }

    /** Full profile: person data, fingerprints (with templates), cards, pin. */
    public JsonObject details(DeviceAdapter a, String employeeNo) {
        JsonObject out = new JsonObject();
        out.addProperty("employeeNo", employeeNo);

        Person p = a.getPerson(employeeNo);
        out.addProperty("exists", p != null);
        if (p != null) {
            JsonObject person = new JsonObject();
            person.addProperty("employeeNo", p.employeeNo);
            person.addProperty("name", p.name);
            person.addProperty("userType", p.userType);
            person.addProperty("beginTime", p.beginTime);
            person.addProperty("endTime", p.endTime);
            // PIN is write-only on real terminals (never returned by search); it is
            // only present here for the simulator. null => not retrievable.
            person.addProperty("pin", p.pin);
            out.add("person", person);
        }

        List<Fingerprint> fps = a.listFingerprints(employeeNo);
        JsonArray fpArr = new JsonArray();
        for (Fingerprint f : fps) {
            JsonObject o = new JsonObject();
            o.addProperty("fingerPrintID", f.fingerPrintID);
            o.addProperty("fingerType", f.fingerType);
            o.addProperty("cardReaderNo", f.cardReaderNo);
            o.addProperty("fingerData", f.fingerData);
            fpArr.add(o);
        }
        out.add("fingerprints", fpArr);
        out.addProperty("fingerprintCount", fpArr.size());

        Result cards = a.listCards(employeeNo);
        out.add("cards", safe(cards.body));

        out.addProperty("pinNote", "PIN is write-only on hardware devices; null means not retrievable");
        return out;
    }

    /**
     * Replace ALL of a person's fingerprints: delete the existing set, then
     * insert the supplied templates (one or many). Idempotent re-enrolment.
     */
    public JsonObject replaceFingerprints(DeviceAdapter a, String employeeNo, List<Fingerprint> newFps) {
        Result del = a.deleteFingerprint(employeeNo, null); // null => delete all fingers

        JsonArray results = new JsonArray();
        int ok = 0;
        for (Fingerprint fp : newFps) {
            fp.employeeNo = employeeNo;
            Result r = a.downloadFingerprint(fp);
            JsonObject item = new JsonObject();
            item.addProperty("fingerPrintID", fp.fingerPrintID);
            item.addProperty("ok", r.ok);
            item.addProperty("detail", r.body);
            if (r.ok) ok++;
            results.add(item);
        }

        JsonObject out = new JsonObject();
        out.addProperty("employeeNo", employeeNo);
        out.addProperty("previousDeleted", del.ok);
        out.addProperty("requested", newFps.size());
        out.addProperty("inserted", ok);
        out.add("fingers", results);
        return out;
    }

    private JsonElement safe(String json) {
        try { return gson.fromJson(json, JsonElement.class); }
        catch (Exception e) { return new com.google.gson.JsonPrimitive(String.valueOf(json)); }
    }
}

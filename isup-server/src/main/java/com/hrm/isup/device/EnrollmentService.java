package com.hrm.isup.device;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hrm.isup.model.Fingerprint;
import com.hrm.isup.model.Person;
import com.hrm.isup.model.Result;

import java.util.List;

/**
 * Composite enrolment workflows — the multi-step, real-world operations the HRM
 * wants as one call (capture-then-assign, create-then-enrol), built on the
 * adapter's primitive operations.
 */
public final class EnrollmentService {

    private final Gson gson = new Gson();

    /** Capture one finger at the reader and assign it to a person. */
    public Result captureAndAssignFingerprint(DeviceAdapter a, String employeeNo, int fingerPrintID) {
        Result cap = a.captureFingerprint(fingerPrintID);
        if (!cap.ok) return cap;
        String data = jsonField(cap.body, "fingerData");
        if (data == null) return Result.fail(cap.body);

        Fingerprint fp = new Fingerprint(employeeNo, fingerPrintID, data);
        Result dl = a.downloadFingerprint(fp);

        JsonObject out = new JsonObject();
        out.addProperty("employeeNo", employeeNo);
        out.addProperty("fingerPrintID", fingerPrintID);
        out.addProperty("quality", intField(cap.body, "fingerPrintQuality"));
        out.addProperty("assigned", dl.ok);
        out.addProperty("deviceReply", dl.body);
        return Result.ok(gson.toJson(out));
    }

    /** Capture several fingers in sequence and assign each to a person. */
    public Result captureAndAssignFingerprintBulk(DeviceAdapter a, String employeeNo, List<Integer> ids) {
        JsonArray results = new JsonArray();
        int ok = 0;
        for (int id : ids) {
            Result r = captureAndAssignFingerprint(a, employeeNo, id);
            JsonObject item = new JsonObject();
            item.addProperty("fingerPrintID", id);
            item.addProperty("ok", r.ok);
            item.addProperty("detail", r.body);
            if (r.ok) ok++;
            results.add(item);
        }
        JsonObject out = new JsonObject();
        out.addProperty("employeeNo", employeeNo);
        out.addProperty("requested", ids.size());
        out.addProperty("succeeded", ok);
        out.add("fingers", results);
        return Result.ok(gson.toJson(out));
    }

    /** Create/update a person, then optionally capture+assign a fingerprint. */
    public Result enrollPerson(DeviceAdapter a, Person person, Integer fingerPrintID) {
        Result created = a.upsertPerson(person);
        JsonObject out = new JsonObject();
        out.addProperty("employeeNo", person.employeeNo);
        out.addProperty("personCreated", created.ok);
        out.addProperty("personReply", created.body);
        if (fingerPrintID != null) {
            Result fp = captureAndAssignFingerprint(a, person.employeeNo, fingerPrintID);
            out.addProperty("fingerprintCaptured", fp.ok);
            out.add("fingerprint", safe(fp.body));
        }
        return Result.ok(gson.toJson(out));
    }

    /** Capture a card at the reader and assign it to a person. */
    public Result captureAndAssignCard(DeviceAdapter a, String employeeNo, String cardType) {
        Result cap = a.captureCard();
        if (!cap.ok) return cap;
        String cardNo = jsonField(cap.body, "cardNo");
        if (cardNo == null) return Result.fail(cap.body);

        Result assign = a.assignCard(employeeNo, cardNo, cardType);
        JsonObject out = new JsonObject();
        out.addProperty("employeeNo", employeeNo);
        out.addProperty("cardNo", cardNo);
        out.addProperty("assigned", assign.ok);
        out.addProperty("deviceReply", assign.body);
        return Result.ok(gson.toJson(out));
    }

    // --- helpers ---
    private String jsonField(String json, String key) {
        try {
            JsonObject o = gson.fromJson(json, JsonObject.class);
            return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
        } catch (Exception e) { return null; }
    }

    private int intField(String json, String key) {
        String v = jsonField(json, key);
        try { return v == null ? 0 : Integer.parseInt(v); } catch (Exception e) { return 0; }
    }

    private com.google.gson.JsonElement safe(String json) {
        try { return gson.fromJson(json, com.google.gson.JsonElement.class); }
        catch (Exception e) { return new com.google.gson.JsonPrimitive(String.valueOf(json)); }
    }
}

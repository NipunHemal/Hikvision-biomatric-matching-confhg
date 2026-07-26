package com.hrm.isup.device;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hrm.isup.model.Fingerprint;
import com.hrm.isup.model.Person;
import com.hrm.isup.model.Result;
import com.hrm.isup.transport.Transport;

import java.util.ArrayList;
import java.util.List;

/**
 * Standard ISAPI implementation of the device operations, sent over whatever
 * Transport it is given. Request paths and JSON bodies are ported from the Node
 * isapi.js, which was validated against the real terminal.
 *
 * Model-specific adapters extend this and override only the calls that differ.
 */
public abstract class AbstractIsapiAdapter implements DeviceAdapter {

    protected final Transport tx;
    protected final Gson gson = new Gson();

    protected AbstractIsapiAdapter(Transport tx) {
        this.tx = tx;
    }

    @Override public Capabilities capabilities() { return new Capabilities(); }

    // --- device ---
    @Override public Result deviceInfo() {
        return tx.get("/ISAPI/System/deviceInfo?format=json");
    }

    // --- persons ---
    @Override public Result upsertPerson(Person p) {
        JsonObject valid = new JsonObject();
        valid.addProperty("enable", true);
        valid.addProperty("beginTime", p.beginTime);
        valid.addProperty("endTime", p.endTime);

        JsonObject plan = new JsonObject();
        plan.addProperty("doorNo", p.doorNo);
        plan.addProperty("planTemplateNo", "1");
        JsonArray rightPlan = new JsonArray();
        rightPlan.add(plan);

        JsonObject user = new JsonObject();
        user.addProperty("employeeNo", p.employeeNo);
        user.addProperty("name", p.name);
        user.addProperty("userType", p.userType);
        user.add("Valid", valid);
        user.addProperty("doorRight", "1");
        user.add("RightPlan", rightPlan);

        JsonObject body = new JsonObject();
        body.add("UserInfo", user);
        String json = gson.toJson(body);

        Result r = tx.post("/ISAPI/AccessControl/UserInfo/Record?format=json", json);
        if (r.ok && r.body.contains("deviceUserAlreadyExist")) {
            return tx.put("/ISAPI/AccessControl/UserInfo/Modify?format=json", json);
        }
        return r;
    }

    @Override public Result deletePerson(String employeeNo) {
        JsonObject emp = new JsonObject();
        emp.addProperty("employeeNo", employeeNo);
        JsonArray list = new JsonArray();
        list.add(emp);
        JsonObject cond = new JsonObject();
        cond.add("EmployeeNoList", list);
        JsonObject body = new JsonObject();
        body.add("UserInfoDelCond", cond);
        return tx.put("/ISAPI/AccessControl/UserInfo/Delete?format=json", gson.toJson(body));
    }

    @Override public List<Person> listPersons() {
        List<Person> out = new ArrayList<>();
        int position = 0;
        String searchID = "persons-" + System.nanoTime();

        for (int guard = 0; guard < 200; guard++) {
            JsonObject cond = new JsonObject();
            cond.addProperty("searchID", searchID);
            cond.addProperty("searchResultPosition", position);
            cond.addProperty("maxResults", 30);
            JsonObject body = new JsonObject();
            body.add("UserInfoSearchCond", cond);

            Result r = tx.post("/ISAPI/AccessControl/UserInfo/Search?format=json", gson.toJson(body));
            if (!r.ok) break;
            JsonObject search = safeObj(r.body).getAsJsonObject("UserInfoSearch");
            if (search == null) break;

            JsonArray arr = search.getAsJsonArray("UserInfo");
            if (arr != null) {
                for (JsonElement e : arr) {
                    JsonObject u = e.getAsJsonObject();
                    Person p = new Person(str(u, "employeeNo"), str(u, "name"));
                    out.add(p);
                }
            }
            int got = arr == null ? 0 : arr.size();
            String status = str(search, "responseStatusStrg");
            position += got;
            if (got == 0 || !"MORE".equals(status)) break;
        }
        return out;
    }

    // --- cards ---
    @Override public Result assignCard(String employeeNo, String cardNo, String cardType) {
        JsonObject card = new JsonObject();
        card.addProperty("employeeNo", employeeNo);
        card.addProperty("cardNo", cardNo);
        card.addProperty("cardType", cardType == null ? "normalCard" : cardType);
        JsonObject body = new JsonObject();
        body.add("CardInfo", card);
        return tx.post("/ISAPI/AccessControl/CardInfo/Record?format=json", gson.toJson(body));
    }

    // --- fingerprints ---
    @Override public Result downloadFingerprint(Fingerprint fp) {
        JsonArray readers = new JsonArray();
        readers.add(fp.cardReaderNo);
        JsonObject cfg = new JsonObject();
        cfg.addProperty("employeeNo", fp.employeeNo);
        cfg.add("enableCardReader", readers);
        cfg.addProperty("fingerPrintID", fp.fingerPrintID);
        cfg.addProperty("fingerType", fp.fingerType);
        cfg.addProperty("fingerData", fp.fingerData);
        JsonObject body = new JsonObject();
        body.add("FingerPrintCfg", cfg);
        return tx.post("/ISAPI/AccessControl/FingerPrintDownload?format=json", gson.toJson(body));
    }

    @Override public List<Fingerprint> listFingerprints(String employeeNo) {
        List<Fingerprint> out = new ArrayList<>();
        String searchID = "fp-" + System.nanoTime();

        for (int guard = 0; guard < 20; guard++) {
            JsonObject cond = new JsonObject();
            cond.addProperty("searchID", searchID);
            cond.addProperty("employeeNo", employeeNo);
            JsonObject body = new JsonObject();
            body.add("FingerPrintCond", cond);

            Result r = tx.post("/ISAPI/AccessControl/FingerPrintUpload?format=json", gson.toJson(body));
            if (!r.ok) break;
            JsonObject info = safeObj(r.body).getAsJsonObject("FingerPrintInfo");
            if (info == null) break;

            JsonArray arr = info.getAsJsonArray("FingerPrintList");
            if (arr != null) {
                for (JsonElement e : arr) {
                    JsonObject f = e.getAsJsonObject();
                    Fingerprint fp = new Fingerprint();
                    fp.employeeNo = employeeNo;
                    fp.fingerPrintID = f.has("fingerPrintID") ? f.get("fingerPrintID").getAsInt() : 0;
                    fp.fingerType = str(f, "fingerType");
                    fp.cardReaderNo = f.has("cardReaderNo") ? f.get("cardReaderNo").getAsInt() : 1;
                    fp.fingerData = str(f, "fingerData");
                    out.add(fp);
                }
            }
            if (!"OK".equals(str(info, "status"))) break;  // "NoFP" = done
        }
        return out;
    }

    @Override public Result deleteFingerprint(String employeeNo, Integer fingerPrintID) {
        JsonObject detail = new JsonObject();
        detail.addProperty("employeeNo", employeeNo);
        if (fingerPrintID != null) {
            JsonArray ids = new JsonArray();
            ids.add(fingerPrintID);
            detail.add("fingerPrintID", ids);
        }
        JsonObject del = new JsonObject();
        del.addProperty("mode", "byEmployeeNo");
        del.add("EmployeeNoDetail", detail);
        JsonObject body = new JsonObject();
        body.add("FingerPrintDelete", del);
        return tx.put("/ISAPI/AccessControl/FingerPrintDelete?format=json", gson.toJson(body));
    }

    // --- doors ---
    @Override public Result controlDoor(int doorNo, String cmd) {
        JsonObject rc = new JsonObject();
        rc.addProperty("cmd", cmd == null ? "open" : cmd);
        JsonObject body = new JsonObject();
        body.add("RemoteControlDoor", rc);
        return tx.put("/ISAPI/AccessControl/RemoteControl/door/" + doorNo + "?format=json",
                gson.toJson(body));
    }

    // --- helpers ---
    protected JsonObject safeObj(String json) {
        try {
            JsonElement e = gson.fromJson(json, JsonElement.class);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (Exception ex) {
            return new JsonObject();
        }
    }

    protected String str(JsonObject o, String k) {
        return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }
}

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
        if (p.pin != null && !p.pin.isEmpty()) user.addProperty("password", p.pin);

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

    /** Targeted single-person lookup (device supports an EmployeeNoList filter). */
    @Override public Person getPerson(String employeeNo) {
        if (employeeNo == null) return null;
        JsonObject emp = new JsonObject();
        emp.addProperty("employeeNo", employeeNo);
        JsonArray idList = new JsonArray();
        idList.add(emp);
        JsonObject cond = new JsonObject();
        cond.addProperty("searchID", "person-" + System.nanoTime());
        cond.addProperty("searchResultPosition", 0);
        cond.addProperty("maxResults", 30);
        cond.add("EmployeeNoList", idList);
        JsonObject body = new JsonObject();
        body.add("UserInfoSearchCond", cond);

        Result r = tx.post("/ISAPI/AccessControl/UserInfo/Search?format=json", gson.toJson(body));
        if (r.ok) {
            JsonObject search = safeObj(r.body).getAsJsonObject("UserInfoSearch");
            JsonArray arr = search == null ? null : search.getAsJsonArray("UserInfo");
            if (arr != null) {
                for (JsonElement e : arr) {
                    JsonObject u = e.getAsJsonObject();
                    if (employeeNo.equals(str(u, "employeeNo"))) return toPerson(u);
                }
            }
        }
        // Some firmwares ignore the filter — fall back to a full scan.
        return DeviceAdapter.super.getPerson(employeeNo);
    }

    private Person toPerson(JsonObject u) {
        Person p = new Person(str(u, "employeeNo"), str(u, "name"));
        String ut = str(u, "userType");
        if (ut != null) p.userType = ut;
        JsonObject valid = u.getAsJsonObject("Valid");
        if (valid != null) {
            if (str(valid, "beginTime") != null) p.beginTime = str(valid, "beginTime");
            if (str(valid, "endTime") != null) p.endTime = str(valid, "endTime");
        }
        return p;
    }

    // --- PIN / password ---
    @Override public Result setPin(String employeeNo, String pin) {
        JsonObject user = new JsonObject();
        user.addProperty("employeeNo", employeeNo);
        user.addProperty("password", pin);
        JsonObject body = new JsonObject();
        body.add("UserInfo", user);
        return tx.put("/ISAPI/AccessControl/UserInfo/Modify?format=json", gson.toJson(body));
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

    @Override public Result listCards(String employeeNo) {
        JsonObject emp = new JsonObject();
        emp.addProperty("employeeNo", employeeNo);
        JsonArray list = new JsonArray();
        list.add(emp);
        JsonObject cond = new JsonObject();
        cond.addProperty("searchID", "cards-" + System.nanoTime());
        cond.addProperty("searchResultPosition", 0);
        cond.addProperty("maxResults", 30);
        cond.add("EmployeeNoList", list);
        JsonObject body = new JsonObject();
        body.add("CardInfoSearchCond", cond);
        return tx.post("/ISAPI/AccessControl/CardInfo/Search?format=json", gson.toJson(body));
    }

    @Override public Result captureCard() {
        // Reads the next card presented at the reader. Blocks until a card is
        // swiped, so it relies on the long passthrough timeout (RECV_TIMEOUT_MS).
        Result r = tx.post("/ISAPI/AccessControl/CaptureCardInfo?format=json", "{}");
        String preview = r.body == null ? "" : r.body.substring(0, Math.min(400, r.body.length()));
        System.out.println("[card] CaptureCardInfo ok=" + r.ok + " reply=" + preview);
        if (!r.ok) return r;

        // The device wraps the number under a container whose name varies by
        // firmware (CaptureCardInfo / CardInfo / top-level), and some return XML.
        // Find "cardNo" wherever it is instead of guessing the wrapper.
        String cardNo = findValue(r.body, "cardNo");
        if (cardNo == null) cardNo = xmlTag(r.body, "cardNo");

        if (cardNo == null || cardNo.isEmpty() || cardNo.equals("0")) {
            // Surface exactly what the device said so the reason is visible
            // (e.g. timeout, "not support", "deviceBusy", empty swipe).
            return Result.fail("{\"ok\":false,\"error\":\"no card captured\",\"deviceReply\":"
                    + gson.toJson(r.body) + "}");
        }
        JsonObject out = new JsonObject();
        out.addProperty("cardNo", cardNo);
        String cardType = findValue(r.body, "cardType");
        if (cardType != null) out.addProperty("cardType", cardType);
        return Result.ok(gson.toJson(out));
    }

    /** Find the first non-empty value for {@code key} anywhere in a JSON tree. */
    private String findValue(String json, String key) {
        try { return findValue(gson.fromJson(json, JsonElement.class), key); }
        catch (Exception e) { return null; }
    }

    private String findValue(JsonElement el, String key) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            if (o.has(key) && o.get(key).isJsonPrimitive()) {
                String v = o.get(key).getAsString();
                if (v != null && !v.isEmpty()) return v;
            }
            for (var e : o.entrySet()) {
                String v = findValue(e.getValue(), key);
                if (v != null) return v;
            }
        } else if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                String v = findValue(e, key);
                if (v != null) return v;
            }
        }
        return null;
    }

    @Override public Result deleteCard(String employeeNo, String cardNo) {
        JsonObject card = new JsonObject();
        card.addProperty("cardNo", cardNo);
        JsonArray list = new JsonArray();
        list.add(card);
        JsonObject cond = new JsonObject();
        cond.add("CardNoList", list);
        JsonObject body = new JsonObject();
        body.add("CardInfoDelCond", cond);
        return tx.put("/ISAPI/AccessControl/CardInfo/Delete?format=json", gson.toJson(body));
    }

    // --- fingerprints ---
    @Override public Result captureFingerprint(int fingerNo) {
        // XML-only (no ?format=json). The device scans and returns the template
        // as XML; we parse it into clean JSON for the HRM.
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<CaptureFingerPrintCond version=\"2.0\" xmlns=\"http://www.isapi.org/ver20/XMLSchema\">"
                + "<fingerNo>" + fingerNo + "</fingerNo></CaptureFingerPrintCond>";
        Result r = tx.post("/ISAPI/AccessControl/CaptureFingerPrint", xml);
        if (!r.ok) return r;

        String data = xmlTag(r.body, "fingerData");
        if (data == null) {
            // capture failed (no finger, low quality, busy) — surface the reason
            String err = xmlTag(r.body, "subStatusCode");
            return Result.fail("{\"ok\":false,\"error\":" + gson.toJson(err != null ? err : r.body) + "}");
        }
        JsonObject out = new JsonObject();
        out.addProperty("fingerNo", intTag(r.body, "fingerNo", fingerNo));
        out.addProperty("fingerPrintQuality", intTag(r.body, "fingerPrintQuality", 0));
        out.addProperty("fingerData", data);
        return Result.ok(gson.toJson(out));
    }

    private String xmlTag(String xml, String tag) {
        if (xml == null) return null;
        var m = java.util.regex.Pattern.compile("<" + tag + ">([^<]*)</" + tag + ">").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    private int intTag(String xml, String tag, int def) {
        String v = xmlTag(xml, tag);
        try { return v == null ? def : Integer.parseInt(v.trim()); } catch (Exception e) { return def; }
    }

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

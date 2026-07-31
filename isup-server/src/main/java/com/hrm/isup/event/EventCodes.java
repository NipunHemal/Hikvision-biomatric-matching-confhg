package com.hrm.isup.event;

import com.hrm.isup.model.AccessEvent;

import java.util.Map;

/**
 * Canonical Hikvision access-event codes (major 5). The MINOR code — not
 * currentVerifyMode — authoritatively names the credential that matched.
 *
 * Ported from the Node reference (src/eventCodes.js / lib/hikvision-event-parser).
 * Centralized so both the inbound webhook gateway and (later) EventPollService
 * classify events the same way.
 */
public final class EventCodes {

    public static final int MAJOR_EVENT = 5;

    /** A resolved description of a minor code. */
    public record Desc(String name, String method, Boolean success) {}

    private static final Map<Integer, Desc> MINOR = Map.of(
            27,  new Desc("exitButtonPressed",      "button",      true),
            38,  new Desc("cardAuthSuccess",        "card",        true),
            75,  new Desc("faceAuthSuccess",        "face",        true),
            76,  new Desc("faceAuthFail",           "face",        false),
            113, new Desc("fingerprintAuthSuccess", "fingerprint", true)
            // Add device-specific minors here as they are observed (see event.raw).
    );

    private EventCodes() {}

    /** name/method/success for a major+minor, or all-null when unmapped. */
    public static Desc describe(int major, int minor) {
        Desc d = major == MAJOR_EVENT ? MINOR.get(minor) : null;
        return d != null ? d : new Desc(null, null, null);
    }

    /**
     * A keep-alive (no credential) — not a real punch. Matches the Node rule:
     * non-access envelope, or no employeeNo/cardNo and no resolved method.
     */
    public static boolean isHeartbeat(AccessEvent e) {
        if (e == null) return true;
        if (e.eventType != null && !"AccessControllerEvent".equals(e.eventType)) return true;
        if ("exitButtonPressed".equals(e.eventName)) return false; // real, but has no person
        boolean noEmp = e.employeeNo == null || e.employeeNo.isEmpty();
        boolean noCard = e.cardNo == null || e.cardNo.isEmpty();
        return noEmp && noCard && e.verifyMethod == null;
    }
}

package com.hrm.isup.model;

/** A normalised access/punch event pushed up from a device (the canonical shape). */
public final class AccessEvent {
    public String deviceId;
    public int majorType;
    public int minorType;
    public String eventName;      // fingerprintAuthSuccess, cardAuthSuccess, ...
    public String verifyMethod;   // fingerprint | card | face | button
    public Integer success;       // 1 / 0 / null
    public String employeeNo;
    public String personName;
    public String cardNo;
    public String time;
    public int doorNo;
    public String raw;

    // Enrichment (nullable; populated where the source provides them).
    public String eventType;        // e.g. AccessControllerEvent (for heartbeat detection)
    public String deviceIp;         // device's own IP from the payload, if any
    public String serialNo;         // event serial (dedup / ordering)
    public String verifyMode;       // currentVerifyMode — context only, never decides method
    public String attendanceStatus; // checkIn / checkOut / ... when the device reports it
}

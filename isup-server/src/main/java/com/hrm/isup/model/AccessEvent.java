package com.hrm.isup.model;

/** A normalised access/punch event pushed up from a device. */
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
}

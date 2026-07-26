package com.hrm.isup.model;

/**
 * A fingerprint template bound to a person + finger slot.
 *
 * fingerData (Base64) is the portable template — it is what makes cross-branch
 * sync possible: capture on one device, download to the others.
 */
public final class Fingerprint {
    public String employeeNo;
    public int fingerPrintID = 1;      // 1..10
    public String fingerType = "normalFP";
    public int cardReaderNo = 1;
    public String fingerData;          // Base64 template

    public Fingerprint() {}

    public Fingerprint(String employeeNo, int fingerPrintID, String fingerData) {
        this.employeeNo = employeeNo;
        this.fingerPrintID = fingerPrintID;
        this.fingerData = fingerData;
    }
}

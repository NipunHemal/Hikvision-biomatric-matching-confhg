package com.hrm.isup.device;

import com.hrm.isup.model.Fingerprint;
import com.hrm.isup.model.Person;
import com.hrm.isup.model.Result;

import java.util.List;

/**
 * The full set of operations the hub can perform on a device, independent of
 * model or transport. Add support for a new device model by implementing this
 * (usually by extending {@link AbstractIsapiAdapter} and overriding only what
 * differs).
 */
public interface DeviceAdapter {

    String model();

    /** Model capability flags, so the HRM can adapt its UI per device. */
    Capabilities capabilities();

    // --- device ---
    Result deviceInfo();

    // --- persons ---
    Result upsertPerson(Person person);
    Result deletePerson(String employeeNo);
    List<Person> listPersons();

    /**
     * Look up a single person by employeeNo, or null if not enrolled on this
     * device. Default scans listPersons(); adapters should override with a
     * targeted query where the device supports one.
     */
    default Person getPerson(String employeeNo) {
        if (employeeNo == null) return null;
        for (Person p : listPersons()) {
            if (employeeNo.equals(p.employeeNo)) return p;
        }
        return null;
    }

    // --- PIN ---
    Result setPin(String employeeNo, String pin);

    // --- cards ---
    Result assignCard(String employeeNo, String cardNo, String cardType);
    Result listCards(String employeeNo);
    Result deleteCard(String employeeNo, String cardNo);
    Result captureCard();                              // read a card at the reader

    // --- fingerprints ---
    Result captureFingerprint(int fingerNo);           // scan at the terminal
    Result downloadFingerprint(Fingerprint fp);        // push a template
    List<Fingerprint> listFingerprints(String employeeNo);
    Result deleteFingerprint(String employeeNo, Integer fingerPrintID);

    // --- doors ---
    Result controlDoor(int doorNo, String cmd);

    // --- events ---
    /**
     * Query the device's access-event log (card taps / fingerprint & face
     * punches) for a time window. Used to POLL for events over the reliable
     * ISAPI passthrough, instead of the push/alarm channel. Default: unsupported.
     */
    default Result queryAcsEvents(String startTime, String endTime, int position, int maxResults) {
        return Result.fail("{\"error\":\"acs events not supported\"}");
    }

    final class Capabilities {
        public boolean persons = true;
        public boolean cards = true;
        public boolean fingerprint = true;
        public boolean face = false;
        public int maxFingerprintsPerPerson = 10;
    }
}

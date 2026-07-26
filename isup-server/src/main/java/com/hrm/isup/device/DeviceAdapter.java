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

    // --- cards ---
    Result assignCard(String employeeNo, String cardNo, String cardType);

    // --- fingerprints ---
    Result downloadFingerprint(Fingerprint fp);
    List<Fingerprint> listFingerprints(String employeeNo);
    Result deleteFingerprint(String employeeNo, Integer fingerPrintID);

    // --- doors ---
    Result controlDoor(int doorNo, String cmd);

    final class Capabilities {
        public boolean persons = true;
        public boolean cards = true;
        public boolean fingerprint = true;
        public boolean face = false;
        public int maxFingerprintsPerPerson = 10;
    }
}

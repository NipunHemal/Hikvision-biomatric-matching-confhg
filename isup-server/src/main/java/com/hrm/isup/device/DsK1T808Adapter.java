package com.hrm.isup.device;

import com.hrm.isup.transport.Transport;

/**
 * Adapter for the DS-K1T808MFWX-B fingerprint terminal (firmware V3.25.x).
 *
 * It uses the standard ISAPI behaviour, so nothing needs overriding yet — the
 * class exists as the seam where any model-specific quirks (payload tweaks,
 * capability limits) go when discovered.
 */
public final class DsK1T808Adapter extends AbstractIsapiAdapter {

    public static final String MODEL = "DS-K1T808MFWX-B";

    public DsK1T808Adapter(Transport tx) {
        super(tx);
    }

    @Override public String model() { return MODEL; }

    @Override public Capabilities capabilities() {
        Capabilities c = new Capabilities();
        c.persons = true;
        c.cards = true;
        c.fingerprint = true;
        c.face = true;                    // this model has a face module
        c.maxFingerprintsPerPerson = 10;
        return c;
    }
}

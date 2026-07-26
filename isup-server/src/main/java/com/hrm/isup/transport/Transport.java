package com.hrm.isup.transport;

import com.hrm.isup.model.Result;

/**
 * How ISAPI requests reach a device. Adapters are written against this, so the
 * same adapter works whether the device is reached over ISUP passthrough (device
 * behind NAT) or, in future, direct HTTP (device on the LAN).
 *
 * To add a new reach mechanism, implement this interface — no adapter changes.
 */
public interface Transport {
    Result get(String isapiPath);
    Result post(String isapiPath, String jsonBody);
    Result put(String isapiPath, String jsonBody);
    Result delete(String isapiPath, String jsonBody);

    /** True while the underlying connection/session is usable. */
    boolean isAlive();
}

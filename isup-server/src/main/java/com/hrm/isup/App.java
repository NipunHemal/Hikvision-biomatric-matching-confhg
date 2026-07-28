package com.hrm.isup;

import com.hrm.isup.api.ApiServer;
import com.hrm.isup.device.FingerprintSyncService;
import com.hrm.isup.server.IsupServer;

/**
 * Biometric device control hub — entry point.
 *
 *   Branch terminals ──ISUP──► IsupServer (CMS + alarm listeners)
 *                                   │ populates
 *                                   ▼
 *                              DeviceManager (deviceId → adapter)
 *                                   ▲ delegates
 *   HRM system ──────HTTP/JSON──► ApiServer
 *
 * Adapters abstract device model; transports abstract how the device is reached.
 * Adding a model = one adapter class; adding a reach mechanism = one transport.
 */
public final class App {
    public static void main(String[] args) throws Exception {
        IsupServer isup = new IsupServer();
        isup.start();   // native SDK: CMS + alarm listeners

        FingerprintSyncService sync = new FingerprintSyncService(isup.manager());

        try {
            new ApiServer(isup.manager(), sync, isup.eventSink()).start();
        } catch (Exception e) {
            isup.stop();
            throw e;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(isup::stop));
        System.out.println("Hub up. ISUP " + (isup.isAvailable() ? "ready" : "UNAVAILABLE (HTTP API only)")
                + ". Devices dial in over ISUP; HRM drives it over the HTTP API.");
        Thread.currentThread().join();
    }
}

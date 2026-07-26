package com.hrm.isup.device;

import com.hrm.isup.sdk.HCISUPCMS;
import com.hrm.isup.transport.IsupTransport;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The hub's live registry of connected devices, keyed by ISUP Device ID.
 *
 * On registration it builds a transport bound to the device's login session and
 * picks the adapter for the device's model — so every branch terminal is managed
 * through one uniform interface regardless of model.
 */
public final class DeviceManager {

    private volatile HCISUPCMS cms;   // null until the native ISUP SDK loads
    private final Map<String, ConnectedDevice> devices = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "model-detect");
        t.setDaemon(true);
        return t;
    });

    public DeviceManager() {}

    /** Set once the native ISUP SDK is loaded. Until then, no devices register. */
    public void setCms(HCISUPCMS cms) { this.cms = cms; }

    /** True when the ISUP stack is loaded and able to accept device registrations. */
    public boolean isReady() { return cms != null; }

    /** Called when a device comes online (ENUM_DEV_ON). */
    public ConnectedDevice online(String deviceId, int loginId) {
        if (cms == null) return null; // ISUP not loaded — cannot happen in practice
        ConnectedDevice existing = devices.get(deviceId);
        if (existing != null) {
            existing.transport.setLoginId(loginId);
            existing.online = true;
            existing.lastSeen = System.currentTimeMillis();
            System.out.println("[hub] device reconnected: " + deviceId);
            scheduleModelDetect(existing);
            return existing;
        }

        // Model detection needs an ISAPI passthrough, which is NOT ready inside
        // the registration callback (the CMS user session is still forming — a
        // passthrough here fails with error 47). Start with the generic adapter
        // and detect the model a few seconds later once the session is valid.
        IsupTransport tx = new IsupTransport(cms, loginId);
        ConnectedDevice dev = new ConnectedDevice(deviceId, "detecting",
                tx, new GenericIsapiAdapter(tx, "detecting"));
        devices.put(deviceId, dev);
        System.out.println("[hub] device online: " + deviceId + " (detecting model...)");
        scheduleModelDetect(dev);
        return dev;
    }

    /** Detect the model once the session is ready, then swap in its adapter. */
    private void scheduleModelDetect(ConnectedDevice dev) {
        scheduler.schedule(() -> {
            try {
                if (!dev.online) return;
                String model = detectModel(dev.transport);
                if (model != null && !model.equals("unknown")) {
                    dev.model = model;
                    dev.adapter = AdapterFactory.forModel(model, dev.transport);
                    System.out.println("[hub] " + dev.deviceId + " model=" + model
                            + " adapter=" + dev.adapter.getClass().getSimpleName());
                }
            } catch (Exception ignored) {
                // stays on the generic adapter, which works for standard ISAPI
            }
        }, 3, TimeUnit.SECONDS);
    }

    public void offline(String deviceId) {
        ConnectedDevice d = devices.get(deviceId);
        if (d != null) {
            d.online = false;
            d.transport.setLoginId(-1);
            System.out.println("[hub] device offline: " + deviceId);
        }
    }

    public ConnectedDevice get(String deviceId) {
        ConnectedDevice d = devices.get(deviceId);
        return (d != null && d.online) ? d : null;
    }

    public Collection<ConnectedDevice> all() {
        return devices.values();
    }

    private String detectModel(IsupTransport tx) {
        try {
            var r = tx.get("/ISAPI/System/deviceInfo?format=json");
            if (r.ok) {
                var m = java.util.regex.Pattern
                        .compile("\"model\"\\s*:\\s*\"([^\"]+)\"").matcher(r.body);
                if (m.find()) return m.group(1);
            }
        } catch (Exception ignored) {
            // registration-time query can fail; adapter fallback handles it
        }
        return "unknown";
    }
}

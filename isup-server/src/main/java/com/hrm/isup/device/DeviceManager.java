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
    private final com.hrm.isup.event.DeviceStatusNotifier status =
            new com.hrm.isup.event.DeviceStatusNotifier();
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
            status.online(deviceId);      // edge-triggered — no-ops if already online
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
        status.online(deviceId);
        scheduleModelDetect(dev);
        return dev;
    }

    /** Detect the model once the session is ready, then swap in its adapter. */
    private void scheduleModelDetect(ConnectedDevice dev) {
        scheduler.schedule(() -> {
            try {
                if (!dev.online) return;
                // Diagnostic: show the raw passthrough result for deviceInfo.
                var r = dev.transport.get("/ISAPI/System/deviceInfo?format=json");
                System.out.println("[hub] detect " + dev.deviceId + " ok=" + r.ok
                        + " reply=" + (r.body == null ? "" : r.body.substring(0, Math.min(120, r.body.length()))));
                if (r.ok) {
                    var m = java.util.regex.Pattern
                            .compile("\"model\"\\s*:\\s*\"([^\"]+)\"").matcher(r.body);
                    if (m.find()) {
                        String model = m.group(1);
                        dev.model = model;
                        dev.adapter = AdapterFactory.forModel(model, dev.transport);
                        System.out.println("[hub] " + dev.deviceId + " model=" + model
                                + " adapter=" + dev.adapter.getClass().getSimpleName());
                    }
                }
            } catch (Exception e) {
                System.out.println("[hub] detect " + dev.deviceId + " error: " + e.getMessage());
            }
        }, 3, TimeUnit.SECONDS);
    }

    public void offline(String deviceId) {
        ConnectedDevice d = devices.get(deviceId);
        if (d != null) {
            d.online = false;
            if (d.transport != null) d.transport.setLoginId(-1);
            System.out.println("[hub] device offline: " + deviceId);
            status.offline(deviceId);     // edge-triggered — no-ops if already offline
        }
    }

    // --- simulation (no ISUP, no hardware) ---

    /** Add or replace an in-memory simulated device. Comes online immediately. */
    public ConnectedDevice addSimulated(String deviceId, String model) {
        SimulatedDeviceAdapter a = new SimulatedDeviceAdapter(model);
        ConnectedDevice dev = new ConnectedDevice(deviceId, a.model(), null, a, true);
        devices.put(deviceId, dev);
        System.out.println("[hub] SIMULATED device added: " + deviceId + " (" + a.model() + ")");
        return dev;
    }

    /** Toggle a simulated device on/off (mimics power/network). */
    public boolean setSimOnline(String deviceId, boolean on) {
        ConnectedDevice d = devices.get(deviceId);
        if (d == null || !d.simulated) return false;
        d.online = on;
        d.lastSeen = System.currentTimeMillis();
        System.out.println("[hub] SIMULATED device " + deviceId + " -> " + (on ? "online" : "offline"));
        return true;
    }

    /** Remove any device from the registry (used for simulated teardown). */
    public boolean remove(String deviceId) {
        return devices.remove(deviceId) != null;
    }

    public ConnectedDevice get(String deviceId) {
        ConnectedDevice d = devices.get(deviceId);
        return (d != null && d.online) ? d : null;
    }

    public Collection<ConnectedDevice> all() {
        return devices.values();
    }

}

package com.hrm.isup.device;

import com.hrm.isup.transport.IsupTransport;

/** A device currently registered with the hub: its identity, session + adapter. */
public final class ConnectedDevice {
    public final String deviceId;
    public volatile String model;
    public final IsupTransport transport;
    public volatile DeviceAdapter adapter;
    public volatile boolean online;
    public volatile long lastSeen;

    public ConnectedDevice(String deviceId, String model, IsupTransport transport, DeviceAdapter adapter) {
        this.deviceId = deviceId;
        this.model = model;
        this.transport = transport;
        this.adapter = adapter;
        this.online = true;
        this.lastSeen = System.currentTimeMillis();
    }
}

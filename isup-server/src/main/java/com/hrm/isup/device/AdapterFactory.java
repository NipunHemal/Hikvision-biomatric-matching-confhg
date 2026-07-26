package com.hrm.isup.device;

import com.hrm.isup.transport.Transport;

/**
 * Chooses the right adapter for a device model. This is the single place to
 * register a new model — map its model string to its adapter class here.
 */
public final class AdapterFactory {

    public static DeviceAdapter forModel(String model, Transport tx) {
        if (model != null && model.toUpperCase().contains("K1T808")) {
            return new DsK1T808Adapter(tx);
        }
        // Add more models here:
        //   if (model.contains("K1T343")) return new DsK1T343Adapter(tx);
        return new GenericIsapiAdapter(tx, model);
    }

    private AdapterFactory() {}
}

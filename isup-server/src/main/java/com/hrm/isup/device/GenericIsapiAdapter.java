package com.hrm.isup.device;

import com.hrm.isup.transport.Transport;

/**
 * Fallback adapter for any ISAPI-speaking device whose model has no dedicated
 * adapter yet. Uses the standard ISAPI behaviour with default capabilities.
 */
public final class GenericIsapiAdapter extends AbstractIsapiAdapter {

    private final String model;

    public GenericIsapiAdapter(Transport tx, String model) {
        super(tx);
        this.model = model == null ? "generic-isapi" : model;
    }

    @Override public String model() { return model; }
}

package com.hrm.isup.event;

import com.hrm.isup.model.AccessEvent;

/**
 * Inbound adapter: turns ONE vendor's webhook payload into the hub's canonical
 * {@link AccessEvent}. The inbound mirror of {@code DeviceAdapter} — add support
 * for a new brand by implementing this and registering it in {@link WebhookGateway}.
 */
public interface WebhookParser {

    /** Vendor key used in the URL: {@code POST /webhook/{vendor}/{deviceCode}}. */
    String vendor();

    /**
     * Parse a raw request body into a canonical event, or null if it carries no
     * usable event. {@code deviceCode} comes from the URL (the payload usually
     * only has the device IP).
     */
    AccessEvent parse(byte[] body, String contentType, String deviceCode);
}

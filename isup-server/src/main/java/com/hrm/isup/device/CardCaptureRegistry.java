package com.hrm.isup.device;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

/**
 * Event-based card capture for devices that do not expose the synchronous
 * CaptureCardInfo ISAPI (e.g. DS-K1T808, which returns methodNotAllowed).
 *
 * The /card/capture request registers a waiter for a device; when the user taps a
 * card, the access event arrives on the alarm channel and its cardNo is handed to
 * the waiter. One capture per device at a time (the natural enrolment cadence).
 */
public final class CardCaptureRegistry {

    private static final java.util.Map<String, SynchronousQueue<String>> WAITERS =
            new ConcurrentHashMap<>();

    private CardCaptureRegistry() {}

    /** Block until a card is tapped on {@code deviceId} or the timeout elapses. */
    public static String await(String deviceId, long timeoutMs) throws InterruptedException {
        SynchronousQueue<String> q = new SynchronousQueue<>();
        WAITERS.put(deviceId, q);
        try {
            return q.poll(timeoutMs, TimeUnit.MILLISECONDS); // null on timeout
        } finally {
            WAITERS.remove(deviceId, q);
        }
    }

    /**
     * Hand a tapped card number to a pending waiter. Matches the event's device
     * first; if that misses (the event's serial may differ from the ISUP id) and
     * exactly one capture is pending, deliver to it. Returns true if consumed.
     */
    public static boolean offer(String deviceId, String cardNo) {
        SynchronousQueue<String> q = deviceId == null ? null : WAITERS.get(deviceId);
        if (q == null && WAITERS.size() == 1) {
            q = WAITERS.values().iterator().next();
        }
        return q != null && q.offer(cardNo);
    }

    public static boolean hasPending() { return !WAITERS.isEmpty(); }
}

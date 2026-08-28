package io.github.mekhontsev.magicdesk;

import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicLong;

/** Named non-polling delays used by protocols, retries, and input gestures. */
public final class RuntimeDelays {
    private static final AtomicLong DELAYS = new AtomicLong();
    private static volatile String sLastReason = "none";
    public enum Reason {
        INPUT_GESTURE,
        SUPERVISOR_BACKOFF,
        RECORDING_DRAIN,
        VENDOR_COMMAND_SETTLE,
        WATCHDOG_TICK,
        STREAM_HEARTBEAT
    }

    private RuntimeDelays() {
    }

    public static void pause(final Reason reason, final long delayMillis) {
        validate(reason, delayMillis);
        record(reason);
        SystemClock.sleep(delayMillis);
    }

    public static void pauseInterruptibly(
            final Reason reason,
            final long delayMillis) throws InterruptedException {
        validate(reason, delayMillis);
        record(reason);
        Thread.sleep(delayMillis);
    }

    static String diagnostics() {
        return "delays=" + DELAYS.get() + ", lastReason=" + sLastReason;
    }

    private static void validate(
            final Reason reason,
            final long delayMillis) {
        if (reason == null || delayMillis < 0L) {
            throw new IllegalArgumentException("invalid runtime delay");
        }
    }

    private static void record(final Reason reason) {
        DELAYS.incrementAndGet();
        sLastReason = reason.name();
    }
}

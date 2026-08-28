package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Explicit bounded polling for framework state that has no usable callback. */
public final class BoundedStateAwaiter {
    private static final AtomicLong REQUESTS = new AtomicLong();
    private static final AtomicLong TIMEOUTS = new AtomicLong();
    private static final AtomicLong POLL_PAUSES = new AtomicLong();
    private static volatile String sLastReason = "none";
    public enum Reason {
        TASK_APPEARANCE,
        TASK_DISPLAY,
        TASK_WINDOWING_MODE,
        TASK_BOUNDS,
        TASK_VISIBILITY,
        TASK_HIERARCHY,
        INPUT_FOCUS,
        DISPLAY_STATE,
        INPUT_DEVICE,
        TRANSITION_HEALTH,
        VENDOR_STATE
    }

    interface IoProbe<T> {
        T sample() throws IOException;
    }

    interface FrameworkProbe<T> {
        T sample() throws ReflectiveOperationException;
    }

    interface Probe<T> {
        T sample();
    }

    interface Condition<T> {
        boolean isSatisfied(T value);
    }

    private BoundedStateAwaiter() {
    }

    static <T> T awaitIo(
            final Reason reason,
            final long timeoutMillis,
            final long intervalMillis,
            final IoProbe<T> probe,
            final Condition<T> condition) throws IOException {
        validate(reason, timeoutMillis, intervalMillis, probe, condition);
        begin(reason);
        final long deadlineNanos = deadlineNanos(timeoutMillis);
        T value;
        do {
            value = probe.sample();
            if (condition.isSatisfied(value)) {
                complete(reason, false);
                return value;
            }
        } while (pauseUntilNextSample(deadlineNanos, intervalMillis));
        complete(reason, true);
        return value;
    }

    static <T> T awaitFramework(
            final Reason reason,
            final long timeoutMillis,
            final long intervalMillis,
            final FrameworkProbe<T> probe,
            final Condition<T> condition)
            throws ReflectiveOperationException {
        validate(reason, timeoutMillis, intervalMillis, probe, condition);
        begin(reason);
        final long deadlineNanos = deadlineNanos(timeoutMillis);
        T value;
        do {
            value = probe.sample();
            if (condition.isSatisfied(value)) {
                complete(reason, false);
                return value;
            }
        } while (pauseUntilNextSample(deadlineNanos, intervalMillis));
        complete(reason, true);
        return value;
    }

    static <T> T awaitUnchecked(
            final Reason reason,
            final long timeoutMillis,
            final long intervalMillis,
            final Probe<T> probe,
            final Condition<T> condition) {
        validate(reason, timeoutMillis, intervalMillis, probe, condition);
        begin(reason);
        final long deadlineNanos = deadlineNanos(timeoutMillis);
        T value;
        do {
            value = probe.sample();
            if (condition.isSatisfied(value)) {
                complete(reason, false);
                return value;
            }
        } while (pauseUntilNextSample(deadlineNanos, intervalMillis));
        complete(reason, true);
        return value;
    }

    public static void pause(final Reason reason, final long delayMillis) {
        if (reason == null || delayMillis < 0L) {
            throw new IllegalArgumentException("invalid state wait pause");
        }
        POLL_PAUSES.incrementAndGet();
        sLastReason = reason.name().toLowerCase();
        sleepUninterruptibly(delayMillis);
    }

    public static void pauseInterruptibly(
            final Reason reason,
            final long delayMillis) throws InterruptedException {
        if (reason == null || delayMillis < 0L) {
            throw new IllegalArgumentException("invalid state wait pause");
        }
        POLL_PAUSES.incrementAndGet();
        sLastReason = reason.name().toLowerCase();
        Thread.sleep(delayMillis);
    }

    static String diagnostics() {
        return "requests=" + REQUESTS.get()
                + ", timeouts=" + TIMEOUTS.get()
                + ", pollPauses=" + POLL_PAUSES.get()
                + ", lastReason=" + sLastReason;
    }

    private static boolean pauseUntilNextSample(
            final long deadlineNanos,
            final long intervalMillis) {
        final long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0L) {
            return false;
        }
        sleepUninterruptibly(Math.min(
                intervalMillis,
                Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos))));
        return true;
    }

    private static long deadlineNanos(final long timeoutMillis) {
        return System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }

    private static void sleepUninterruptibly(final long delayMillis) {
        boolean interrupted = false;
        final long deadlineNanos = deadlineNanos(delayMillis);
        long remainingNanos;
        do {
            remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                break;
            }
            try {
                final long millis = TimeUnit.NANOSECONDS.toMillis(
                        remainingNanos);
                final int nanos = (int) (remainingNanos
                        - TimeUnit.MILLISECONDS.toNanos(millis));
                Thread.sleep(millis, nanos);
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        } while (true);
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void validate(
            final Reason reason,
            final long timeoutMillis,
            final long intervalMillis,
            final Object probe,
            final Object condition) {
        if (reason == null || timeoutMillis < 0L || intervalMillis <= 0L
                || probe == null || condition == null) {
            throw new IllegalArgumentException("invalid bounded wait");
        }
    }

    private static void begin(final Reason reason) {
        REQUESTS.incrementAndGet();
        sLastReason = reason.name().toLowerCase();
    }

    private static void complete(
            final Reason reason,
            final boolean timedOut) {
        if (timedOut) {
            TIMEOUTS.incrementAndGet();
        }
        sLastReason = reason.name().toLowerCase();
    }
}

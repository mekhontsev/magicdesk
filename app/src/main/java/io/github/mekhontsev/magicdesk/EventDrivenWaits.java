package io.github.mekhontsev.magicdesk;

import java.util.concurrent.atomic.AtomicLong;

/** Classified monitor waits that wake from an explicit runtime signal. */
public final class EventDrivenWaits {
    public enum Reason {
        FRAMEWORK_OBSERVER_ACTIVATION,
        FRAMEWORK_OBSERVER_RESAMPLE,
        TASK_CREATION,
        ACTIVITY_LAUNCH_RESULT,
        PTY_RESPONSE,
        AUTOMATION_EVENT,
        INPUT_WINDOW_COMMIT,
        INPUT_FOCUS_RELAYOUT,
        INPUT_WORKER_STOP,
        INPUT_CAPTURE_RELEASE,
        INPUT_DIAGNOSTICS,
        TERMINAL_REGISTRATION,
        SERVICE_BINDING
    }

    private static final AtomicLong WAITS = new AtomicLong();
    private static volatile String sLastReason = "none";

    private EventDrivenWaits() {
    }

    public static void await(
            final Object monitor,
            final Reason reason) throws InterruptedException {
        validate(monitor, reason);
        record(reason);
        monitor.wait();
    }

    public static void await(
            final Object monitor,
            final Reason reason,
            final long timeoutMillis) throws InterruptedException {
        validate(monitor, reason);
        if (timeoutMillis <= 0L) {
            throw new IllegalArgumentException(
                    "event wait timeout must be positive");
        }
        record(reason);
        monitor.wait(timeoutMillis);
    }

    static String diagnostics() {
        return "waits=" + WAITS.get() + ", lastReason=" + sLastReason;
    }

    private static void validate(
            final Object monitor,
            final Reason reason) {
        if (monitor == null || reason == null) {
            throw new IllegalArgumentException("invalid event wait");
        }
        if (!Thread.holdsLock(monitor)) {
            throw new IllegalMonitorStateException(
                    "event wait monitor is not owned");
        }
    }

    private static void record(final Reason reason) {
        WAITS.incrementAndGet();
        sLastReason = reason.name();
    }
}

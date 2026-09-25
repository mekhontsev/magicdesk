package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/** Confirms input focus only after committed input-window topology changes. */
final class InputFocusCommitAwaiter {
    interface EventSource {
        long checkpoint();
        boolean isAvailable();
        boolean awaitChangeAfter(long checkpoint, long timeoutMillis,
                BooleanSupplier cancelled)
                throws InterruptedException;
    }

    interface FocusProbe {
        boolean isConsistent() throws IOException, InterruptedException;
    }

    private static final AtomicLong ATTEMPTS = new AtomicLong();
    private static final AtomicLong SNAPSHOTS = new AtomicLong();
    private static final AtomicLong EVENT_ADVANCES = new AtomicLong();
    private static final AtomicLong CONVERGED = new AtomicLong();
    private static final AtomicLong EXPIRED = new AtomicLong();
    private static final AtomicLong CANCELLED = new AtomicLong();
    private static final AtomicLong EVENT_SOURCE_UNAVAILABLE = new AtomicLong();

    private InputFocusCommitAwaiter() {
    }

    static boolean await(
            final EventSource events,
            final long initialGeneration,
            final long timeoutMillis,
            final BooleanSupplier cancelled,
            final FocusProbe probe) throws IOException, InterruptedException {
        if (probe == null || cancelled == null || timeoutMillis <= 0L) {
            throw new IllegalArgumentException("invalid focus commit wait");
        }
        ATTEMPTS.incrementAndGet();
        if (isCancelled(cancelled)) return false;
        final boolean initiallyFocused = probe(probe);
        if (isCancelled(cancelled)) return false;
        if (initiallyFocused) {
            CONVERGED.incrementAndGet();
            return true;
        }
        if (events == null || !events.isAvailable()) {
            EVENT_SOURCE_UNAVAILABLE.incrementAndGet();
            return false;
        }

        final long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        long generation = initialGeneration;
        while (true) {
            if (isCancelled(cancelled)) return false;
            final long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                EXPIRED.incrementAndGet();
                return probe(probe) && !isCancelled(cancelled);
            }
            final boolean changed = events.awaitChangeAfter(
                    generation,
                    Math.max(1L,
                            TimeUnit.NANOSECONDS.toMillis(remainingNanos)),
                    cancelled);
            if (isCancelled(cancelled)) return false;
            if (!changed) {
                EXPIRED.incrementAndGet();
                // Cover an event racing the event-source deadline without
                // adding a timer or another hierarchy mutation.
                return probe(probe) && !isCancelled(cancelled);
            }
            EVENT_ADVANCES.incrementAndGet();
            generation = events.checkpoint();
            final boolean focused = probe(probe);
            if (isCancelled(cancelled)) return false;
            if (focused) {
                CONVERGED.incrementAndGet();
                return true;
            }
        }
    }

    static String diagnostics() {
        return "attempts=" + ATTEMPTS.get()
                + ", snapshots=" + SNAPSHOTS.get()
                + ", eventAdvances=" + EVENT_ADVANCES.get()
                + ", converged=" + CONVERGED.get()
                + ", expired=" + EXPIRED.get()
                + ", cancelled=" + CANCELLED.get()
                + ", eventSourceUnavailable="
                + EVENT_SOURCE_UNAVAILABLE.get();
    }

    private static boolean isCancelled(final BooleanSupplier cancelled) {
        if (!cancelled.getAsBoolean()) return false;
        CANCELLED.incrementAndGet();
        return true;
    }

    private static boolean probe(final FocusProbe probe)
            throws IOException, InterruptedException {
        SNAPSHOTS.incrementAndGet();
        return probe.isConsistent();
    }
}

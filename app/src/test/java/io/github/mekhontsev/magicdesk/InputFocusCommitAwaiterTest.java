package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public final class InputFocusCommitAwaiterTest {
    @Test
    public void returnsImmediatelyForAlreadyConsistentFocus() throws Exception {
        final FakeEvents events = new FakeEvents(true, 1);
        final AtomicInteger probes = new AtomicInteger();

        assertTrue(InputFocusCommitAwaiter.await(
                events, 0L, 100L, () -> false,
                () -> probes.incrementAndGet() == 1));
        assertEquals(1, probes.get());
        assertEquals(0, events.waits);
    }

    @Test
    public void followsSuccessiveCommitEventsUntilSourcesAgree()
            throws Exception {
        final FakeEvents events = new FakeEvents(true, 2);
        final AtomicInteger probes = new AtomicInteger();

        assertTrue(InputFocusCommitAwaiter.await(
                events, 0L, 100L, () -> false,
                () -> probes.incrementAndGet() >= 3));
        assertEquals(3, probes.get());
        assertEquals(2, events.waits);
    }

    @Test
    public void doesNotPollWithoutFrameworkEvents() throws Exception {
        final FakeEvents events = new FakeEvents(false, 0);
        final AtomicInteger probes = new AtomicInteger();

        assertFalse(InputFocusCommitAwaiter.await(
                events, 0L, 100L, () -> false,
                () -> {
                    probes.incrementAndGet();
                    return false;
                }));
        assertEquals(1, probes.get());
        assertEquals(0, events.waits);
    }

    @Test
    public void takesFinalSnapshotWhenEventDeadlineWins() throws Exception {
        final FakeEvents events = new FakeEvents(true, 0);
        final AtomicInteger probes = new AtomicInteger();

        assertTrue(InputFocusCommitAwaiter.await(
                events, 0L, 100L, () -> false,
                () -> probes.incrementAndGet() == 2));
        assertEquals(2, probes.get());
        assertEquals(1, events.waits);
    }

    @Test
    public void removedTargetDoesNotProbeOrWait() throws Exception {
        final FakeEvents events = new FakeEvents(true, 1);
        final AtomicInteger probes = new AtomicInteger();
        assertFalse(InputFocusCommitAwaiter.await(events, 0L, 10_000L,
                () -> true, () -> probes.incrementAndGet() > 0));
        assertEquals(0, probes.get());
        assertEquals(0, events.waits);
    }

    @Test
    public void removalDuringWaitIsNotAnExpiryOrFinalFocusSuccess() throws Exception {
        final FakeEvents events = new FakeEvents(true, 0);
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicInteger probes = new AtomicInteger();
        events.onWait = () -> cancelled.set(true);
        assertFalse(InputFocusCommitAwaiter.await(events, 0L, 10_000L,
                cancelled::get, () -> probes.incrementAndGet() > 1));
        assertEquals(1, events.waits);
        assertEquals(1, probes.get());
    }

    @Test
    public void removalRacingACommitCannotConfirmTheTask() throws Exception {
        final FakeEvents events = new FakeEvents(true, 1);
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicInteger probes = new AtomicInteger();
        assertFalse(InputFocusCommitAwaiter.await(events, 0L, 10_000L,
                cancelled::get, () -> {
                    if (probes.incrementAndGet() == 1) return false;
                    cancelled.set(true);
                    return true;
                }));
        assertEquals(1, events.waits);
        assertEquals(2, probes.get());
    }

    private static final class FakeEvents
            implements InputFocusCommitAwaiter.EventSource {
        private final boolean available;
        private int remainingAdvances;
        private long generation;
        int waits;
        Runnable onWait = () -> {};

        FakeEvents(final boolean available, final int remainingAdvances) {
            this.available = available;
            this.remainingAdvances = remainingAdvances;
        }

        @Override
        public long checkpoint() {
            return generation;
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public boolean awaitChangeAfter(
                final long checkpoint,
                final long timeoutMillis,
                final BooleanSupplier cancelled) {
            waits++;
            onWait.run();
            if (remainingAdvances <= 0) {
                return false;
            }
            remainingAdvances--;
            generation = Math.max(generation, checkpoint) + 1L;
            return true;
        }
    }
}

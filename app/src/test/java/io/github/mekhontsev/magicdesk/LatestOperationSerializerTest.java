package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class LatestOperationSerializerTest {
    @Test
    public void supersededQueuedOperationDoesNotReplaceLatestState()
            throws Exception {
        final LatestOperationSerializer serializer =
                new LatestOperationSerializer();
        final LatestOperationSerializer.Ticket stale = serializer.supersede();
        final LatestOperationSerializer.Ticket latest = serializer.supersede();
        final List<String> states = new ArrayList<>();

        assertTrue(serializer.executeIfCurrent(
                latest, () -> states.add("phone")));
        assertFalse(serializer.executeIfCurrent(
                stale, () -> states.add("clear")));
        assertEquals(List.of("phone"), states);
    }

    @Test
    public void newerOperationRunsAfterAlreadyStartedCleanup()
            throws Exception {
        final LatestOperationSerializer serializer =
                new LatestOperationSerializer();
        final LatestOperationSerializer.Ticket cleanup = serializer.supersede();
        final CountDownLatch cleanupStarted = new CountDownLatch(1);
        final CountDownLatch releaseCleanup = new CountDownLatch(1);
        final List<String> states = new ArrayList<>();
        final ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            final Future<Boolean> clearing = executor.submit(() ->
                    serializer.executeIfCurrent(cleanup, () -> {
                        cleanupStarted.countDown();
                        assertTrue(releaseCleanup.await(2, TimeUnit.SECONDS));
                        states.add("clear");
                    }));
            assertTrue(cleanupStarted.await(2, TimeUnit.SECONDS));

            final LatestOperationSerializer.Ticket latest =
                    serializer.supersede();
            final Future<Boolean> configuring = executor.submit(() ->
                    serializer.executeIfCurrent(
                            latest, () -> states.add("phone")));
            releaseCleanup.countDown();

            assertTrue(clearing.get(2, TimeUnit.SECONDS));
            assertTrue(configuring.get(2, TimeUnit.SECONDS));
            assertEquals(List.of("clear", "phone"), states);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void invalidationCancelsQueuedOperation() throws Exception {
        final LatestOperationSerializer serializer =
                new LatestOperationSerializer();
        final LatestOperationSerializer.Ticket ticket = serializer.supersede();
        serializer.invalidate();

        assertFalse(serializer.executeIfCurrent(ticket, () -> { }));
    }
}

package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Test;

public final class AndroidActivityResultLifecycleTest {
    private final List<String> requests = new ArrayList<>();

    @After
    public void discardRequests() {
        for (final String id : requests) {
            AndroidActivityResultStore.discard(id);
        }
    }

    @Test
    public void subscriptionDoesNotBlockAndSeesPublishedCompletion() throws Exception {
        final String id = begin();
        final List<String> seen = new ArrayList<>();
        final var ready = AndroidActivityResultStore.whenReady(id, () -> seen.add(state(id)));
        assertFalse(ready.isDone());
        assertEquals("pending", state(id));
        AndroidActivityResultStore.complete(null, id, -1, null);
        ready.join();
        assertEquals(List.of("completed"), seen);
        assertEquals(-1, AndroidActivityResultStore.get(id, 0L, false).getInt("resultCode"));
    }

    @Test
    public void completionBeforeSubscriptionIsNotLost() throws Exception {
        final String id = begin();
        AndroidActivityResultStore.complete(null, id, 0, null);
        final AtomicInteger calls = new AtomicInteger();
        AndroidActivityResultStore.whenReady(id, calls::incrementAndGet).join();
        assertEquals(1, calls.get());
    }

    @Test
    public void failedLaunchAlsoNotifiesAndRetainsItsError() throws Exception {
        final String id = begin();
        final List<String> seen = new ArrayList<>();
        final var ready = AndroidActivityResultStore.whenReady(id, () -> seen.add(state(id)));
        AndroidActivityResultStore.fail(id, new IllegalStateException("launch failed"));
        ready.join();
        assertEquals(List.of("failed"), seen);
        assertEquals("launch failed", AndroidActivityResultStore.get(id, 0L, false).getString("error"));
    }

    @Test
    public void ownerDisposalNotifiesAndRejectsLateResults() throws Exception {
        final String id = begin();
        final List<String> seen = new ArrayList<>();
        final var ready = AndroidActivityResultStore.whenReady(id, () -> seen.add(state(id)));
        AndroidActivityResultStore.discard(id);
        ready.join();
        AndroidActivityResultStore.complete(null, id, -1, null);
        AndroidActivityResultStore.fail(id, new IllegalStateException("late failure"));
        AndroidActivityResultStore.discard(id);
        assertEquals(List.of("not_found"), seen);
        assertEquals("not_found", state(id));
    }

    @Test
    public void evictionNotifiesAnUnfinishedSubscriber() throws Exception {
        final String id = begin();
        final List<String> seen = new ArrayList<>();
        final var ready = AndroidActivityResultStore.whenReady(id, () -> seen.add(state(id)));
        for (int index = 0; index < 64; index++) {
            begin();
        }
        ready.join();
        assertEquals(List.of("not_found"), seen);
    }

    @Test
    public void evictionReleaseFailureDoesNotOrphanTheNewRequest() throws Exception {
        final String id = begin();
        final AtomicInteger releases = new AtomicInteger();
        final var permissions = new PersistedUriPermissions(new PersistedUriPermissions.Access() {
            @Override public void take(final String uri, final int flags) { }
            @Override public void release(final String uri, final int flags) {
                releases.incrementAndGet();
                throw new IllegalStateException("release unavailable");
            }
        });
        AndroidActivityResultStore.complete(null, id, -1, null);
        final var entriesField = AndroidActivityResultStore.class.getDeclaredField("ENTRIES");
        entriesField.setAccessible(true);
        @SuppressWarnings("unchecked")
        final Map<String, AndroidActivityResultStore.Entry> entries =
                (Map<String, AndroidActivityResultStore.Entry>) entriesField.get(null);
        entries.put(id, AndroidActivityResultStore.Entry.completed(id, -1, null,
                List.of(permissions.acquire("content://owned", 1))));
        String current = "";
        for (int index = 0; index < 64; index++) {
            current = begin();
        }
        assertEquals(1, releases.get());
        assertEquals("not_found", state(id));
        assertEquals("pending", state(current));
        AndroidActivityResultStore.complete(null, current, -1, null);
        assertEquals("completed", state(current));
    }

    @Test
    public void missingRequestNotifiesImmediately() {
        final List<String> seen = new ArrayList<>();
        AndroidActivityResultStore.whenReady("absent-request", () -> seen.add(state("absent-request")))
                .join();
        assertEquals(List.of("not_found"), seen);
    }

    @Test
    public void consumingPendingMetadataDoesNotCancelTheSelection() throws Exception {
        final String id = begin();
        final var ready = AndroidActivityResultStore.whenReady(id, () -> { });
        assertEquals("pending", AndroidActivityResultStore.get(id, 0L, true).getString("state"));
        assertFalse(ready.isDone());
        assertEquals("pending", state(id));
        AndroidActivityResultStore.complete(null, id, -1, null);
        ready.join();
        assertTrue(AndroidActivityResultStore.get(id, 0L, true).getBoolean("consumed"));
        assertEquals("not_found", state(id));
    }

    @Test
    public void duplicateTerminalCallbacksNotifyOnlyOnce() throws Exception {
        final String id = begin();
        final AtomicInteger calls = new AtomicInteger();
        final var ready = AndroidActivityResultStore.whenReady(id, calls::incrementAndGet);
        AndroidActivityResultStore.complete(null, id, -1, null);
        AndroidActivityResultStore.complete(null, id, 0, null);
        AndroidActivityResultStore.fail(id, new IllegalStateException("late failure"));
        ready.join();
        assertEquals(1, calls.get());
        assertEquals(-1, AndroidActivityResultStore.get(id, 0L, false).getInt("resultCode"));
    }

    @Test
    public void subscriberFailureCannotPreventAnotherSubscriberOrPublication() throws Exception {
        final String id = begin();
        final var failed = AndroidActivityResultStore.whenReady(id, () -> {
            throw new IllegalArgumentException("subscriber failed");
        });
        final AtomicInteger calls = new AtomicInteger();
        final var good = AndroidActivityResultStore.whenReady(id, calls::incrementAndGet);
        AndroidActivityResultStore.complete(null, id, -1, null);
        good.join();
        assertThrows(CompletionException.class, failed::join);
        assertEquals(1, calls.get());
        assertEquals("completed", state(id));
    }

    @Test
    public void cancellingOneNotificationDoesNotCancelTheRequest() throws Exception {
        final String id = begin();
        final var cancelled = AndroidActivityResultStore.whenReady(id, () -> {
            throw new AssertionError("cancelled notification");
        });
        cancelled.cancel(false);
        final AtomicInteger calls = new AtomicInteger();
        final var good = AndroidActivityResultStore.whenReady(id, calls::incrementAndGet);
        AndroidActivityResultStore.complete(null, id, -1, null);
        good.join();
        assertEquals(1, calls.get());
        assertEquals("completed", state(id));
    }

    @Test
    public void pendingResultCannotBeClaimed() throws Exception {
        final String id = begin();
        assertNull(AndroidActivityResultStore.claim(id));
        assertEquals("pending", state(id));
    }

    @Test
    public void claimedResultSurvivesRegistryEvictionAndLateCallbacks() throws Exception {
        final String id = begin();
        AndroidActivityResultStore.complete(null, id, -1, null);
        try (final var owned = AndroidActivityResultStore.claim(id)) {
            assertNotNull(owned);
            assertEquals("not_found", state(id));
            AndroidActivityResultStore.discard(id);
            AndroidActivityResultStore.complete(null, id, 0, null);
            for (int index = 0; index < 65; index++) {
                begin();
            }
            assertEquals(-1, owned.toJson().getInt("resultCode"));
            assertNull(AndroidActivityResultStore.claim(id));
        }
    }

    @Test
    public void onlyOneConsumerCanClaimAResult() throws Exception {
        final String id = begin();
        AndroidActivityResultStore.complete(null, id, -1, null);
        final var workers = Executors.newFixedThreadPool(2);
        try {
            final var first = workers.submit(() -> AndroidActivityResultStore.claim(id));
            final var second = workers.submit(() -> AndroidActivityResultStore.claim(id));
            try (final var a = first.get(2, TimeUnit.SECONDS);
                    final var b = second.get(2, TimeUnit.SECONDS)) {
                assertTrue((a == null) != (b == null));
                assertEquals("not_found", state(id));
            }
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test(timeout = 5_000)
    public void callbackRunsWithoutHoldingTheRegistryLock() throws Exception {
        final String id = begin();
        final var ready = AndroidActivityResultStore.whenReady(id, () -> {
            final FutureTask<String> read = new FutureTask<>(() -> state(id));
            final Thread reader = new Thread(read);
            reader.start();
            try {
                assertEquals("completed", read.get(2, TimeUnit.SECONDS));
            } catch (Exception error) {
                throw new AssertionError(error);
            } finally {
                try {
                    reader.join(2_000L);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        AndroidActivityResultStore.complete(null, id, -1, null);
        ready.join();
    }

    private String begin() throws Exception {
        final String id = AndroidActivityResultStore.begin(null);
        requests.add(id);
        return id;
    }

    private static String state(final String id) {
        try {
            return AndroidActivityResultStore.get(id, 0L, false).getString("state");
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}

package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class AndroidActivityResultStoreTest {
    @Test
    public void failedReleaseStillAttemptsEveryGrantAndPreservesTheFailures() throws Exception {
        final List<String> released = new ArrayList<>();
        final IllegalStateException first = new IllegalStateException("first release failed");
        final IllegalStateException last = new IllegalStateException("last release failed");
        final var permissions = new PersistedUriPermissions(new PersistedUriPermissions.Access() {
            @Override public void take(final String uri, final int flags) { }
            @Override public void release(final String uri, final int flags) {
                released.add(uri);
                if (uri.equals("content://first")) throw first;
                if (uri.equals("content://last")) throw last;
            }
        });
        final var owned = new AndroidActivityResultStore.ClaimedResult(
                AndroidActivityResultStore.Entry.completed("id", -1, data(), List.of(
                        permissions.acquire("content://first", 1),
                        permissions.acquire("content://middle", 1),
                        permissions.acquire("content://last", 1))));

        try (final var scope = new ContentRequestScope(Runnable::run)) {
            final var completion = scope.submit(cancelled -> "imported", owned::close).join();
            assertEquals("imported", completion.value);
            assertSame(first, completion.failure);
            assertEquals(1, first.getSuppressed().length);
            assertSame(last, first.getSuppressed()[0]);
        }
        owned.close();
        assertEquals(List.of("content://first", "content://middle", "content://last"), released);
    }

    @Test
    public void repeatedReleaseExceptionCannotSelfSuppressOrSkipTheFinalGrant() throws Exception {
        final AtomicInteger releases = new AtomicInteger();
        final IllegalStateException failure = new IllegalStateException("release unavailable");
        final var permissions = new PersistedUriPermissions(new PersistedUriPermissions.Access() {
            @Override public void take(final String uri, final int flags) { }
            @Override public void release(final String uri, final int flags) {
                if (releases.incrementAndGet() < 3) throw failure;
            }
        });
        final var owned = new AndroidActivityResultStore.ClaimedResult(
                AndroidActivityResultStore.Entry.completed("id", -1, data(), List.of(
                        permissions.acquire("content://first", 1),
                        permissions.acquire("content://second", 1),
                        permissions.acquire("content://third", 1))));
        assertSame(failure, assertThrows(IllegalStateException.class, owned::close));
        assertEquals(3, releases.get());
        assertEquals(0, failure.getSuppressed().length);
        owned.close();
        assertEquals(3, releases.get());
    }

    @Test
    public void discardedQueuedImportClosesItsClaimOnce() throws Exception {
        final AtomicInteger releases = new AtomicInteger();
        final var owned = ownedResult(releases);
        final List<Runnable> queue = new ArrayList<>();
        final var scope = new ContentRequestScope(queue::add);
        final var result = scope.submit(cancelled -> {
            throw new AssertionError("closed queued import must not start");
        }, owned::close);
        scope.close();
        for (final Runnable task : queue) {
            task.run();
        }
        owned.close();
        assertTrue(result.join().failure instanceof InterruptedIOException);
        assertNull(result.join().value);
        assertEquals(1, releases.get());
    }

    @Test
    public void runningImportRetainsClaimUntilItLeavesProviderIo() throws Exception {
        final AtomicInteger releases = new AtomicInteger();
        final var owned = ownedResult(releases);
        final var worker = Executors.newSingleThreadExecutor();
        final var scope = new ContentRequestScope(worker);
        final var reading = new CountDownLatch(1);
        final var finishRead = new CountDownLatch(1);
        try {
            final var result = scope.submit(cancelled -> {
                reading.countDown();
                assertTrue(finishRead.await(5, TimeUnit.SECONDS));
                ContentStreamCopy.checkCancelled(cancelled);
                return "unreachable";
            }, owned::close);
            assertTrue(reading.await(5, TimeUnit.SECONDS));
            scope.close();
            assertFalse(result.isDone());
            assertEquals(0, releases.get());
            finishRead.countDown();
            assertTrue(result.get(5, TimeUnit.SECONDS).failure instanceof InterruptedIOException);
            assertNull(result.join().value);
            assertEquals(1, releases.get());
        } finally {
            finishRead.countDown();
            scope.close();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            owned.close();
        }
    }

    private static AndroidActivityResultStore.ClaimedResult ownedResult(
            final AtomicInteger releases) throws Exception {
        final var permissions = new PersistedUriPermissions(new PersistedUriPermissions.Access() {
            @Override public void take(final String uri, final int flags) { }
            @Override public void release(final String uri, final int flags) { releases.incrementAndGet(); }
        });
        return new AndroidActivityResultStore.ClaimedResult(
                AndroidActivityResultStore.Entry.completed("id", -1, data(),
                        List.of(permissions.acquire("content://owned", 1))));
    }

    @Test
    public void entryOwnsItsDataInsteadOfRetainingTheProducersObjects() throws Exception {
        final JSONObject data = data();
        final var entry = AndroidActivityResultStore.Entry.completed("id", -1, data, List.of());
        data.put("dataUri", "content://changed");
        data.getJSONObject("extras").put("name", "changed");
        data.getJSONArray("clipUris").put("content://added");
        assertOriginal(entry.toJson().getJSONObject("data"));
    }

    @Test
    public void changingAReadCannotChangeTheNextRead() throws Exception {
        final var entry = AndroidActivityResultStore.Entry.completed("id", -1, data(), List.of());
        final JSONObject first = entry.toJson();
        first.put("state", "failed");
        first.getJSONObject("data").getJSONObject("extras").put("name", "changed");
        first.getJSONObject("data").getJSONArray("clipUris").put("content://added");
        final JSONObject second = entry.toJson();
        assertEquals("completed", second.getString("state"));
        assertOriginal(second.getJSONObject("data"));
    }

    @Test
    public void twoReadersOwnIndependentNestedData() throws Exception {
        final var entry = AndroidActivityResultStore.Entry.completed("id", -1, data(), List.of());
        final JSONObject first = entry.toJson().getJSONObject("data");
        final JSONObject second = entry.toJson().getJSONObject("data");
        first.getJSONObject("extras").put("name", "first");
        second.getJSONArray("clipUris").put("content://second");
        assertEquals("original", second.getJSONObject("extras").getString("name"));
        assertEquals(1, first.getJSONArray("clipUris").length());
    }

    @Test
    public void pendingAndFailedStatesAlsoHaveIndependentData() throws Exception {
        final var pending = AndroidActivityResultStore.Entry.pending("pending", data());
        assertTrue(pending.isPending());
        pending.toJson().getJSONObject("data").put("dataUri", "content://changed");
        assertOriginal(pending.toJson().getJSONObject("data"));
        assertFalse(pending.toJson().has("resultCode"));

        final var failed = AndroidActivityResultStore.Entry.failed("failed", "could not read result");
        failed.toJson().getJSONObject("data").put("extra", "changed");
        assertEquals(0, failed.toJson().getJSONObject("data").length());
        assertEquals("could not read result", failed.toJson().getString("error"));
        assertFalse(failed.isPending());
    }

    @Test
    public void invalidJsonCannotBecomeAPartialStoredResult() {
        final JSONObject invalid = new JSONObject() {
            @Override public String toString() { return "{"; }
        };
        assertThrows(IllegalArgumentException.class,
                () -> AndroidActivityResultStore.Entry.completed("id", -1, invalid, List.of()));
    }

    @Test
    public void jsonChangesCannotChangeGrantOwnership() throws Exception {
        final List<String> released = new ArrayList<>();
        final var permissions = new PersistedUriPermissions(new PersistedUriPermissions.Access() {
            @Override public void take(final String uri, final int flags) { }
            @Override public void release(final String uri, final int flags) { released.add(uri); }
        });
        final var grant = permissions.acquire("content://owned", 1);
        final List<PersistedUriPermissions.Grant> grants = new ArrayList<>(List.of(grant));
        final var entry = AndroidActivityResultStore.Entry.completed("id", -1, data(), grants);
        grants.clear();
        entry.toJson().getJSONObject("data").put("persistedUris", new JSONArray());
        assertEquals(List.of(grant), entry.grants);
        assertTrue(entry.grants.get(0).release());
        assertEquals(List.of("content://owned"), released);
        assertThrows(UnsupportedOperationException.class, () -> entry.grants.clear());
    }

    private static JSONObject data() throws Exception {
        return new JSONObject().put("dataUri", "content://original")
                .put("clipUris", new JSONArray().put("content://clip"))
                .put("extras", new JSONObject().put("name", "original"));
    }

    private static void assertOriginal(final JSONObject data) throws Exception {
        assertEquals("content://original", data.getString("dataUri"));
        assertEquals("original", data.getJSONObject("extras").getString("name"));
        assertEquals(1, data.getJSONArray("clipUris").length());
        assertEquals("content://clip", data.getJSONArray("clipUris").getString(0));
    }
}

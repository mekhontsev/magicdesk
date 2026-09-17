package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ApplicationCatalogSourceTest {
    private static final class Source {
        final List<ApplicationCatalogSource.Completion<String>> requests = new ArrayList<>();
        int changes;
        final ApplicationCatalogSource<String> source = new ApplicationCatalogSource<>(
                requests::add, () -> changes++);
        void succeed(int request, String... entries) { requests.get(request).complete(List.of(entries), ""); }
        void fail(int request) { requests.get(request).complete(List.of(), "unavailable"); }
    }

    @Test public void firstReadIsExplicitlyLoadingAndReopenReusesImmutableResult() {
        final var test = new Source();
        assertFalse(test.source.snapshot().ready());
        test.source.ensureLoaded();
        assertTrue(test.source.snapshot().loading());
        assertFalse(test.source.snapshot().ready());
        test.succeed(0, "Android app");
        final var entries = test.source.snapshot().entries();
        assertTrue(test.source.snapshot().ready());
        assertFalse(test.source.snapshot().loading());
        assertThrows(UnsupportedOperationException.class, () -> entries.add("other"));
        test.source.ensureLoaded();
        assertEquals(1, test.requests.size());
        assertSame(entries, test.source.snapshot().entries());
    }

    @Test public void refreshKeepsLastGoodResultAndJoinsConcurrentReaders() {
        final var test = new Source();
        test.source.ensureLoaded();
        test.succeed(0, "old");
        final var entries = test.source.snapshot().entries();
        final List<ApplicationCatalogSource.Snapshot<String>> replies = new ArrayList<>();
        test.source.refresh(replies::add);
        test.source.refresh(replies::add);
        assertEquals(2, test.requests.size());
        assertSame(entries, test.source.snapshot().entries());
        assertTrue(test.source.snapshot().ready());
        test.succeed(1, "new");
        assertEquals(2, replies.size());
        assertSame(replies.get(0), replies.get(1));
        assertEquals(List.of("new"), replies.get(0).entries());
    }

    @Test public void failedRefreshRetainsCacheAndNextOpenRetriesWithoutAnAutomaticLoop() {
        final var test = new Source();
        test.source.ensureLoaded();
        test.succeed(0, "old");
        final var entries = test.source.snapshot().entries();
        test.source.refresh(null);
        test.fail(1);
        assertEquals(2, test.requests.size());
        assertSame(entries, test.source.snapshot().entries());
        assertTrue(test.source.snapshot().ready());
        assertEquals("unavailable", test.source.snapshot().error());
        test.source.ensureLoaded();
        assertEquals(3, test.requests.size());
        test.succeed(2, "recovered");
        assertEquals("", test.source.snapshot().error());
    }

    @Test public void emptySuccessfulCatalogIsNotAnUninitializedOrFailedCatalog() {
        final var test = new Source();
        test.source.ensureLoaded();
        test.fail(0);
        assertFalse(test.source.snapshot().ready());
        assertFalse(test.source.snapshot().loading());
        test.source.ensureLoaded();
        test.succeed(1);
        assertTrue(test.source.snapshot().ready());
        assertTrue(test.source.snapshot().entries().isEmpty());
    }

    @Test public void packageChangesDuringDiscoveryDiscardStaleResultsAndCoalesceRereads() {
        final var test = new Source();
        final List<ApplicationCatalogSource.Snapshot<String>> replies = new ArrayList<>();
        test.source.refresh(replies::add);
        test.source.invalidate();
        test.source.invalidate();
        test.source.ensureLoaded();
        assertEquals(1, test.requests.size());
        test.succeed(0, "uninstalled");
        assertFalse(test.source.snapshot().ready());
        assertTrue(test.source.snapshot().loading());
        assertTrue(replies.isEmpty());
        assertEquals(2, test.requests.size());
        test.succeed(1, "installed");
        assertEquals(List.of("installed"), replies.get(0).entries());
    }

    @Test public void endpointReplacementClearsCacheAndIgnoresOldOwnerCompletion() {
        final var test = new Source();
        test.source.ensureLoaded();
        test.succeed(0, "original package");
        final List<ApplicationCatalogSource.Snapshot<String>> replies = new ArrayList<>();
        test.source.refresh(replies::add);
        test.source.reset("RUN_COMMAND unavailable");
        assertTrue(test.source.snapshot().entries().isEmpty());
        assertFalse(test.source.snapshot().ready());
        assertEquals(1, replies.size());
        assertEquals("RUN_COMMAND unavailable", replies.get(0).error());
        test.source.ensureLoaded();
        test.succeed(1, "stale original package");
        assertTrue(test.source.snapshot().loading());
        assertTrue(test.source.snapshot().entries().isEmpty());
        test.succeed(2, "new package");
        assertEquals(List.of("new package"), test.source.snapshot().entries());
    }

    @Test public void duplicateCompletionCannotFinishAnotherReadOrOverwriteCache() {
        final var test = new Source();
        test.source.ensureLoaded();
        test.succeed(0, "first");
        test.fail(0);
        assertEquals("", test.source.snapshot().error());
        test.source.refresh(null);
        test.succeed(0, "duplicate");
        assertTrue(test.source.snapshot().loading());
        assertEquals(List.of("first"), test.source.snapshot().entries());
        test.succeed(1, "second");
        assertEquals(List.of("second"), test.source.snapshot().entries());
    }

    @Test public void slowOrFailingTermuxDoesNotBlockAndroid() {
        final var android = new Source();
        final var termux = new Source();
        android.source.ensureLoaded();
        termux.source.ensureLoaded();
        android.succeed(0, "Android app");
        assertTrue(android.source.snapshot().ready());
        assertTrue(termux.source.snapshot().loading());
        termux.fail(0);
        assertEquals(List.of("Android app"), android.source.snapshot().entries());
    }

    @Test public void synchronousLoaderFailureCompletesWaitingReader() {
        final var source = new ApplicationCatalogSource<String>(complete -> {
            throw new IllegalStateException("load failed");
        }, () -> { });
        final List<ApplicationCatalogSource.Snapshot<String>> replies = new ArrayList<>();
        source.refresh(replies::add);
        assertEquals(1, replies.size());
        assertFalse(replies.get(0).loading());
        assertTrue(replies.get(0).error().contains("load failed"));
    }
}

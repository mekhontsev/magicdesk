package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public final class ContentImportBatchTest {
    @Test
    public void successfulBatchReportsEveryProcessedItem() {
        final List<String> copied = new ArrayList<>();
        final List<Integer> progress = new ArrayList<>();
        final ContentImportBatch<String> request = new ContentImportBatch<>(List.of("a", "b"),
                (item, cancelled) -> copied.add(item));
        final ContentImportBatch.Result result = request.run(null, progress::add);
        assertEquals(List.of("a", "b"), copied);
        assertEquals(List.of(1, 2), progress);
        assertResult(result, 2, 2, 0, 0, false);
        assertTrue(result.isComplete());
        assertNull(result.firstFailure);
    }

    @Test
    public void emptyBatchDoesNotAccessProvider() {
        final ContentImportBatch.Result result = new ContentImportBatch<>(List.of(),
                (item, cancelled) -> fail("empty import")).run(() -> true, ignored -> fail("progress"));
        assertResult(result, 0, 0, 0, 0, false);
        assertTrue(result.isComplete());
    }

    @Test
    public void ordinaryFailuresContinueAndCountTowardsProgress() {
        final IOException first = new IOException("unreadable");
        final List<Integer> progress = new ArrayList<>();
        final ContentImportBatch.Result result = new ContentImportBatch<>(List.of(1, 2, 3, 4),
                (item, cancelled) -> {
                    if (item == 1) throw first;
                    if (item == 3) throw new SecurityException("grant lost");
                }).run(null, progress::add);
        assertResult(result, 4, 2, 2, 0, false);
        assertEquals(List.of(1, 2, 3, 4), progress);
        assertSame(first, result.firstFailure);
        assertFalse(result.isComplete());
    }

    @Test
    public void cancellationBeforeFirstItemIsNotSuccessOrProviderFailure() {
        final ContentImportBatch.Result result = new ContentImportBatch<>(List.of(1, 2),
                (item, cancelled) -> fail("provider accessed after cancellation"))
                .run(() -> true, ignored -> fail("progress"));
        assertResult(result, 2, 0, 0, 2, true);
        assertFalse(result.isComplete());
        assertNull(result.firstFailure);
    }

    @Test
    public void cancellationBetweenItemsRetainsCommittedFiles() {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final ContentImportBatch.Result result = new ContentImportBatch<>(List.of(1, 2, 3),
                (item, signal) -> {
                    assertEquals(Integer.valueOf(1), item);
                    cancelled.set(true);
                }).run(cancelled::get, null);
        assertResult(result, 3, 1, 0, 2, true);
        assertFalse(result.isComplete());
    }

    @Test
    public void cancellationAtEofDoesNotCountIncompleteCopy() {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicInteger attempted = new AtomicInteger();
        final ContentImportBatch.Result result = new ContentImportBatch<>(List.of(1, 2),
                (item, signal) -> {
                    attempted.incrementAndGet();
                    final ByteArrayInputStream input = new ByteArrayInputStream(new byte[0]) {
                        @Override
                        public synchronized int read(final byte[] buffer, final int offset, final int length) {
                            cancelled.set(true);
                            return -1;
                        }
                    };
                    ContentStreamCopy.copy(input, new ByteArrayOutputStream(), signal);
                }).run(cancelled::get, null);
        assertResult(result, 2, 0, 0, 2, true);
        assertEquals(1, attempted.get());
        assertNull(result.firstFailure);
    }

    @Test
    public void cancellationAfterLastCommitDoesNotUndoSuccess() {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final ContentImportBatch.Result result = new ContentImportBatch<>(List.of(1),
                (item, signal) -> cancelled.set(true)).run(cancelled::get, null);
        assertResult(result, 1, 1, 0, 0, false);
        assertTrue(result.isComplete());
    }

    @Test
    public void earlierErrorSurvivesLaterCancellation() {
        final IOException error = new IOException("first item missing");
        final AtomicBoolean cancelled = new AtomicBoolean();
        final ContentImportBatch.Result result = new ContentImportBatch<>(List.of(1, 2, 3),
                (item, signal) -> {
                    if (item == 1) throw error;
                    cancelled.set(true);
                    ContentStreamCopy.checkCancelled(signal);
                }).run(cancelled::get, null);
        assertResult(result, 3, 0, 1, 2, true);
        assertSame(error, result.firstFailure);
    }

    @Test
    public void simultaneousProviderFailureAndCancellationPreserveTheError() {
        final IOException error = new IOException("provider disconnected");
        final AtomicBoolean cancelled = new AtomicBoolean();
        final ContentImportBatch.Result result = new ContentImportBatch<>(List.of(1, 2),
                (item, signal) -> {
                    cancelled.set(true);
                    throw error;
                }).run(cancelled::get, null);
        assertResult(result, 2, 0, 1, 1, true);
        assertSame(error, result.firstFailure);
    }

    @Test
    public void cancellationDoesNotSwallowRollbackFailure() {
        final InterruptedIOException error = new InterruptedIOException("cancelled");
        final IOException rollback = new IOException("cannot remove partial output");
        error.addSuppressed(rollback);
        final AtomicBoolean cancelled = new AtomicBoolean();
        final ContentImportBatch.Result result = new ContentImportBatch<>(List.of(1, 2),
                (item, signal) -> {
                    cancelled.set(true);
                    throw error;
                }).run(cancelled::get, null);
        assertResult(result, 2, 0, 1, 1, true);
        assertSame(error, result.firstFailure);
        assertSame(rollback, result.firstFailure.getSuppressed()[0]);
    }

    @Test
    public void providerTimeoutWithoutCancellationIsAnErrorAndBatchContinues() {
        final InterruptedIOException timeout = new InterruptedIOException("provider timeout");
        final ContentImportBatch.Result result = new ContentImportBatch<>(List.of(1, 2),
                (item, signal) -> {
                    if (item == 1) throw timeout;
                }).run(() -> false, null);
        assertResult(result, 2, 1, 1, 0, false);
        assertSame(timeout, result.firstFailure);
    }

    @Test
    public void threadInterruptionStopsTheBatchWithoutClearingTheInterrupt() {
        try {
            Thread.currentThread().interrupt();
            final ContentImportBatch.Result result = new ContentImportBatch<>(List.of(1),
                    (item, signal) -> fail("provider called")).run(null, null);
            assertResult(result, 1, 0, 0, 1, true);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void queuedRequestOwnsItsSourceSnapshotAndGrantUntilCompletion() {
        final List<String> sources = new ArrayList<>(List.of("a", "b"));
        final List<String> copied = new ArrayList<>();
        final AtomicInteger releases = new AtomicInteger();
        final ContentImportBatch<String> request = new ContentImportBatch<>(sources,
                (item, signal) -> {
                    assertEquals(0, releases.get());
                    copied.add(item);
                });
        final List<Runnable> queue = new ArrayList<>();
        try (ContentRequestScope scope = new ContentRequestScope(queue::add)) {
            final CompletableFuture<ContentRequestScope.Completion<ContentImportBatch.Result>> result = scope.submit(
                    signal -> request.run(signal, null), releases::incrementAndGet);
            sources.clear();
            sources.add("replacement");
            assertFalse(result.isDone());
            queue.get(0).run();
            assertEquals(List.of("a", "b"), copied);
            assertResult(request.finish(result.join().value, result.join().failure), 2, 2, 0, 0, false);
            assertEquals(1, releases.get());
        }
        assertEquals(1, releases.get());
    }

    @Test
    public void closingRunningScopeRetainsPartialResultAndReleasesAfterCopyReturns() {
        final AtomicInteger releases = new AtomicInteger();
        try (ContentRequestScope scope = new ContentRequestScope(Runnable::run)) {
            final ContentImportBatch<Integer> request = new ContentImportBatch<>(List.of(1, 2, 3),
                    (item, signal) -> {
                        if (item == 2) {
                            scope.close();
                            assertEquals(0, releases.get());
                            ContentStreamCopy.checkCancelled(signal);
                        }
                    });
            final var completion = scope.submit(
                    signal -> request.run(signal, null), releases::incrementAndGet).join();
            final ContentImportBatch.Result result = request.finish(completion.value, completion.failure);
            assertResult(result, 3, 1, 0, 2, true);
            assertEquals(1, releases.get());
        }
    }

    @Test
    public void notStartedRequestDoesNotInventProviderFailures() {
        final IOException failure = new IOException("cannot schedule import");
        final ContentImportBatch.Result result = ContentImportBatch.Result.notStarted(3, failure);
        assertResult(result, 3, 0, 0, 3, false);
        assertSame(failure, result.firstFailure);
        assertFalse(result.isComplete());
    }

    @Test
    public void progressFailureIsNotTreatedAsAnotherFailedFile() {
        final IllegalStateException failure = new IllegalStateException("UI unavailable");
        final AtomicInteger copies = new AtomicInteger();
        final ContentImportBatch<Integer> request = new ContentImportBatch<>(List.of(1, 2),
                (item, signal) -> copies.incrementAndGet());
        final ContentImportBatch.Result result = request.run(null, progress -> { throw failure; });
        assertResult(result, 2, 1, 0, 1, false);
        assertSame(failure, result.firstFailure);
        assertFalse(result.isComplete());
        assertEquals(1, copies.get());
    }

    @Test
    public void releaseFailureRetainsAllCommittedCopies() {
        final IllegalStateException release = new IllegalStateException("release failed");
        final ContentImportBatch<Integer> request = new ContentImportBatch<>(List.of(1, 2), (item, signal) -> { });
        try (ContentRequestScope scope = new ContentRequestScope(Runnable::run)) {
            final var completion = scope.submit(signal -> request.run(signal, null), () -> { throw release; }).join();
            final ContentImportBatch.Result result = request.finish(completion.value, completion.failure);
            assertResult(result, 2, 2, 0, 0, false);
            assertSame(release, result.firstFailure);
            assertFalse(result.isComplete());
            assertTrue(completion.value.isComplete());
        }
    }

    @Test
    public void mergingReleaseFailureDoesNotMutateTheOriginalPartialResult() {
        final IOException unreadable = new IOException("unreadable");
        final IOException release = new IOException("release failed");
        final ContentImportBatch<Integer> request = new ContentImportBatch<>(List.of(1, 2),
                (item, signal) -> { if (item == 2) throw unreadable; });
        final ContentImportBatch.Result original = request.run(null, null);
        final ContentImportBatch.Result result = request.finish(original, release);
        assertResult(result, 2, 1, 1, 0, false);
        assertSame(unreadable, result.firstFailure.getCause());
        assertSame(release, result.firstFailure.getSuppressed()[0]);
        assertSame(unreadable, original.firstFailure);
        assertEquals(0, unreadable.getSuppressed().length);
        assertSame(original, request.finish(original, null));
        assertSame(original, request.finish(original, unreadable));
    }

    @Test
    public void cancellationAndPartialCopiesSurviveReleaseFailureTogether() {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final IOException release = new IOException("release failed");
        final ContentImportBatch<Integer> request = new ContentImportBatch<>(List.of(1, 2, 3),
                (item, signal) -> cancelled.set(true));
        final ContentImportBatch.Result result = request.finish(request.run(cancelled::get, null), release);
        assertResult(result, 3, 1, 0, 2, true);
        assertSame(release, result.firstFailure);
    }

    @Test
    public void finishOfARejectedRequestCountsUnattemptedItems() {
        final IOException failure = new IOException("rejected");
        final ContentImportBatch<Integer> request = new ContentImportBatch<>(List.of(1, 2),
                (item, signal) -> fail("not started"));
        final ContentImportBatch.Result result = request.finish(null, failure);
        assertResult(result, 2, 0, 0, 2, false);
        assertSame(failure, result.firstFailure);
    }

    @Test
    public void progressFailureAfterLastCopyDoesNotEraseThatCopy() {
        final IllegalStateException failure = new IllegalStateException("UI unavailable");
        final ContentImportBatch.Result result = new ContentImportBatch<>(List.of(1), (item, signal) -> { })
                .run(null, count -> { throw failure; });
        assertResult(result, 1, 1, 0, 0, false);
        assertSame(failure, result.firstFailure);
        assertFalse(result.isComplete());
    }

    @Test
    public void progressFailureDoesNotOverwriteEarlierProviderFailure() {
        final IOException provider = new IOException("source missing");
        final IllegalStateException progress = new IllegalStateException("UI unavailable");
        final ContentImportBatch.Result result = new ContentImportBatch<>(List.of(1, 2),
                (item, signal) -> { throw provider; })
                .run(null, count -> { throw progress; });
        assertResult(result, 2, 0, 1, 1, false);
        assertSame(provider, result.firstFailure.getCause());
        assertSame(progress, result.firstFailure.getSuppressed()[0]);
        assertEquals(0, provider.getSuppressed().length);
    }

    private static void assertResult(final ContentImportBatch.Result result,
            final int total, final int copied, final int failed, final int skipped,
            final boolean cancelled) {
        assertEquals(total, result.total);
        assertEquals(copied, result.copied);
        assertEquals(failed, result.failed);
        assertEquals(skipped, result.skipped);
        assertEquals(cancelled, result.cancelled);
        assertEquals(result.total, result.copied + result.failed + result.skipped);
    }
}

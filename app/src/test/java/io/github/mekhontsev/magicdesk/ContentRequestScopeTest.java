package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayDeque;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public final class ContentRequestScopeTest {
    @Test
    public void successfulValueSurvivesReleaseFailure() {
        final ContentRequestScope scope = new ContentRequestScope(Runnable::run);
        final IllegalStateException cleanup = new IllegalStateException("release failed");
        scope.submit(cancelled -> "saved", () -> { throw cleanup; })
                .thenAccept(completion -> {
                    assertEquals("saved", completion.value);
                    assertSame(cleanup, completion.failure);
                }).join();
        scope.close();
    }

    @Test
    public void partialImportResultSurvivesReleaseFailure() {
        final ContentRequestScope scope = new ContentRequestScope(Runnable::run);
        final IOException unreadable = new IOException("source missing");
        final IllegalStateException cleanup = new IllegalStateException("release failed");
        final ContentImportBatch<Integer> batch = new ContentImportBatch<>(java.util.List.of(1, 2),
                (item, cancelled) -> { if (item == 2) throw unreadable; });
        scope.submit(cancelled -> batch.run(cancelled, null), () -> { throw cleanup; })
                .thenAccept(completion -> {
                    assertEquals(1, completion.value.copied);
                    assertEquals(1, completion.value.failed);
                    assertSame(unreadable, completion.value.firstFailure);
                    assertSame(cleanup, completion.failure);
                }).join();
        scope.close();
    }

    @Test
    public void discardedQueuedImportStillReleasesItsGrantExactlyOnce() {
        final Queue executor = new Queue();
        final ContentRequestScope scope = new ContentRequestScope(executor);
        final AtomicInteger writes = new AtomicInteger();
        final AtomicInteger releases = new AtomicInteger();
        final var result = scope.submit(cancelled -> writes.incrementAndGet(),
                releases::incrementAndGet);

        scope.close();
        scope.close();
        executor.drain();

        assertTrue(result.join().failure instanceof InterruptedIOException);
        assertNull(result.join().value);
        assertEquals(0, writes.get());
        assertEquals(1, releases.get());
    }

    @Test
    public void closedScopeRejectsNewImportAndReleasesIt() {
        final Queue executor = new Queue();
        final ContentRequestScope scope = new ContentRequestScope(executor);
        scope.close();
        final AtomicInteger releases = new AtomicInteger();

        final var result = scope.submit(cancelled -> "unused", releases::incrementAndGet);

        assertTrue(result.join().failure instanceof InterruptedIOException);
        assertNull(result.join().value);
        assertEquals(0, executor.tasks.size());
        assertEquals(1, releases.get());
    }

    @Test
    public void executorRejectionAlsoCompletesAndReleases() {
        final ContentRequestScope scope = new ContentRequestScope(task -> {
            throw new RejectedExecutionException("closed worker");
        });
        final AtomicInteger releases = new AtomicInteger();

        final var result = scope.submit(cancelled -> "unused", releases::incrementAndGet);
        scope.close();

        assertTrue(result.join().failure instanceof RejectedExecutionException);
        assertNull(result.join().value);
        assertEquals(1, releases.get());
    }

    @Test
    public void completionFollowsReleaseAndDoesNotReleaseAgainOnClose() {
        final ContentRequestScope scope = new ContentRequestScope(Runnable::run);
        final AtomicInteger releases = new AtomicInteger();
        final var result = scope.submit(cancelled -> "saved", releases::incrementAndGet);

        result.thenRun(() -> assertEquals(1, releases.get())).join();
        scope.close();

        assertEquals("saved", result.join().value);
        assertNull(result.join().failure);
        assertEquals(1, releases.get());
    }

    @Test
    public void releaseFailureDoesNotLoseOriginalImportError() {
        final ContentRequestScope scope = new ContentRequestScope(Runnable::run);
        final IOException original = new IOException("provider failed");
        final IllegalStateException cleanup = new IllegalStateException("release failed");

        final var result = scope.submit(cancelled -> { throw original; },
                () -> { throw cleanup; });

        assertSame(original, result.join().failure);
        assertNull(result.join().value);
        assertSame(cleanup, original.getSuppressed()[0]);
        scope.close();
        assertEquals(1, original.getSuppressed().length);
    }

    @Test
    public void runningImportKeepsGrantUntilItObservesCancellation() throws Exception {
        final var executor = Executors.newSingleThreadExecutor();
        final ContentRequestScope scope = new ContentRequestScope(executor);
        final CountDownLatch reading = new CountDownLatch(1);
        final CountDownLatch finishRead = new CountDownLatch(1);
        final AtomicInteger releases = new AtomicInteger();
        try {
            final var result = scope.submit(cancelled -> {
                reading.countDown();
                assertTrue(finishRead.await(5, TimeUnit.SECONDS));
                assertTrue(cancelled.getAsBoolean());
                ContentStreamCopy.checkCancelled(cancelled);
                return "unreachable";
            }, releases::incrementAndGet);
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
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void closeDuringExecutorSubmissionCannotRunReleasedWork() {
        final ContentRequestScope[] scope = new ContentRequestScope[1];
        scope[0] = new ContentRequestScope(task -> {
            scope[0].close();
            task.run();
        });
        final AtomicInteger writes = new AtomicInteger();
        final AtomicInteger releases = new AtomicInteger();

        final var result = scope[0].submit(cancelled -> writes.incrementAndGet(),
                releases::incrementAndGet);

        assertTrue(result.join().failure instanceof InterruptedIOException);
        assertNull(result.join().value);
        assertEquals(0, writes.get());
        assertEquals(1, releases.get());
    }

    @Test
    public void queuedUiCallbackIsDroppedAfterOwnerCloses() {
        final ContentRequestScope scope = new ContentRequestScope(Runnable::run);
        final Queue ui = new Queue();
        final AtomicInteger releases = new AtomicInteger();
        final AtomicInteger callbacks = new AtomicInteger();

        scope.submit(cancelled -> "delivered", releases::incrementAndGet)
                .thenAccept(completion -> scope.deliver(ui, callbacks::incrementAndGet));
        assertEquals(1, releases.get());
        assertEquals(1, ui.tasks.size());
        scope.close();
        ui.drain();

        assertEquals(0, callbacks.get());
        assertEquals(1, releases.get());
    }

    @Test
    public void callbackFailureCannotPreventGrantRelease() {
        final ContentRequestScope scope = new ContentRequestScope(Runnable::run);
        final Queue ui = new Queue();
        final AtomicInteger releases = new AtomicInteger();

        scope.submit(cancelled -> "delivered", releases::incrementAndGet)
                .thenAccept(completion -> scope.deliver(ui, () -> {
                    assertEquals(1, releases.get());
                    throw new IllegalStateException("UI unavailable");
                }));

        assertThrows(IllegalStateException.class, ui::drain);
        scope.close();
        assertEquals(1, releases.get());
    }

    @Test
    public void closedOwnerDoesNotEvenQueueTheCallback() {
        final ContentRequestScope scope = new ContentRequestScope(Runnable::run);
        final Queue ui = new Queue();
        scope.close();

        scope.deliver(ui, () -> { throw new AssertionError("closed UI"); });

        assertTrue(ui.tasks.isEmpty());
    }

    @Test
    public void replacementRequestDoesNotPresentTheOldResult() {
        final Queue worker = new Queue();
        final Queue ui = new Queue();
        final ContentRequestScope old = new ContentRequestScope(worker);
        final java.util.List<String> shown = new java.util.ArrayList<>();
        old.submit(cancelled -> "old handlers", null)
                .thenAccept(completion -> old.deliver(ui, () -> shown.add(completion.value)));
        worker.drain();

        old.close();
        final ContentRequestScope current = new ContentRequestScope(worker);
        current.submit(cancelled -> "current handlers", null)
                .thenAccept(completion -> current.deliver(ui, () -> shown.add(completion.value)));
        worker.drain();
        ui.drain();

        assertEquals(java.util.List.of("current handlers"), shown);
        current.close();
    }

    @Test
    public void closingOneOwnerDoesNotCancelOtherWorkOnTheSharedExecutor() {
        final Queue worker = new Queue();
        final ContentRequestScope old = new ContentRequestScope(worker);
        final ContentRequestScope current = new ContentRequestScope(worker);
        final var discarded = old.submit(cancelled -> {
            throw new AssertionError("cancelled request must not run");
        }, null);
        final var retained = current.submit(cancelled -> "current", null);

        old.close();
        worker.drain();

        assertTrue(discarded.join().failure instanceof InterruptedIOException);
        assertNull(discarded.join().value);
        assertEquals("current", retained.join().value);
        assertNull(retained.join().failure);
        current.close();
    }

    @Test
    public void nullOperationResultIsAValidSuccessfulCompletion() {
        try (ContentRequestScope scope = new ContentRequestScope(Runnable::run)) {
            final var completion = scope.submit(cancelled -> null, null).join();
            assertNull(completion.value);
            assertNull(completion.failure);
        }
    }

    @Test
    public void rejectedRequestStillPublishesBothRejectionAndReleaseFailure() {
        final RejectedExecutionException rejected = new RejectedExecutionException("worker closed");
        final IllegalStateException cleanup = new IllegalStateException("release failed");
        try (ContentRequestScope scope = new ContentRequestScope(task -> { throw rejected; })) {
            final var completion = scope.submit(cancelled -> "never ran", () -> { throw cleanup; }).join();
            assertNull(completion.value);
            assertSame(rejected, completion.failure);
            assertEquals(1, completion.failure.getSuppressed().length);
            assertSame(cleanup, completion.failure.getSuppressed()[0]);
        }
    }

    @Test
    public void releaseFailureCannotStopClosingOtherQueuedRequests() {
        final Queue worker = new Queue();
        final ContentRequestScope scope = new ContentRequestScope(worker);
        final AtomicInteger releases = new AtomicInteger();
        final var first = scope.submit(cancelled -> "never ran", () -> {
            releases.incrementAndGet();
            throw new IllegalStateException("release failed");
        });
        final var second = scope.submit(cancelled -> "never ran", releases::incrementAndGet);
        scope.close();
        worker.drain();
        assertTrue(first.join().failure instanceof InterruptedIOException);
        assertEquals(1, first.join().failure.getSuppressed().length);
        assertTrue(second.join().failure instanceof InterruptedIOException);
        assertEquals(2, releases.get());
    }

    @Test
    public void failingCompletionSubscriberDoesNotChangeThePublishedResult() {
        final IllegalStateException cleanup = new IllegalStateException("release failed");
        final IllegalStateException callback = new IllegalStateException("callback failed");
        try (ContentRequestScope scope = new ContentRequestScope(Runnable::run)) {
            final var completed = scope.submit(cancelled -> "saved", () -> { throw cleanup; });
            final var failedSubscriber = completed.thenAccept(result -> { throw callback; });
            assertSame(callback, assertThrows(CompletionException.class, failedSubscriber::join).getCause());
            assertEquals("saved", completed.join().value);
            assertSame(cleanup, completed.join().failure);
            assertEquals("saved", completed.thenApply(result -> result.value).join());
        }
    }

    @Test
    public void completionIsPublishedOutsideTheOwnerLock() throws Exception {
        final Queue worker = new Queue();
        final var other = Executors.newSingleThreadExecutor();
        try (ContentRequestScope scope = new ContentRequestScope(worker)) {
            final var result = scope.submit(cancelled -> "saved", null);
            final var callback = result.thenRun(() -> {
                try {
                    other.submit(scope::close).get(5, TimeUnit.SECONDS);
                } catch (Exception error) {
                    throw new AssertionError("completion held the scope lock", error);
                }
            });
            worker.drain();
            callback.join();
            assertEquals("saved", result.join().value);
        } finally {
            other.shutdownNow();
            assertTrue(other.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void sameOperationAndReleaseFailureIsNotSelfSuppressed() {
        final IllegalStateException failure = new IllegalStateException("same failure");
        try (ContentRequestScope scope = new ContentRequestScope(Runnable::run)) {
            final var result = scope.submit(cancelled -> { throw failure; }, () -> {
                throw failure;
            }).join();
            assertSame(failure, result.failure);
            assertEquals(0, failure.getSuppressed().length);
        }
    }

    private static final class Queue implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(final Runnable task) {
            tasks.add(task);
        }

        void drain() {
            while (!tasks.isEmpty()) {
                tasks.remove().run();
            }
        }
    }
}

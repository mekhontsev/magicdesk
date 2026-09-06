package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public final class TerminalRequestScopeTest {
    @Test
    public void completedRequestKeepsItsResultAfterClose() throws Exception {
        final TerminalRequestScope scope = new TerminalRequestScope(Runnable::run);
        final CompletableFuture<String> result = scope.submit(() -> "/home");
        scope.close();
        assertEquals("/home", result.get(2L, TimeUnit.SECONDS));
    }

    @Test
    public void closeCompletesQueuedRequestsWithoutExecutingThem() {
        final List<Runnable> queue = new ArrayList<>();
        final TerminalRequestScope scope = new TerminalRequestScope(queue::add);
        final AtomicInteger executed = new AtomicInteger();
        final AtomicInteger notified = new AtomicInteger();
        final CompletableFuture<Integer> result = scope.submit(executed::incrementAndGet);
        result.whenComplete((value, failure) -> notified.incrementAndGet());
        assertFalse(result.isDone());
        scope.close();
        scope.close();
        assertClosed(result);
        queue.get(0).run();
        assertEquals(0, executed.get());
        assertEquals(1, notified.get());
    }

    @Test
    public void requestAfterCloseCompletesWithoutEnqueueing() {
        final List<Runnable> queue = new ArrayList<>();
        final TerminalRequestScope scope = new TerminalRequestScope(queue::add);
        scope.close();
        assertClosed(scope.submit(() -> "late"));
        assertTrue(queue.isEmpty());
    }

    @Test
    public void closeCompletesInFlightRequestWithoutWaitingForTransport() throws Exception {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicReference<Thread> worker = new AtomicReference<>();
        final TerminalRequestScope scope = new TerminalRequestScope(operation -> {
            final Thread thread = new Thread(operation, "terminal-request-test");
            worker.set(thread);
            thread.setDaemon(true);
            thread.start();
        });
        final AtomicInteger notified = new AtomicInteger();
        final CompletableFuture<String> result = scope.submit(() -> {
            started.countDown();
            if (!release.await(2L, TimeUnit.SECONDS)) {
                throw new IOException("test transport was not released");
            }
            return "late result";
        });
        result.whenComplete((value, failure) -> notified.incrementAndGet());
        try {
            assertTrue(started.await(2L, TimeUnit.SECONDS));
            scope.close();
            assertClosed(result);
            assertEquals(1, notified.get());
            release.countDown();
            worker.get().join(2_000L);
            assertFalse(worker.get().isAlive());
            assertClosed(result);
            assertEquals(1, notified.get());
        } finally {
            release.countDown();
            worker.get().interrupt();
            worker.get().join(2_000L);
        }
    }

    @Test
    public void executorRejectionCompletesRequest() {
        final RejectedExecutionException failure = new RejectedExecutionException("closed");
        final TerminalRequestScope scope = new TerminalRequestScope(operation -> {
            throw failure;
        });
        final CompletableFuture<String> result = scope.submit(() -> "unreachable");
        assertSame(failure, assertThrows(ExecutionException.class,
                () -> result.get(2L, TimeUnit.SECONDS)).getCause());
    }

    @Test
    public void metadataFailureReachesCallerWithoutBecomingEmptySuccess() {
        final TerminalRequestScope scope = new TerminalRequestScope(Runnable::run);
        final IOException failure = new IOException("transport failed");
        final CompletableFuture<String> result = scope.submit(() -> {
            throw failure;
        });
        assertSame(failure, assertThrows(ExecutionException.class,
                () -> result.get(2L, TimeUnit.SECONDS)).getCause());
    }

    private static void assertClosed(final CompletableFuture<?> result) {
        final Throwable failure = assertThrows(ExecutionException.class,
                () -> result.get(2L, TimeUnit.SECONDS)).getCause();
        assertTrue(failure instanceof IOException);
        assertEquals("terminal closed", failure.getMessage());
    }
}

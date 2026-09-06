package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public final class AppFunctionRequestQueueTest {
    @Test
    public void cancellationBeforeSubmissionDeliversOnceAndNeverExecutes() {
        final var executor = Executors.newSingleThreadExecutor();
        final var cancelled = new AtomicInteger();
        final var actions = new AtomicInteger();
        try (var requests = new AppFunctionRequestQueue(executor)) {
            final var request = requests.create(actions::incrementAndGet, cancelled::incrementAndGet);
            assertTrue(request.cancel(true));
            requests.execute(request);
            request.cancel(true);
            assertEquals(1, cancelled.get());
            assertEquals(0, actions.get());
            assertEquals(0, requests.pendingCount());
        }
    }

    @Test(timeout = 5000)
    public void publishedFutureCanInterruptWorkBeforeExecutorReturns() throws Exception {
        final var started = new CountDownLatch(1);
        final var interrupted = new CountDownLatch(1);
        final var releaseSubmit = new CountDownLatch(1);
        final var cancelled = new AtomicInteger();
        final var executor = new HoldingExecutor(releaseSubmit);
        final var requests = new AppFunctionRequestQueue(executor);
        final var request = requests.create(() -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
        }, cancelled::incrementAndGet);
        final Thread submitter = new Thread(() -> requests.execute(request));
        submitter.start();
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertTrue("executor has not returned", submitter.isAlive());
            assertTrue(request.cancel(true));
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
            assertEquals(1, cancelled.get());
            assertEquals(0, requests.pendingCount());
        } finally {
            releaseSubmit.countDown();
            requests.close();
            submitter.join(1000);
            executor.worker.join(1000);
            assertFalse(submitter.isAlive());
            assertFalse(executor.worker.isAlive());
        }
    }

    @Test(timeout = 5000)
    public void shutdownCancelsRunningQueuedAndNotYetSubmittedRequests() throws Exception {
        final var executor = Executors.newSingleThreadExecutor();
        final var requests = new AppFunctionRequestQueue(executor);
        final var started = new CountDownLatch(1);
        final var cancelled = new AtomicInteger();
        final var queuedActions = new AtomicInteger();
        final var running = requests.create(() -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                Thread.currentThread().interrupt();
            }
        }, cancelled::incrementAndGet);
        try {
            requests.execute(running);
            assertTrue(started.await(1, TimeUnit.SECONDS));
            final var queued = requests.create(queuedActions::incrementAndGet,
                    cancelled::incrementAndGet);
            requests.execute(queued);
            final var notSubmitted = requests.create(queuedActions::incrementAndGet,
                    cancelled::incrementAndGet);
            requests.close();
            assertTrue(running.isCancelled());
            assertTrue(queued.isCancelled());
            assertTrue(notSubmitted.isCancelled());
            assertEquals(3, cancelled.get());
            assertEquals(0, requests.pendingCount());
            requests.execute(notSubmitted);
            assertEquals(0, queuedActions.get());
            final var afterClose = requests.create(queuedActions::incrementAndGet,
                    cancelled::incrementAndGet);
            requests.execute(afterClose);
            assertTrue(afterClose.isCancelled());
            assertEquals(4, cancelled.get());
        } finally {
            requests.close();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    public void executorRejectionAndBrokenClientCallbackDoNotStrandRequests() {
        final var executor = Executors.newSingleThreadExecutor();
        final var cancelled = new AtomicInteger();
        try (var requests = new AppFunctionRequestQueue(executor)) {
            final var broken = requests.create(() -> { }, () -> {
                throw new IllegalStateException("client disconnected");
            });
            final var queued = requests.create(() -> { }, cancelled::incrementAndGet);
            requests.close();
            assertTrue(broken.isCancelled());
            assertTrue(queued.isCancelled());
            assertEquals(1, cancelled.get());
            assertEquals(0, requests.pendingCount());
        }
        final var stopped = Executors.newSingleThreadExecutor();
        stopped.shutdownNow();
        try (var requests = new AppFunctionRequestQueue(stopped)) {
            final var rejected = requests.create(() -> { }, cancelled::incrementAndGet);
            requests.execute(rejected);
            assertTrue(rejected.isCancelled());
            assertEquals(2, cancelled.get());
            assertEquals(0, requests.pendingCount());
        }
    }

    @Test
    public void completedRequestIsRemovedAndNotCancelledOnShutdown() throws Exception {
        final var executor = Executors.newSingleThreadExecutor();
        final var cancelled = new AtomicInteger();
        final var requests = new AppFunctionRequestQueue(executor);
        final var request = requests.create(() -> { }, cancelled::incrementAndGet);
        // Running directly makes done() completion deterministic without waiting on a timer.
        request.run();
        assertEquals(0, requests.pendingCount());
        requests.close();
        assertFalse(request.isCancelled());
        assertEquals(0, cancelled.get());
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
    }

    private static final class HoldingExecutor extends AbstractExecutorService {
        private final CountDownLatch release;
        private boolean stopped;
        Thread worker;

        HoldingExecutor(final CountDownLatch release) {
            this.release = release;
        }

        @Override
        public void execute(final Runnable command) {
            worker = new Thread(command);
            worker.start();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new AssertionError("fixture submit was not released");
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new AssertionError(error);
            }
        }

        @Override
        public void shutdown() {
            stopped = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown();
            if (worker != null) {
                worker.interrupt();
            }
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return stopped;
        }

        @Override
        public boolean isTerminated() {
            return stopped && (worker == null || !worker.isAlive());
        }

        @Override
        public boolean awaitTermination(final long timeout, final TimeUnit unit)
                throws InterruptedException {
            if (worker != null) {
                unit.timedJoin(worker, timeout);
            }
            return isTerminated();
        }
    }
}

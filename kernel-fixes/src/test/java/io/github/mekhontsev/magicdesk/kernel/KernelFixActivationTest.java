package io.github.mekhontsev.magicdesk.kernel;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public final class KernelFixActivationTest {
    @Test
    public void recreationObservesTheSameWorkerAndDoesNotReenableActivation() {
        final var activation = new XrResolutionFix.Activation();
        final var workers = new ArrayList<Runnable>();
        final var oldUpdates = new AtomicInteger();
        final var newUpdates = new AtomicInteger();
        final Runnable oldActivity = oldUpdates::incrementAndGet;
        final Runnable newActivity = newUpdates::incrementAndGet;
        final var result = new XrResolutionFix.Result(XrResolutionFix.Code.ACTIVATED, "");
        activation.observe(oldActivity);
        assertTrue(activation.start(workers::add, () -> result));
        assertTrue(activation.state().running);
        assertEquals(1, oldUpdates.get());

        activation.removeObserver(oldActivity);
        activation.observe(newActivity);
        assertTrue(activation.state().running);
        assertFalse(activation.start(workers::add, () -> {
            throw new AssertionError("duplicate loader");
        }));
        assertEquals(1, workers.size());
        workers.get(0).run();

        assertFalse(activation.state().running);
        assertSame(result, activation.state().result);
        assertEquals(1, oldUpdates.get());
        assertEquals(1, newUpdates.get());
        activation.removeObserver(newActivity);
        assertSame(result, activation.state().result);
    }

    @Test(timeout = 5000)
    public void concurrentRequestDuringBlockedLoaderCannotExtractAgain() throws Exception {
        final var activation = new XrResolutionFix.Activation();
        final var started = new CountDownLatch(1);
        final var release = new CountDownLatch(1);
        final var completed = new CountDownLatch(1);
        final var calls = new AtomicInteger();
        final var threads = new ArrayList<Thread>();
        activation.observe(() -> {
            if (!activation.state().running) {
                completed.countDown();
            }
        });
        try {
            activation.start(task -> {
                final Thread thread = new Thread(task, "FakeKernelLoader");
                threads.add(thread);
                thread.start();
            }, () -> {
                calls.incrementAndGet();
                started.countDown();
                if (!release.await(2, TimeUnit.SECONDS)) {
                    throw new IOException("fixture did not release loader");
                }
                return new XrResolutionFix.Result(XrResolutionFix.Code.ACTIVE, "");
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertFalse(activation.start(Runnable::run, () -> {
                calls.incrementAndGet();
                return null;
            }));
            release.countDown();
            assertTrue(completed.await(1, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            assertEquals(XrResolutionFix.Code.ACTIVE, activation.state().result.code);
        } finally {
            release.countDown();
            for (final Thread thread : threads) {
                thread.join(1000);
                assertFalse(thread.isAlive());
            }
        }
    }

    @Test
    public void failedLoaderAndRejectedWorkerReleaseOwnershipForExplicitRetry() {
        final var activation = new XrResolutionFix.Activation();
        activation.start(Runnable::run, () -> {
            throw new IOException("read failed");
        });
        assertFalse(activation.state().running);
        assertEquals("read failed", activation.state().result.detail);
        activation.start(task -> {
            throw new RejectedExecutionException("worker unavailable");
        }, () -> null);
        assertFalse(activation.state().running);
        assertEquals(XrResolutionFix.Code.FAILED, activation.state().result.code);
        assertEquals("worker unavailable", activation.state().result.detail);
        assertTrue(activation.start(Runnable::run,
                () -> new XrResolutionFix.Result(XrResolutionFix.Code.ACTIVATED, "")));
        assertEquals(XrResolutionFix.Code.ACTIVATED, activation.state().result.code);
    }
}

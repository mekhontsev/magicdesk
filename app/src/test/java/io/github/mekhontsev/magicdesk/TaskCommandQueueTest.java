package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class TaskCommandQueueTest {
    @Test
    public void blockingCallWaitsForEarlierTaskOperation() throws Exception {
        final CountDownLatch firstStarted = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final CountDownLatch secondFinished = new CountDownLatch(1);
        final AtomicInteger result = new AtomicInteger();

        TaskCommandQueue.execute(() -> {
            firstStarted.countDown();
            await(releaseFirst);
        });
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

        final Thread caller = new Thread(() -> {
            result.set(TaskCommandQueue.call(() -> 42));
            secondFinished.countDown();
        });
        caller.start();
        assertFalse(secondFinished.await(100, TimeUnit.MILLISECONDS));

        releaseFirst.countDown();
        assertTrue(secondFinished.await(2, TimeUnit.SECONDS));
        caller.join(2_000L);
        assertEquals(42, result.get());
    }

    @Test
    public void blockingCallCanNestOnQueueWorker() throws Exception {
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicInteger result = new AtomicInteger();

        TaskCommandQueue.execute(() -> {
            result.set(TaskCommandQueue.call(() -> 7));
            finished.countDown();
        });

        assertTrue(finished.await(2, TimeUnit.SECONDS));
        assertEquals(7, result.get());
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(error);
        }
    }
}

package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.Test;

public final class TerminalOutputBufferTest {
    @Test
    public void schedulesOneDrainPerBatchAndCopiesProducerData() throws Exception {
        final TerminalOutputBuffer buffer = new TerminalOutputBuffer(4);
        final byte[] first = {1, 2};
        assertFalse(buffer.append(first, 0));
        assertTrue(buffer.append(first, 2));
        first[0] = 9;
        assertFalse(buffer.append(new byte[]{3, 4}, 2));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, buffer.drain());
        assertArrayEquals(new byte[0], buffer.drain());
        assertTrue(buffer.append(new byte[]{5}, 1));
        assertArrayEquals(new byte[]{5}, buffer.drain());
    }

    @Test
    public void fullBufferPausesProducerUntilDrainedWithoutDroppingBytes() throws Exception {
        final TerminalOutputBuffer buffer = fullBuffer();
        final Producer producer = new Producer(buffer);
        try {
            producer.assertWaiting();
            assertArrayEquals(new byte[]{1, 2, 3, 4}, buffer.drain());
            assertTrue(producer.result.get(2L, TimeUnit.SECONDS));
            assertArrayEquals(new byte[]{5, 6}, buffer.drain());
        } finally {
            buffer.close();
            producer.stop();
        }
    }

    @Test
    public void closeReleasesBlockedProducerAndRejectsLateOutput() throws Exception {
        final TerminalOutputBuffer buffer = fullBuffer();
        final Producer producer = new Producer(buffer);
        try {
            producer.assertWaiting();
            buffer.close();
            assertFalse(producer.result.get(2L, TimeUnit.SECONDS));
            assertArrayEquals(new byte[0], buffer.drain());
            assertFalse(buffer.append(new byte[]{7}, 1));
            buffer.close();
        } finally {
            buffer.close();
            producer.stop();
        }
    }

    @Test
    public void interruptedProducerDoesNotChangeBufferedOutput() throws Exception {
        final TerminalOutputBuffer buffer = fullBuffer();
        final Producer producer = new Producer(buffer);
        try {
            producer.assertWaiting();
            producer.thread.interrupt();
            final ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> producer.result.get(2L, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof InterruptedException);
            assertArrayEquals(new byte[]{1, 2, 3, 4}, buffer.drain());
        } finally {
            buffer.close();
            producer.stop();
        }
    }

    @Test
    public void invalidChunksFailInsteadOfWaitingForImpossibleCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new TerminalOutputBuffer(0));
        final TerminalOutputBuffer buffer = new TerminalOutputBuffer(4);
        assertThrows(IllegalArgumentException.class, () -> buffer.append(new byte[5], 5));
        assertThrows(IndexOutOfBoundsException.class, () -> buffer.append(new byte[1], 2));
        assertThrows(IndexOutOfBoundsException.class, () -> buffer.append(new byte[1], -1));
    }

    private static TerminalOutputBuffer fullBuffer() throws Exception {
        final TerminalOutputBuffer buffer = new TerminalOutputBuffer(4);
        assertTrue(buffer.append(new byte[]{1, 2, 3, 4}, 4));
        return buffer;
    }

    private static final class Producer {
        final CountDownLatch started = new CountDownLatch(1);
        final FutureTask<Boolean> result;
        final Thread thread;

        Producer(final TerminalOutputBuffer buffer) {
            result = new FutureTask<>(() -> {
                started.countDown();
                return buffer.append(new byte[]{5, 6}, 2);
            });
            thread = new Thread(result, "terminal-output-test");
            thread.setDaemon(true);
            thread.start();
        }

        void assertWaiting() throws Exception {
            assertTrue(started.await(2L, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class,
                    () -> result.get(100L, TimeUnit.MILLISECONDS));
        }

        void stop() throws Exception {
            thread.interrupt();
            thread.join(2_000L);
            assertFalse("output producer leaked", thread.isAlive());
        }
    }
}

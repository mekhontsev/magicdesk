package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public final class BoundedProcessRunnerTest {
    @Test
    public void truncatesCommandOutput() throws Exception {
        final FakeProcess process = new FakeProcess("abcdefgh", true, 7);

        final BoundedProcessRunner.Result result =
                BoundedProcessRunner.run(process, 1000L, 4);

        assertEquals(7, result.exitCode);
        assertTrue(result.truncated);
        assertTrue(result.output.startsWith("abcd"));
        assertTrue(result.output.contains("output truncated"));
    }

    @Test
    public void terminatesCommandAtDeadline() throws Exception {
        final FakeProcess process = new FakeProcess("", false, 0);

        try {
            BoundedProcessRunner.run(process, 1L, 1024);
            fail("Expected command timeout");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("timed out"));
        }
        assertTrue(process.destroyed);
        assertStreamsClosed(process);
    }

    @Test
    public void closesStdinBeforeWaitingAndReleasesAllStreams() throws Exception {
        final FakeProcess process = new FakeProcess("result", true, 0) {
            @Override
            public boolean waitFor(final long timeout, final TimeUnit unit) {
                assertTrue("one-shot command must receive EOF", inputClosed);
                return super.waitFor(timeout, unit);
            }
        };

        assertEquals("result", BoundedProcessRunner.run(process).output);
        assertStreamsClosed(process);
    }

    @Test
    public void interruptionWhileJoiningOutputReleasesAllStreams() throws Exception {
        final CountDownLatch reading = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        final FakeProcess process = new FakeProcess("", true, 0) {
            private final InputStream output = new InputStream() {
                @Override
                public int read() throws IOException {
                    reading.countDown();
                    try {
                        if (!closed.await(5L, TimeUnit.SECONDS)) {
                            throw new IOException("test stream not closed");
                        }
                        return -1;
                    } catch (InterruptedException error) {
                        throw new IOException(error);
                    }
                }

                @Override
                public void close() {
                    outputClosed = true;
                    closed.countDown();
                }
            };

            @Override
            public InputStream getInputStream() {
                return output;
            }
        };
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread runner = new Thread(() -> {
            try {
                BoundedProcessRunner.run(process);
                failure.set(new AssertionError("expected interruption"));
            } catch (InterruptedException expected) {
                // Interrupting an output join must use the same cleanup path.
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        runner.start();
        try {
            assertTrue(reading.await(2L, TimeUnit.SECONDS));
            runner.interrupt();
            runner.join(2_000L);
            assertTrue("runner must stop", !runner.isAlive());
            assertEquals(null, failure.get());
            assertStreamsClosed(process);
        } finally {
            closed.countDown();
            runner.interrupt();
            runner.join(2_000L);
        }
    }

    private static void assertStreamsClosed(final FakeProcess process) {
        assertTrue("stdin leaked", process.inputClosed);
        assertTrue("stdout leaked", process.outputClosed);
        assertTrue("stderr leaked", process.errorClosed);
    }

    private static class FakeProcess extends Process {
        private final InputStream mOutput;
        private final OutputStream mInput = new ByteArrayOutputStream() {
            @Override
            public void close() {
                inputClosed = true;
            }
        };
        private final InputStream mError = new ByteArrayInputStream(new byte[0]) {
            @Override
            public void close() {
                errorClosed = true;
            }
        };
        private final int mExitCode;
        private final boolean mCompletes;
        private boolean mAlive = true;
        boolean destroyed;
        volatile boolean inputClosed;
        volatile boolean outputClosed;
        volatile boolean errorClosed;

        FakeProcess(
                final String output,
                final boolean completes,
                final int exitCode) {
            mOutput = new ByteArrayInputStream(
                    output.getBytes(StandardCharsets.UTF_8)) {
                @Override
                public void close() {
                    outputClosed = true;
                }
            };
            mCompletes = completes;
            mExitCode = exitCode;
        }

        @Override
        public OutputStream getOutputStream() {
            return mInput;
        }

        @Override
        public InputStream getInputStream() {
            return mOutput;
        }

        @Override
        public InputStream getErrorStream() {
            return mError;
        }

        @Override
        public int waitFor() throws InterruptedException {
            mAlive = false;
            return mExitCode;
        }

        @Override
        public boolean waitFor(
                final long timeout,
                final TimeUnit unit) {
            if (!mAlive) {
                return true;
            }
            if (mCompletes) {
                mAlive = false;
                return true;
            }
            return false;
        }

        @Override
        public int exitValue() {
            if (mAlive) {
                throw new IllegalThreadStateException();
            }
            return mExitCode;
        }

        @Override
        public void destroy() {
            destroyed = true;
            mAlive = false;
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return mAlive;
        }
    }
}

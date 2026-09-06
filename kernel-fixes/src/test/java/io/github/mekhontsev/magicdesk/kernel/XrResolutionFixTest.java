package io.github.mekhontsev.magicdesk.kernel;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import io.github.mekhontsev.magicdesk.BoundedProcessRunner;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class XrResolutionFixTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void verifiedExtractionsHaveDistinctPrivateAndRootPaths() throws Exception {
        final byte[] bytes = "fixture module".getBytes(StandardCharsets.UTF_8);
        final String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        final File first = XrResolutionFix.extractModule(
                temporary.getRoot(), new ByteArrayInputStream(bytes), hash);
        final File second = XrResolutionFix.extractModule(
                temporary.getRoot(), new ByteArrayInputStream(bytes), hash);
        assertNotEquals(first, second);
        assertArrayEquals(bytes, Files.readAllBytes(first.toPath()));
        assertArrayEquals(bytes, Files.readAllBytes(second.toPath()));
        final String firstCommand = XrResolutionFix.loadCommand(first);
        assertNotEquals(firstCommand, XrResolutionFix.loadCommand(second));
        assertTrue(firstCommand.contains("/data/local/tmp/magicdesk-" + first.getName()));
        assertTrue(firstCommand.contains(" EXIT; trap 'exit 1' HUP INT TERM; "));
        assertTrue(firstCommand.indexOf("INVALID_MODULE:") < firstCommand.indexOf("trap "));
        assertTrue(firstCommand.indexOf("trap ") < firstCommand.indexOf("/system/bin/cp "));
        assertTrue(firstCommand.contains("ERROR:chmod_failed"));
    }

    @Test
    public void rootCommandAndCleanupTrapHaveValidShellQuotingWithoutExecutingThem()
            throws Exception {
        final String command = XrResolutionFix.loadCommand(new File("/private/file ' name.ko"));
        final var result = BoundedProcessRunner.run(
                new ProcessBuilder("sh", "-n", "-c", command)
                        .redirectErrorStream(true).start(), 1000, 1024);
        assertEquals(result.output, 0, result.exitCode);
    }

    @Test
    public void checksumReadAndCloseFailuresRemoveIncompleteExtractions() throws Exception {
        final byte[] bytes = "fixture".getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> XrResolutionFix.extractModule(
                temporary.getRoot(), new ByteArrayInputStream(bytes), "invalid hash"));
        assertEquals(0, temporary.getRoot().list().length);
        final InputStream brokenRead = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("read failed");
            }
        };
        assertThrows(IOException.class, () -> XrResolutionFix.extractModule(
                temporary.getRoot(), brokenRead, "unused"));
        assertEquals(0, temporary.getRoot().list().length);
        final String hash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        final InputStream brokenClose = new ByteArrayInputStream(bytes) {
            @Override
            public void close() throws IOException {
                throw new IOException("close failed");
            }
        };
        assertThrows(IOException.class, () -> XrResolutionFix.extractModule(
                temporary.getRoot(), brokenClose, hash));
        assertEquals(0, temporary.getRoot().list().length);
    }

    @Test
    public void parsesLastRecognizedResultAfterRootManagerOutput() {
        assertEquals(XrResolutionFix.Code.ACTIVATED,
                XrResolutionFix.parseResult("root granted\r\nACTIVATED\r\n").code);
        assertEquals(XrResolutionFix.Code.ACTIVE,
                XrResolutionFix.parseResult("ACTIVE\n").code);
        assertEquals(XrResolutionFix.Code.UNSUPPORTED_KERNEL,
                XrResolutionFix.parseResult("UNSUPPORTED_KERNEL:other\n").code);
        assertEquals(XrResolutionFix.Code.UNSUPPORTED_DRIVER,
                XrResolutionFix.parseResult("UNSUPPORTED_DRIVER:hash\n").code);
        assertEquals(XrResolutionFix.Code.INVALID_MODULE,
                XrResolutionFix.parseResult("INVALID_MODULE:hash\n").code);
        assertEquals("copy_failed", XrResolutionFix.parseResult("ERROR:copy_failed\n").detail);
        assertEquals(XrResolutionFix.Code.FAILED,
                XrResolutionFix.parseResult("denied\n").code);
    }

    @Test
    public void processExitAndReadFailureAlwaysReleaseStreams() throws Exception {
        final var success = new FakeProcess("ACTIVATED\n", true, 0);
        assertEquals("ACTIVATED\n", XrResolutionFix.readRootResult(success));
        assertClosed(success);
        final var denied = new FakeProcess("permission denied", true, 1);
        assertTrue(assertThrows(IOException.class,
                () -> XrResolutionFix.readRootResult(denied)).getMessage()
                .contains("root command failed 1: permission denied"));
        assertClosed(denied);
        final var broken = new FakeProcess("", true, 0);
        broken.output = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("read failed");
            }

            @Override
            public void close() {
                broken.outputClosed = true;
            }
        };
        assertTrue(assertThrows(IOException.class,
                () -> XrResolutionFix.readRootResult(broken)).getMessage()
                .contains("cannot read command output"));
        assertClosed(broken);
    }

    @Test
    public void oversizedOutputIsDrainedButNeverAcceptedAsActivationSuccess() {
        final var process = new FakeProcess("ACTIVATED\n" + "x".repeat(64 * 1024), true, 0);
        assertTrue(assertThrows(IOException.class,
                () -> XrResolutionFix.readRootResult(process)).getMessage()
                .contains("exceeded 32768 bytes"));
        assertClosed(process);
    }

    @Test
    public void stalledRootAuthorizationHasABoundedDeadlineAndTermination() {
        final var process = new FakeProcess("", false, 0);
        assertTrue(assertThrows(IOException.class,
                () -> XrResolutionFix.readRootResult(process)).getMessage()
                .contains("timed out after 30000 ms"));
        assertTrue(process.firstTimeoutMillis > 0);
        assertTrue(process.firstTimeoutMillis <= 30_000);
        assertTrue(process.destroyed);
        assertFalse(process.isAlive());
        assertClosed(process);
    }

    @Test(timeout = 5000)
    public void interruptedOutputDrainReleasesProcessAndRestoresInterrupt() throws Exception {
        final var process = new FakeProcess("", true, 0);
        final var reading = new CountDownLatch(1);
        final var closed = new CountDownLatch(1);
        final var failure = new AtomicReference<Throwable>();
        process.output = new InputStream() {
            @Override
            public int read() throws IOException {
                reading.countDown();
                try {
                    if (!closed.await(2, TimeUnit.SECONDS)) {
                        throw new IOException("fixture output was not closed");
                    }
                    return -1;
                } catch (InterruptedException error) {
                    throw new IOException(error);
                }
            }

            @Override
            public void close() {
                process.outputClosed = true;
                closed.countDown();
            }
        };
        final Thread worker = new Thread(() -> {
            try {
                final IOException error = assertThrows(IOException.class,
                        () -> XrResolutionFix.readRootResult(process));
                assertTrue(error.getCause() instanceof InterruptedException);
                assertTrue(Thread.currentThread().isInterrupted());
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        worker.start();
        try {
            assertTrue(reading.await(1, TimeUnit.SECONDS));
            worker.interrupt();
            worker.join(1000);
            assertFalse(worker.isAlive());
            assertEquals(null, failure.get());
            assertClosed(process);
        } finally {
            closed.countDown();
            worker.interrupt();
            worker.join(1000);
        }
    }

    private static void assertClosed(final FakeProcess process) {
        assertTrue(process.inputClosed);
        assertTrue(process.outputClosed);
        assertTrue(process.errorClosed);
    }

    private static final class FakeProcess extends Process {
        private final boolean completes;
        private final int exitCode;
        private volatile boolean alive = true;
        boolean destroyed;
        volatile boolean inputClosed;
        volatile boolean outputClosed;
        volatile boolean errorClosed;
        long firstTimeoutMillis;
        InputStream output;
        private final OutputStream input = new ByteArrayOutputStream() {
            @Override
            public void close() {
                inputClosed = true;
            }
        };
        private final InputStream error = new ByteArrayInputStream(new byte[0]) {
            @Override
            public void close() {
                errorClosed = true;
            }
        };

        FakeProcess(final String text, final boolean completes, final int exitCode) {
            this.completes = completes;
            this.exitCode = exitCode;
            output = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)) {
                @Override
                public void close() {
                    outputClosed = true;
                }
            };
        }

        @Override
        public OutputStream getOutputStream() {
            return input;
        }

        @Override
        public InputStream getInputStream() {
            return output;
        }

        @Override
        public InputStream getErrorStream() {
            return error;
        }

        @Override
        public int waitFor() {
            throw new AssertionError("unbounded process wait");
        }

        @Override
        public boolean waitFor(final long timeout, final TimeUnit unit) {
            assertTrue("stdin must be closed before waiting", inputClosed);
            if (firstTimeoutMillis == 0) {
                firstTimeoutMillis = unit.toMillis(timeout);
            }
            if (completes) {
                alive = false;
            }
            return !alive;
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException();
            }
            return exitCode;
        }

        @Override
        public void destroy() {
            destroyed = true;
            alive = false;
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }
}

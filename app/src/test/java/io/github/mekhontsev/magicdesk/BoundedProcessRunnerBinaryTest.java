package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class BoundedProcessRunnerBinaryTest {
    @Test(timeout = 10_000L)
    public void drainsBothRealPipesAndPreservesEveryByte() throws Exception {
        final Process process = child("pressure");
        final var result = BoundedProcessRunner.runBinary(process, 5000, 65536, 262144);
        assertEquals(7, result.exitCode);
        assertArrayEquals(binary(), result.stdout);
        assertEquals("e".repeat(262144), result.stderr);
        assertFalse(result.stdoutTruncated);
        assertFalse(result.stderrTruncated);
        assertFalse(process.isAlive());
    }

    @Test(timeout = 10_000L)
    public void drainsBeyondBothIndependentCaps() throws Exception {
        final Process process = child("pressure");
        final var result = BoundedProcessRunner.runBinary(process, 5000, 257, 11);
        assertEquals(7, result.exitCode);
        assertArrayEquals(Arrays.copyOf(binary(), 257), result.stdout);
        assertEquals("e".repeat(11), result.stderr);
        assertTrue(result.stdoutTruncated);
        assertTrue(result.stderrTruncated);
        assertFalse(process.isAlive());
    }

    @Test
    public void stderrOverflowDoesNotMarkStdoutTruncated() throws Exception {
        final StubProcess process = new StubProcess(
                new ByteArrayInputStream(new byte[]{0, (byte) 0xFF}),
                new ByteArrayInputStream("diagnostic".getBytes(StandardCharsets.UTF_8)));
        final var result = BoundedProcessRunner.runBinary(process, 1000, 2, 0);
        assertArrayEquals(new byte[]{0, (byte) 0xFF}, result.stdout);
        assertEquals("", result.stderr);
        assertFalse(result.stdoutTruncated);
        assertTrue(result.stderrTruncated);
    }

    @Test
    public void collectorFailureIsReportedWithStderrAndClosesStreams() throws Exception {
        final StubProcess process = new StubProcess(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("broken binary pipe");
            }
        }, new ByteArrayInputStream("capture failed".getBytes(StandardCharsets.UTF_8)));
        final IOException error = assertThrows(IOException.class,
                () -> BoundedProcessRunner.runBinary(process, 1000, 1024, 1024));
        assertTrue(error.getMessage().contains("capture failed"));
        assertEquals("broken binary pipe", error.getCause().getMessage());
        assertTrue(process.stdinClosed);
    }

    @Test(timeout = 5000L)
    public void inheritedPipesShareTheDeadlineAndCloseOnFailure() throws Exception {
        final BlockingPipe stdout = new BlockingPipe();
        final BlockingPipe stderr = new BlockingPipe();
        final StubProcess process = new StubProcess(stdout, stderr);
        final long started = System.nanoTime();
        final IOException error = assertThrows(IOException.class,
                () -> BoundedProcessRunner.runBinary(process, 100, 1024, 1024));
        assertTrue(error.getMessage().contains("output did not close"));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500);
        assertEquals(0L, stdout.closed.getCount());
        assertEquals(0L, stderr.closed.getCount());
        assertTrue(process.stdinClosed);
    }

    @Test(timeout = 10_000L)
    public void realHungChildIsTerminatedAtTheCommandDeadline() throws Exception {
        try (ServerSocket gate = new ServerSocket(0)) {
            gate.setSoTimeout(3000);
            final Process process = child("hold", Integer.toString(gate.getLocalPort()));
            try (Socket child = gate.accept()) {
                final IOException error = assertThrows(IOException.class,
                        () -> BoundedProcessRunner.runBinary(process, 100, 1024, 1024));
                assertTrue(error.getMessage().contains("timed out"));
                assertFalse(process.isAlive());
            } finally {
                process.destroyForcibly();
                assertTrue(process.waitFor(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    public void ignoredGracefulTerminationIsEscalatedAndReaped() throws Exception {
        final StubProcess process = new StubProcess(
                new ByteArrayInputStream(new byte[0]), new ByteArrayInputStream(new byte[0])) {
            @Override
            public boolean waitFor(long timeout, TimeUnit unit) {
                return !alive;
            }

            @Override
            public void destroy() {
                // Reproduce a process that ignores graceful termination.
            }
        };
        assertThrows(IOException.class,
                () -> BoundedProcessRunner.runBinary(process, 100, 0, 0));
        assertTrue(process.forced);
        assertFalse(process.isAlive());
        assertTrue(process.stdinClosed);
    }

    private static Process child(final String... arguments) throws Exception {
        final String[] command = new String[4 + arguments.length];
        command[0] = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        command[1] = "-cp";
        // Gradle's worker classpath need not contain its test-classloader URLs.
        command[2] = Path.of(Child.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI()).toString();
        command[3] = Child.class.getName();
        System.arraycopy(arguments, 0, command, 4, arguments.length);
        return new ProcessBuilder(command).start();
    }

    private static byte[] binary() {
        final byte[] bytes = new byte[65536];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) i;
        }
        return bytes;
    }

    public static final class Child {
        public static void main(final String[] arguments) throws Exception {
            if (arguments[0].equals("hold")) {
                try (Socket gate = new Socket("127.0.0.1", Integer.parseInt(arguments[1]))) {
                    gate.getInputStream().read();
                }
                return;
            }
            if (System.in.read() != -1) {
                throw new AssertionError("runner did not close stdin");
            }
            final byte[] errors = "e".repeat(131072).getBytes(StandardCharsets.UTF_8);
            System.err.write(errors);
            System.out.write(binary());
            System.err.write(errors);
            System.exit(7);
        }
    }

    private static final class BlockingPipe extends InputStream {
        final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            try {
                if (!closed.await(3, TimeUnit.SECONDS)) {
                    throw new IOException("fixture pipe leaked");
                }
                return -1;
            } catch (InterruptedException error) {
                throw new IOException(error);
            }
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static class StubProcess extends Process {
        final InputStream stdout;
        final InputStream stderr;
        boolean alive = true;
        boolean stdinClosed;
        boolean forced;

        StubProcess(final InputStream stdout, final InputStream stderr) {
            this.stdout = stdout;
            this.stderr = stderr;
        }

        @Override public InputStream getInputStream() { return stdout; }
        @Override public InputStream getErrorStream() { return stderr; }
        @Override public OutputStream getOutputStream() {
            return new ByteArrayOutputStream() {
                @Override public void close() { stdinClosed = true; }
            };
        }
        @Override public boolean waitFor(long timeout, TimeUnit unit) {
            assertTrue(stdinClosed);
            alive = false;
            return true;
        }
        @Override public int waitFor() { throw new AssertionError("unbounded wait"); }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { alive = false; }
        @Override public Process destroyForcibly() { forced = true; alive = false; return this; }
        @Override public boolean isAlive() { return alive; }
    }
}

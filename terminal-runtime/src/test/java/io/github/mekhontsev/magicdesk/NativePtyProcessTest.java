package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public final class NativePtyProcessTest {
    private static final class Child extends Process {
        final CountDownLatch reading = new CountDownLatch(1);
        final CountDownLatch writing = new CountDownLatch(1);
        final CountDownLatch destroyed = new CountDownLatch(1);
        final AtomicInteger kills = new AtomicInteger();
        final boolean header;
        Child(boolean header) { this.header = header; }
        private void untilDestroyed() throws IOException {
            try {
                // EVENT_WAIT: fake child destruction releases blocked IO; expiry fails the test.
                if (!destroyed.await(3, TimeUnit.SECONDS)) throw new IOException("Child was not cancelled");
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
        }
        @Override public InputStream getInputStream() {
            if (header) return new ByteArrayInputStream("MAGICDESK_PTY 42 100 /dev/pts/3\n".getBytes(StandardCharsets.US_ASCII));
            return new InputStream() {
                @Override public int read() throws IOException { reading.countDown(); untilDestroyed(); return -1; }
            };
        }
        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int value) throws IOException { writing.countDown(); untilDestroyed(); throw new IOException("Child closed"); }
            };
        }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() throws InterruptedException { destroyed.await(); return 0; }
        @Override public int exitValue() { if (isAlive()) throw new IllegalThreadStateException(); return 0; }
        @Override public boolean isAlive() { return destroyed.getCount()!=0; }
        @Override public void destroy() { kills.incrementAndGet(); destroyed.countDown(); }
    }

    @Test public void cancellationDoesNotWaitBehindBlockedWriter() throws Exception {
        var child = new Child(true);
        var resources = new OperationResources();
        try (var pty = new NativePtyProcess(child, resources)) {
            assertEquals(new PtyEndpoint(42,100,"/dev/pts/3"),pty.endpoint());
            var writer = CompletableFuture.runAsync(() -> {
                assertThrows(IOException.class, () -> pty.write(new byte[]{1}));
            });
            // EVENT_WAIT: input pipe is blocked; expiry fails the fixture.
            assertTrue(child.writing.await(1,TimeUnit.SECONDS));
            resources.close();
            writer.get(1,TimeUnit.SECONDS);
            assertThrows(IOException.class, () -> pty.resize(24,80));
        }
        resources.close();
        assertEquals(1,child.kills.get());
    }

    @Test public void cancellationOwnsProcessBeforeHandshakeCompletes() throws Exception {
        var child = new Child(false);
        var resources = new OperationResources();
        var startup = CompletableFuture.runAsync(() -> {
            assertThrows(IOException.class, () -> new NativePtyProcess(child,resources));
        });
        // EVENT_WAIT: native startup header is pending; expiry fails the fixture.
        assertTrue(child.reading.await(1,TimeUnit.SECONDS));
        resources.close();
        startup.get(1,TimeUnit.SECONDS);
        assertEquals(1,child.kills.get());
    }

    @Test public void previouslyCancelledOwnerRejectsLateProcess() {
        var child = new Child(false);
        var resources = new OperationResources();
        resources.close();
        assertThrows(IOException.class, () -> new NativePtyProcess(child,resources));
        assertEquals(1,child.kills.get());
    }
}

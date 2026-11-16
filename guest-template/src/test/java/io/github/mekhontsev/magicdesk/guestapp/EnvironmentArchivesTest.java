package io.github.mekhontsev.magicdesk.guestapp;

import static org.junit.Assert.*;
import io.github.mekhontsev.magicdesk.OperationResources;
import java.io.*;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class EnvironmentArchivesTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private EnvironmentArchives.Command command(String script) {
        String shell=Files.isExecutable(Path.of("/bin/sh")) ? "/bin/sh" : "/system/bin/sh";
        return arguments -> {
            var words=new java.util.ArrayList<>(java.util.List.of(shell,"-c",script,"archive"));
            words.addAll(java.util.List.of(arguments)); return new ProcessBuilder(words);
        };
    }
    private void empty(Path cache) throws IOException {
        try (var files=Files.list(cache)) { assertEquals(0,files.count()); }
    }
    @Test public void restoreAndBackupShareStagingWithoutReplacingTheStore() throws Exception {
        Path cache=temporary.newFolder("cache").toPath(), store=temporary.getRoot().toPath().resolve("Linux's data");
        byte[] bytes="guest archive payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (var resources=new OperationResources()) {
            EnvironmentArchives.restore(cache,store,() -> new ByteArrayInputStream(bytes),
                    command("test \"$1\" = restore && mkdir \"$3\" && cp \"$2\" \"$3/payload\""),resources);
            assertArrayEquals(bytes,Files.readAllBytes(store.resolve("payload"))); empty(cache);
            var destination=new ByteArrayOutputStream();
            EnvironmentArchives.backup(cache,store,() -> destination,
                    command("test \"$1\" = backup && test ! -e \"$3\" && cp \"$2/payload\" \"$3\""),resources);
            assertArrayEquals(bytes,destination.toByteArray()); empty(cache);
            assertArrayEquals(bytes,Files.readAllBytes(store.resolve("payload")));
        }
    }
    @Test public void nativeFailureDoesNotOpenDestinationAndRemovesPrivateStaging() throws Exception {
        Path cache=temporary.newFolder("cache").toPath();
        var opened=new AtomicBoolean();
        try (var resources=new OperationResources()) {
            IOException error=assertThrows(IOException.class,() -> EnvironmentArchives.backup(cache,Path.of("/unused"),
                    () -> { opened.set(true); return new ByteArrayOutputStream(); },
                    command("mkdir \"${3%/*}/native-staging\"; echo rejected; exit 7"),resources));
            assertTrue(error.getMessage().contains("rejected")); assertFalse(opened.get()); empty(cache);
        }
    }
    @Test public void destinationCloseFailureIsNotReportedAsSuccessfulBackup() throws Exception {
        Path cache=temporary.newFolder("cache").toPath();
        try (var resources=new OperationResources()) {
            IOException error=assertThrows(IOException.class,() -> EnvironmentArchives.backup(cache,Path.of("/unused"),
                    () -> new ByteArrayOutputStream() {
                        @Override public void close() throws IOException { throw new IOException("document commit failed"); }
                    },command("printf payload > \"$3\""),resources));
            assertEquals("document commit failed",error.getMessage()); empty(cache);
        }
    }
    @Test public void cancellationClosesBlockedIngressBeforeNativeImport() throws Exception {
        Path cache=temporary.newFolder("cache").toPath();
        var reading=new CountDownLatch(1); var closed=new CountDownLatch(1); var launched=new AtomicBoolean();
        try (var resources=new OperationResources()) {
            var operation=CompletableFuture.runAsync(() -> {
                assertThrows(IOException.class,() -> EnvironmentArchives.restore(cache,Path.of("/unused"),
                        () -> new InputStream() {
                            @Override public int read() throws IOException {
                                reading.countDown();
                                try {
                                    // EVENT_WAIT: cancellation closes the source; timeout fails the fixture.
                                    if (!closed.await(5,TimeUnit.SECONDS)) throw new AssertionError("Source not cancelled");
                                } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                                throw new IOException("Source closed");
                            }
                            @Override public void close() { closed.countDown(); }
                        },arguments -> { launched.set(true); throw new AssertionError("Cancelled import started"); },resources));
            });
            // EVENT_WAIT: ingress reaches the blocked read before cancellation.
            assertTrue(reading.await(5,TimeUnit.SECONDS)); resources.close();
            operation.get(5,TimeUnit.SECONDS); assertFalse(launched.get()); empty(cache);
        }
    }
    @Test public void cancelledOwnerDoesNotAcquireStreamsOrCreateStaging() throws Exception {
        Path cache=temporary.newFolder("cache").toPath();
        var resources=new OperationResources(); resources.close();
        assertThrows(IOException.class,() -> EnvironmentArchives.restore(cache,Path.of("/unused"),
                () -> { throw new AssertionError("Unexpected stream"); },
                arguments -> { throw new AssertionError("Unexpected process"); },resources));
        empty(cache);
    }

    @Test public void cancellationAlsoClosesBlockedExportDestination() throws Exception {
        Path cache=temporary.newFolder("cache").toPath();
        var writing=new CountDownLatch(1); var closed=new CountDownLatch(1);
        try (var resources=new OperationResources()) {
            var operation=CompletableFuture.runAsync(() -> {
                assertThrows(IOException.class,() -> EnvironmentArchives.backup(cache,Path.of("/unused"),
                        () -> new OutputStream() {
                            @Override public void write(int value) throws IOException {
                                writing.countDown();
                                try {
                                    // EVENT_WAIT: cancellation closes the destination; timeout fails the fixture.
                                    if (!closed.await(5,TimeUnit.SECONDS)) throw new AssertionError("Destination not cancelled");
                                } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                                throw new IOException("Destination closed");
                            }
                            @Override public void close() { closed.countDown(); }
                        },command("printf payload > \"$3\""),resources));
            });
            // EVENT_WAIT: the document write is pending before cancellation.
            assertTrue(writing.await(5,TimeUnit.SECONDS)); resources.close();
            operation.get(5,TimeUnit.SECONDS); empty(cache);
        }
    }
}

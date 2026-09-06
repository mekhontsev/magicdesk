package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public final class ContentProviderFileAccessTest {
    @Test
    public void cancelledRequestNeverOpensAResource() {
        final AtomicInteger opens = new AtomicInteger();
        final CancellationException cancelled = new CancellationException();

        assertSame(cancelled, assertThrows(CancellationException.class,
                () -> ContentProviderFileAccess.openChecked(() -> {
                    opens.incrementAndGet();
                    return new Resource();
                }, () -> { throw cancelled; })));
        assertEquals(0, opens.get());
    }

    @Test
    public void cancellationDuringOpenClosesTheUnclaimedResource() {
        final Resource resource = new Resource();
        final CancellationException cancelled = new CancellationException();
        final AtomicInteger checks = new AtomicInteger();

        assertSame(cancelled, assertThrows(CancellationException.class,
                () -> ContentProviderFileAccess.openChecked(() -> resource, () -> {
                    if (checks.incrementAndGet() == 2) {
                        throw cancelled;
                    }
                })));
        assertEquals(1, resource.closes);
    }

    @Test
    public void cleanupFailureDoesNotReplaceCancellation() {
        final Resource resource = new Resource();
        final IOException cleanup = new IOException("close failed");
        resource.closeFailure = cleanup;
        final CancellationException cancelled = new CancellationException();
        final AtomicInteger checks = new AtomicInteger();

        assertSame(cancelled, assertThrows(CancellationException.class,
                () -> ContentProviderFileAccess.openChecked(() -> resource, () -> {
                    if (checks.incrementAndGet() == 2) {
                        throw cancelled;
                    }
                })));
        assertEquals(1, resource.closes);
        assertSame(cleanup, cancelled.getSuppressed()[0]);
    }

    @Test
    public void successfulOpenTransfersResourceWithoutClosingIt() throws IOException {
        final Resource resource = new Resource();
        final AtomicInteger checks = new AtomicInteger();

        assertSame(resource, ContentProviderFileAccess.openChecked(
                () -> resource, checks::incrementAndGet));
        assertEquals(2, checks.get());
        assertEquals(0, resource.closes);
    }

    @Test
    public void openFailureRemainsTheOriginalFailure() {
        final IOException openFailure = new IOException("provider unavailable");
        final AtomicInteger checks = new AtomicInteger();

        assertSame(openFailure, assertThrows(IOException.class,
                () -> ContentProviderFileAccess.openChecked(() -> {
                    throw openFailure;
                }, checks::incrementAndGet)));
        assertEquals(1, checks.get());
    }

    private static final class Resource implements Closeable {
        int closes;
        IOException closeFailure;

        @Override
        public void close() throws IOException {
            closes++;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }
}

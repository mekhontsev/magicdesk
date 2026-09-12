package io.github.mekhontsev.magicdesk;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.*;

public final class DisplayRemovalRequestsTest {
    private final DisplayRemovalRequests requests = new DisplayRemovalRequests();
    private final AtomicInteger starts = new AtomicInteger();
    private final AtomicReference<Consumer<Boolean>> completion = new AtomicReference<>();

    private CompletableFuture<Boolean> submit(DesktopDisplayInfo... displays) throws IOException {
        return requests.submit(7, "owned", displays, callback -> {
            starts.incrementAndGet();
            completion.set(callback);
        });
    }

    @Test public void timedOutObserverAndConcurrentRetryShareOneRemoval() throws Exception {
        var first = submit(display("owned", true));
        try {
            first.get(0, TimeUnit.MILLISECONDS);
            fail("unfinished removal was reported complete");
        } catch (TimeoutException expected) { }
        var second = submit(display("owned", true));
        assertSame(first, second);
        assertEquals(1, starts.get());
        assertFalse(first.isCancelled());
        completion.get().accept(true);
        assertTrue(first.get());
        assertTrue(second.get());
    }

    @Test public void releaseReceiptBridgesLateDisplayRemovalPublication() throws Exception {
        var first = submit(display("owned", true));
        completion.get().accept(true);
        assertSame(first, submit(display("owned", false)));
        assertTrue(submit().get());
        assertEquals(1, starts.get());
    }

    @Test public void absenceIsSuccessfulWithoutStartingCleanup() throws Exception {
        assertTrue(submit().get());
        assertEquals(0, starts.get());
    }

    @Test public void failedAttemptCanBeRetried() throws Exception {
        var first = submit(display("owned", true));
        completion.get().accept(false);
        assertFalse(first.get());
        assertNotSame(first, submit(display("owned", true)));
        assertEquals(2, starts.get());
    }

    @Test public void reusedIdCannotReleaseReplacementEvenWhileOldRemovalRuns() throws Exception {
        submit(display("owned", true));
        assertThrows(IOException.class, () -> submit(display("replacement", true)));
        completion.get().accept(true);
        assertThrows(IOException.class, () -> submit(display("replacement", true)));
        assertEquals(1, starts.get());
    }

    @Test public void cannotDeleteForeignOrBuiltInDisplays() {
        assertThrows(IOException.class, () -> submit(display("owned", false)));
        assertThrows(IOException.class, () -> submit(new DesktopDisplayInfo(
                7, "owned", "Internal", "internal", 800, 600, 160, false, true)));
        assertThrows(IllegalArgumentException.class, () -> requests.submit(0, "phone",
                new DesktopDisplayInfo[0], ignored -> fail()));
        assertThrows(IllegalArgumentException.class, () -> requests.submit(-1, "owned",
                new DesktopDisplayInfo[0], ignored -> fail()));
        assertThrows(IllegalArgumentException.class, () -> requests.submit(7, " ",
                new DesktopDisplayInfo[0], ignored -> fail()));
        assertEquals(0, starts.get());
    }

    @Test public void dispatchExceptionDoesNotRetainAnUnfinishableRequest() throws Exception {
        assertThrows(IllegalStateException.class, () -> requests.submit(7, "owned",
                new DesktopDisplayInfo[]{display("owned", true)}, ignored -> {
                    throw new IllegalStateException("rejected");
                }));
        assertFalse(submit(display("owned", true)).isDone());
        assertEquals(1, starts.get());
    }

    private static DesktopDisplayInfo display(String uniqueId, boolean owned) {
        return new DesktopDisplayInfo(7, uniqueId, "Test", "virtual", 800, 600, 160, true, owned);
    }
}

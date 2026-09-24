package io.github.mekhontsev.magicdesk.wayland;
import io.github.mekhontsev.magicdesk.hosted.FramePresentation;

import static org.junit.Assert.*;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import org.junit.Test;

public class FramePresentationTest {
    @Test public void staleFrameCannotCompleteReplacement() {
        var state = new FramePresentation();
        var old = new CompletableFuture<Void>();
        long first = state.begin(old);
        var next = new CompletableFuture<Void>();
        long second = state.begin(next);
        assertTrue(old.isCancelled());
        assertFalse(state.accepts(first));
        state.submitted(first);
        assertFalse(next.isDone());
        state.submitted(second);
        assertTrue(next.isDone());
        assertFalse(next.isCompletedExceptionally());
    }

    @Test public void invalidationRevokesReceiptsAndAcceptsNoOldFrame() {
        var state = new FramePresentation();
        var pending = new CompletableFuture<Void>();
        long generation = state.begin(pending);
        state.invalidate("closed");
        state.submitted(generation);
        assertTrue(pending.isCancelled());
        assertFalse(state.accepts(generation));
    }

    @Test public void surfaceReplacementRevokesWorkerFailureBeforeItsCallback() throws Exception {
        var state = new FramePresentation();
        long old = state.begin(new CompletableFuture<>());
        var replacement = new CompletableFuture<Void>();
        long current = state.begin(replacement);
        var worker = new Thread(() -> state.fail(old, new IOException("abandoned Surface")));
        worker.start();
        worker.join();
        assertFalse(replacement.isDone());
        state.submitted(current);
        assertFalse(replacement.isCompletedExceptionally());
    }

    @Test public void callbacksCanUseAnotherThreadWithoutHoldingReceiptLock() throws Exception {
        var state = new FramePresentation();
        var first = new CompletableFuture<Void>();
        var second = new CompletableFuture<Void>();
        long current = state.begin(first);
        var callback = first.thenRun(() -> {
            var worker = new Thread(() -> state.begin(second));
            worker.start();
            // EVENT_WAIT: the worker enters the receipt from a completion callback; timeout detects a held lock.
            try { worker.join(1_000); }
            catch (InterruptedException error) { throw new AssertionError(error); }
            assertFalse("Completion must not hold the receipt lock", worker.isAlive());
        });
        state.submitted(current);
        callback.join();
        assertFalse(second.isDone());
        state.submitted(state.generation());
        assertTrue(second.isDone());
    }

    @Test public void failureDoesNotCompleteAFutureRequest() {
        var state = new FramePresentation();
        var first = new CompletableFuture<Void>();
        long failed = state.begin(first);
        state.fail(new IOException("unavailable Surface"));
        assertTrue(first.isCompletedExceptionally());
        var second = new CompletableFuture<Void>();
        long generation = state.begin(second);
        state.submitted(failed);
        assertFalse(second.isDone());
        state.submitted(generation);
        assertTrue(second.isDone());
    }

    @Test public void completionMayReenterWithoutDiscardingANewReceipt() {
        var state = new FramePresentation();
        var first = new CompletableFuture<Void>();
        var second = new CompletableFuture<Void>();
        long generation = state.begin(first);
        first.thenRun(() -> state.begin(second));
        state.submitted(generation);
        assertFalse(second.isDone());
        state.submitted(state.generation());
        assertTrue(second.isDone());
    }

    @Test public void viewportKeepsNegativePaintOriginAndEnforcesRenderBudget() {
        assertEquals(-5, new WaylandViewport(-5, -6, 80, 40).x());
        assertThrows(IllegalArgumentException.class, () -> new WaylandViewport(0, 0, 4097, 1));
        assertThrows(IllegalArgumentException.class, () -> new WaylandViewport(-16385, 0, 80, 40));
        assertThrows(IllegalArgumentException.class, () -> new WaylandViewport(0, 0, 0, 40));
    }

    @Test public void supersessionMayReenterWithoutReplacingTheNewestReceipt() {
        var state = new FramePresentation();
        var first = new CompletableFuture<Void>();
        var second = new CompletableFuture<Void>();
        var third = new CompletableFuture<Void>();
        state.begin(first);
        first.whenComplete((value, error) -> state.begin(third));
        long obsolete = state.begin(second);
        assertTrue(second.isCancelled());
        assertFalse(state.accepts(obsolete));
        state.submitted(state.generation());
        assertTrue(third.isDone());
    }
}

package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class FileOperationStateTest {
    private final FileOperationState state = new FileOperationState();

    @Test
    public void cancelledPendingRequestCannotCompleteItsReplacement() {
        final var old = state.begin(1, 2, 10L);
        assertSame(old, state.cancel());
        final var next = state.begin(2, 3, 11L);
        final var snapshot = state.snapshot();
        assertFalse(state.progress(old, 1L, 1, 2, "old", 100L));
        assertFalse(state.finish(old, 1L, true, "Completed"));
        assertFalse(state.started(old, 1L));
        state.fail(old, "late start failure");
        assertSame(snapshot, state.snapshot());
        assertTrue(state.started(next, 2L));
    }

    @Test
    public void completionBeforeStartReplyRemainsTerminal() {
        final var request = state.begin(1, 2, -1L);
        assertTrue(state.finish(request, 7L, true, "Completed"));
        final var finished = state.snapshot();
        assertTrue(state.started(request, 7L));
        assertFalse(state.progress(request, 7L, 1, 2, "late", 20L));
        assertFalse(state.finish(request, 7L, true, "duplicate"));
        state.fail(request, "late failure");
        assertSame(finished, state.snapshot());
    }

    @Test
    public void progressBeforeStartReplyBindsRemoteIdentity() {
        final var request = state.begin(1, 3, -1L);
        assertTrue(state.progress(request, 5L, 1, 3, "file", 20L));
        assertTrue(state.started(request, 5L));
        assertEquals(1, state.snapshot().completedItems);
        assertFalse(state.progress(request, 6L, 2, 3, "wrong", 40L));
        assertFalse(state.finish(request, 6L, true, "wrong"));
    }

    @Test
    public void disconnectedServiceCannotCompleteReusedOperationId() {
        final var old = state.begin(1, 2, -1L);
        state.started(old, 1L);
        state.disconnect("service died");
        final var next = state.begin(1, 2, -1L);
        assertFalse(state.finish(old, 1L, true, "Completed"));
        assertTrue(state.started(next, 1L));
        assertTrue(state.finish(next, 1L, true, "Completed"));
    }

    @Test
    public void failureRetainsActualProgressInsteadOfClaimingAllItemsCompleted() {
        final var request = state.begin(1, 3, -1L);
        state.progress(request, 1L, 1, 3, "file", 20L);
        state.finish(request, 1L, false, "Cancelled");
        assertEquals(1, state.snapshot().completedItems);
        assertEquals(20L, state.snapshot().bytesCompleted);
        assertFalse(state.snapshot().successful);
    }

    @Test
    public void runningCancellationWaitsForRemoteCompletion() {
        final var request = state.begin(1, 3, -1L);
        state.started(request, 4L);
        assertSame(request, state.cancel());
        assertTrue(state.snapshot().isBusy());
        assertNull(state.begin(1, 3, -1L));
        assertTrue(state.finish(request, 4L, false, "Cancelled"));
        assertFalse(state.snapshot().isBusy());
    }

    @Test
    public void invalidRemoteIdsCannotBindRequest() {
        final var request = state.begin(1, 3, -1L);
        assertFalse(state.started(request, 0L));
        assertFalse(state.finish(request, -1L, true, "invalid"));
        assertFalse(state.progress(request, -1L, 1, 3, "invalid", 1L));
        assertNull(state.begin(1, 3, -1L));
    }
}

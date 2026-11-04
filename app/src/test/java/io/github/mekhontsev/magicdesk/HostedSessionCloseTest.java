package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class HostedSessionCloseTest {
    @Test public void waitsForActualDestructionAndKeepsNewSaveDialogs() {
        var close = new HostedSessionClose();
        var requests = new ArrayList<Long>();
        close.request(List.of(1L, 2L), requests::add);
        assertEquals(List.of(1L, 2L), requests);
        assertFalse(close.completed(true, true));
        close.request(List.of(3L), requests::add);
        assertEquals(List.of(1L, 2L), requests);
        assertFalse(close.completed(false, false));
        assertTrue(close.completed(true, false));
    }

    @Test public void cancelAndFailedRequestsDoNotForceStop() {
        var close = new HostedSessionClose();
        close.request(List.of(1L), ignored -> { });
        close.cancel();
        assertFalse(close.completed(true, false));
        assertThrows(IllegalStateException.class, () -> close.request(List.of(1L), ignored -> { throw new IllegalStateException(); }));
        assertFalse(close.requested());
        close.request(List.of(), ignored -> fail());
        assertTrue(close.completed(true, false));
    }
}

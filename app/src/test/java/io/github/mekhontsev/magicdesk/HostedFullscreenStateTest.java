package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class HostedFullscreenStateTest {
    @Test public void acknowledgesEntryAndRestorationOnlyAfterModeChanges() {
        var state = new HostedFullscreenState();
        state.request(true, true);
        assertNull(state.observe(true));
        assertEquals(Boolean.TRUE, state.observe(false));
        assertNull(state.observe(false));
        state.request(false, false);
        assertNull(state.observe(false));
        assertEquals(Boolean.FALSE, state.observe(true));
        assertNull(state.observe(true));
    }

    @Test public void ordinaryFullscreenActivityDoesNotWaitForFreeformOnExit() {
        var state = new HostedFullscreenState();
        state.request(true, false);
        assertEquals(Boolean.TRUE, state.observe(false));
        state.request(false, false);
        assertEquals(Boolean.FALSE, state.observe(false));
    }

    @Test public void userRestoreWithdrawsRequestOnceWithoutReentry() {
        var state = new HostedFullscreenState();
        state.request(true, true);
        assertEquals(Boolean.TRUE, state.observe(false));
        assertEquals(Boolean.FALSE, state.observe(true));
        assertFalse(state.requested());
        assertNull(state.observe(true));
        state.request(true, true);
        assertNull(state.observe(true));
        assertEquals(Boolean.TRUE, state.observe(false));
    }

    @Test public void rapidOppositeRequestSupersedesPendingEntry() {
        var state = new HostedFullscreenState();
        state.request(true, true);
        state.request(false, true);
        assertEquals(Boolean.FALSE, state.observe(true));
        assertFalse(state.requested());
        assertNull(state.observe(false));
    }

    @Test public void duplicateEntryKeepsOriginalRestoreMode() {
        var state = new HostedFullscreenState();
        state.request(true, true);
        assertEquals(Boolean.TRUE, state.observe(false));
        state.request(true, false);
        assertEquals(Boolean.TRUE, state.observe(false));
        state.request(false, false);
        assertNull(state.observe(false));
        assertEquals(Boolean.FALSE, state.observe(true));
    }

    @Test public void rejectedRequestDoesNotRetryOnLayout() {
        var state = new HostedFullscreenState();
        state.request(true, true);
        state.reject();
        assertFalse(state.requested());
        assertNull(state.observe(true));
    }
}

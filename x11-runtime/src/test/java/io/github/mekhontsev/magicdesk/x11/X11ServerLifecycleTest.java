package io.github.mekhontsev.magicdesk.x11;

import org.junit.Test;
import static org.junit.Assert.*;

public final class X11ServerLifecycleTest {
    @Test public void serverRequiresOwnerBeforeStarting() {
        var state = new X11ServerLifecycle(123);
        assertFalse(state.beginStart());
        assertFalse(state.retained());
        assertThrows(SecurityException.class, () -> state.checkCaller(0));
        assertThrows(SecurityException.class, () -> state.checkCaller(2000));
        state.checkCaller(123);
        state.retain();
        assertTrue(state.retained());
        assertThrows(IllegalStateException.class, () -> state.checkReady(123));
        assertTrue(state.beginStart());
        assertFalse(state.beginStart());
        assertTrue(state.ready());
        state.checkReady(123);
        assertThrows(SecurityException.class, () -> state.checkReady(124));
    }

    @Test public void closeBeforeStartNeverStartsNativeServer() {
        var state = new X11ServerLifecycle(1);
        state.retain();
        assertEquals(X11ServerLifecycle.Stop.EXIT, state.stop());
        assertFalse(state.beginStart());
        assertFalse(state.ready());
        assertThrows(IllegalStateException.class, state::retain);
        assertEquals(X11ServerLifecycle.Stop.NONE, state.stop());
    }

    @Test public void closeDuringStartupDefersNativeShutdownUntilReady() {
        var state = new X11ServerLifecycle(1);
        state.retain();
        assertTrue(state.beginStart());
        assertEquals(X11ServerLifecycle.Stop.NONE, state.stop());
        assertFalse(state.ready());
        assertThrows(IllegalStateException.class, () -> state.checkReady(1));
    }

    @Test public void normalShutdownIsIdempotent() {
        var state = new X11ServerLifecycle(1);
        state.retain();
        state.beginStart();
        state.ready();
        assertEquals(X11ServerLifecycle.Stop.NATIVE, state.stop());
        assertEquals(X11ServerLifecycle.Stop.NONE, state.stop());
        assertThrows(IllegalStateException.class, () -> state.checkReady(1));
    }
}

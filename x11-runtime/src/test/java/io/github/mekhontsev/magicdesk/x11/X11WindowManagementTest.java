package io.github.mekhontsev.magicdesk.x11;

import org.junit.Test;
import static org.junit.Assert.*;

public final class X11WindowManagementTest {
    private static final X11WindowManagement.Maximization NONE = new X11WindowManagement.Maximization(0,
            io.github.mekhontsev.magicdesk.hosted.HostedMaximization.NONE, io.github.mekhontsev.magicdesk.hosted.HostedMaximization.NONE);
    @Test public void requestAndActualAreIndependent() {
        var request = new X11WindowManagement.Request(17, true);
        var pending = new X11WindowManagement(true, request, new X11WindowManagement.State(false), NONE);
        assertTrue(pending.request().fullscreen());
        assertFalse(pending.actual().fullscreen());
        var confirmed = new X11WindowManagement(true, request, new X11WindowManagement.State(true), NONE);
        assertEquals(pending.request(), confirmed.request());
        assertNotEquals(pending, confirmed);
    }

    @Test public void nativeConstructorRetainsUnsignedSerialBitsAndManagement() throws Exception {
        var constructor = X11WindowManagement.class.getDeclaredConstructor(boolean.class, int.class, boolean.class, boolean.class,
                int.class, int.class, int.class);
        constructor.setAccessible(true);
        var state = constructor.newInstance(false, -1, false, true, -2, 3, 1);
        assertFalse(state.managed());
        assertEquals(0xffffffffL, Integer.toUnsignedLong(state.request().serial()));
        assertFalse(state.request().fullscreen());
        assertTrue(state.actual().fullscreen());
        assertEquals(0xfffffffeL, Integer.toUnsignedLong(state.maximization().serial()));
        assertEquals(io.github.mekhontsev.magicdesk.hosted.HostedMaximization.BOTH, state.maximization().requested());
        assertEquals(io.github.mekhontsev.magicdesk.hosted.HostedMaximization.HORIZONTAL, state.maximization().actual());
    }

    @Test(expected = NullPointerException.class) public void missingRequestIsNotInvented() {
        new X11WindowManagement(true, null, new X11WindowManagement.State(false), NONE);
    }

    @Test(expected = NullPointerException.class) public void missingActualIsNotInvented() {
        new X11WindowManagement(true, new X11WindowManagement.Request(0, false), null, NONE);
    }
}

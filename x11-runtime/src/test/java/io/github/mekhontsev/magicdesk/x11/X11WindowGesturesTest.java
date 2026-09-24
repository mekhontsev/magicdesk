package io.github.mekhontsev.magicdesk.x11;

import io.github.mekhontsev.magicdesk.hosted.HostedWindowGesture;
import org.junit.Test;
import static org.junit.Assert.*;

public final class X11WindowGesturesTest {
    @Test public void protocolDirectionsDoNotDependOnSharedEnumOrdinals() {
        assertEquals(HostedWindowGesture.NORTH_WEST, X11WindowGestures.decode(0));
        assertEquals(HostedWindowGesture.SOUTH_EAST, X11WindowGestures.decode(4));
        assertEquals(HostedWindowGesture.MOVE, X11WindowGestures.decode(8));
        assertEquals(HostedWindowGesture.CANCEL, X11WindowGestures.decode(11));
        assertNull(X11WindowGestures.decode(9));
        assertNull(X11WindowGestures.decode(10));
        assertNull(X11WindowGestures.decode(-1));
    }
}

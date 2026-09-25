package io.github.mekhontsev.magicdesk;

import java.util.List;
import org.junit.Test;
import io.github.mekhontsev.magicdesk.x11.X11Session;
import static org.junit.Assert.*;

public final class X11WindowSelectionTest {
    @Test public void dialogRetainsItsHostButDoesNotOwnApplicationGeometry() {
        var dialog = window(2, X11Session.WindowRole.DIALOG);
        var main = window(1, X11Session.WindowRole.APPLICATION);
        assertFalse(dialog.provisional());
        assertFalse(dialog.applicationWindow());
        assertTrue(main.applicationWindow());
        assertEquals(2, X11WindowSelection.select(2, true, List.of(main, dialog)));
        assertEquals(-1, X11WindowSelection.select(2, false, List.of(main)));
        var transientWindow = new X11Session.Window(3, "", true, null, X11Session.WindowRole.APPLICATION,
                null, "", "", new io.github.mekhontsev.magicdesk.hosted.HostedWindowLayout(1, 100, 100,
                        io.github.mekhontsev.magicdesk.hosted.HostedWindowConstraints.NONE));
        assertFalse(transientWindow.applicationWindow());
    }

    private static X11Session.Window window(long id, X11Session.WindowRole role) {
        return new X11Session.Window(id, "", true, null, role, null, "", "", io.github.mekhontsev.magicdesk.hosted.HostedWindowLayout.NONE);
    }

    @Test public void splashHandsOffWithoutAnIntermediateHost() {
        var splash = window(1, X11Session.WindowRole.SPLASH);
        var main = window(2, X11Session.WindowRole.APPLICATION);
        assertEquals(1, X11WindowSelection.select(0, true, List.of(splash)));
        assertEquals(2, X11WindowSelection.select(1, true, List.of(splash, main)));
        assertEquals(2, X11WindowSelection.select(2, false, List.of(main)));
    }

    @Test public void unclassifiedStartupAndEmptyGapRemainInSameHost() {
        var provisional = window(1, X11Session.WindowRole.UNCLASSIFIED);
        var main = window(2, X11Session.WindowRole.APPLICATION);
        assertEquals(1, X11WindowSelection.select(0, true, List.of(provisional)));
        assertEquals(0, X11WindowSelection.select(1, true, List.of()));
        assertEquals(2, X11WindowSelection.select(0, true, List.of(main)));
    }

    @Test public void realWindowClosureDoesNotHijackAnotherDocument() {
        var first = window(1, X11Session.WindowRole.APPLICATION);
        var second = window(2, X11Session.WindowRole.APPLICATION);
        assertEquals(1, X11WindowSelection.select(1, false, List.of(second, first)));
        assertEquals(-1, X11WindowSelection.select(1, false, List.of(second)));
    }

    @Test public void knownUnmappedWindowRetainsItsHost() {
        var hidden = new X11Session.Window(1, "", false, null, X11Session.WindowRole.APPLICATION, null, "", "", io.github.mekhontsev.magicdesk.hosted.HostedWindowLayout.NONE);
        assertEquals(1, X11WindowSelection.select(1, false, List.of(hidden)));
    }

    @Test public void selectedWriterDoesNotBecomeTheFirstCalcInTheSharedSession() {
        var calc = window(1, X11Session.WindowRole.APPLICATION);
        var writer = window(2, X11Session.WindowRole.APPLICATION);
        assertEquals(2, X11WindowSelection.select(2, true, List.of(calc, writer)));
        assertEquals(2, X11WindowSelection.select(2, false, List.of(calc, writer)));
        assertEquals(-1, X11WindowSelection.select(2, false, List.of(calc)));
    }
}

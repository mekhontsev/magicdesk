package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PaperInputShortcutStateTest {
    @Test public void defaultsAndInactiveDesktopLeaveButtonsToAndroid() {
        var state = new PaperInputShortcutState();
        assertFalse(state.accept(4, true, 0, true, PaperInputAction.SYSTEM).consumed());
        assertFalse(state.accept(4, false, 0, true, PaperInputAction.NEXT).consumed());
        assertFalse(state.accept(4, true, 0, false, PaperInputAction.NEXT).consumed());
        assertEquals(PaperInputAction.SYSTEM, PaperInputAction.parse("invalid"));
    }

    @Test public void releaseRunsCapturedMappingOnceWithoutRepeatingOrLeakingThePair() {
        var state = new PaperInputShortcutState();
        assertTrue(state.accept(4, true, 0, true, PaperInputAction.PREVIOUS).consumed());
        assertEquals(null, state.accept(4, true, 1, true, PaperInputAction.NEXT).command());
        var release = state.accept(4, false, 0, true, PaperInputAction.SYSTEM);
        assertTrue(release.consumed());
        assertEquals(DesktopWindowAction.PAPER_PREVIOUS, release.command());
        assertFalse(state.accept(4, false, 0, true, PaperInputAction.NEXT).consumed());
    }

    @Test public void disablingLayoutWhileHeldConsumesReleaseWithoutAnAction() {
        var state = new PaperInputShortcutState();
        state.accept(4, true, 0, true, PaperInputAction.NEXT);
        var release = state.accept(4, false, 0, false, PaperInputAction.NEXT);
        assertTrue(release.consumed());
        assertEquals(null, release.command());
    }

    @Test public void buttonsAndDevicesHaveIndependentHeldState() {
        var first = new PaperInputShortcutState();
        var second = new PaperInputShortcutState();
        first.accept(4, true, 0, true, PaperInputAction.PREVIOUS);
        first.accept(125, true, 0, true, PaperInputAction.NEXT);
        assertFalse(second.accept(4, false, 0, true, PaperInputAction.NEXT).consumed());
        assertEquals(DesktopWindowAction.PAPER_NEXT,
                first.accept(125, false, 0, true, PaperInputAction.NEXT).command());
        assertEquals(DesktopWindowAction.PAPER_PREVIOUS,
                first.accept(4, false, 0, true, PaperInputAction.NEXT).command());
    }
}

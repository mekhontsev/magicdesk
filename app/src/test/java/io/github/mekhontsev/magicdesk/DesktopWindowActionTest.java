package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.EnumSet;
import java.util.Set;

public final class DesktopWindowActionTest {
    @Test public void fullscreenTargetEligibilityIsIndependentOfTheInputSource() {
        Set<DesktopWindowAction> freeformOnly = EnumSet.of(
                DesktopWindowAction.FULLSCREEN, DesktopWindowAction.UNMAXIMIZE,
                DesktopWindowAction.PAPER_TOGGLE,
                DesktopWindowAction.PAPER_PREVIOUS,
                DesktopWindowAction.PAPER_NEXT,
                DesktopWindowAction.PAPER_MOVE_LEFT,
                DesktopWindowAction.PAPER_MOVE_RIGHT,
                DesktopWindowAction.PAPER_WIDTH,
                DesktopWindowAction.PAPER_FLOAT);
        for (var action : DesktopWindowAction.values()) {
            assertEquals(action.name(), !freeformOnly.contains(action), action.supportsFullscreenTask());
        }
    }

    @Test public void diagnosticIdentifiersRemainStable() {
        assertEquals(1, DesktopWindowAction.FULLSCREEN.diagnosticId());
        assertEquals(2, DesktopWindowAction.RESTORE.diagnosticId());
        assertEquals(3, DesktopWindowAction.SNAP_LEFT.diagnosticId());
        assertEquals(4, DesktopWindowAction.SNAP_RIGHT.diagnosticId());
        assertEquals(5, DesktopWindowAction.CLOSE.diagnosticId());
        assertEquals(6, DesktopWindowAction.SNAP_TOP_LEFT.diagnosticId());
        assertEquals(7, DesktopWindowAction.SNAP_TOP_RIGHT.diagnosticId());
        assertEquals(8, DesktopWindowAction.SNAP_BOTTOM_LEFT.diagnosticId());
        assertEquals(9, DesktopWindowAction.SNAP_BOTTOM_RIGHT.diagnosticId());
        assertEquals(10, DesktopWindowAction.MAXIMIZE.diagnosticId());
        assertEquals(11, DesktopWindowAction.UNMAXIMIZE.diagnosticId());
    }
}

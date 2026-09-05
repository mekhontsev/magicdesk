package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopTaskbarRevealController.Presentation.EDGE;
import static io.github.mekhontsev.magicdesk.DesktopTaskbarRevealController.Presentation.UNAVAILABLE;
import static io.github.mekhontsev.magicdesk.DesktopTaskbarRevealController.Presentation.VISIBLE;
import static io.github.mekhontsev.magicdesk.DesktopTaskbarRevealController.resolvePresentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopTaskbarRevealControllerTest {
    @Test
    public void managedFullscreenRetainsRevealWithEitherAutoHidePreference() {
        for (final boolean autoHide : new boolean[] { false, true }) {
            assertEquals(EDGE, resolvePresentation(
                    true, false, autoHide, false, false));
            assertEquals(VISIBLE, resolvePresentation(
                    true, false, autoHide, false, true));
        }
    }

    @Test
    public void foreignForegroundSuppressesPanelEvenWhenForcedOrRevealed() {
        for (int flags = 0; flags < 16; flags++) {
            assertEquals(UNAVAILABLE, resolvePresentation(
                    false, (flags & 1) != 0, (flags & 2) != 0,
                    (flags & 4) != 0, (flags & 8) != 0));
        }
    }

    @Test
    public void desktopPreferenceChoosesPinnedPanelOrRevealEdge() {
        assertEquals(VISIBLE, resolvePresentation(
                true, true, false, false, false));
        assertEquals(EDGE, resolvePresentation(
                true, true, true, false, false));
        assertEquals(VISIBLE, resolvePresentation(
                true, true, true, false, true));
    }

    @Test
    public void forcedVisibilityOverridesConcealmentButDoesNotChangePolicy() {
        assertEquals(VISIBLE, resolvePresentation(
                true, false, false, true, false));
        assertEquals(EDGE, resolvePresentation(
                true, false, false, false, false));
    }

    @Test
    public void fullscreenCanRevealAgainAfterPointerLeaves() {
        final PointerEdgeRevealState pointer = new PointerEdgeRevealState();
        pointer.onPointerEntered();
        pointer.setArmed(resolvePresentation(
                true, false, false, false, false) == EDGE);
        assertEquals(VISIBLE, resolvePresentation(
                true, false, false, false, pointer.isRevealed()));

        assertEquals(PointerEdgeRevealState.TimerAction.START_HIDE,
                pointer.onPointerExited());
        assertTrue(pointer.onHideTimeout());
        assertEquals(EDGE, resolvePresentation(
                true, false, false, false, pointer.isRevealed()));

        assertEquals(PointerEdgeRevealState.TimerAction.START_REVEAL,
                pointer.onPointerEntered());
        assertTrue(pointer.onRevealTimeout());
        assertEquals(VISIBLE, resolvePresentation(
                true, false, false, false, pointer.isRevealed()));
    }
}

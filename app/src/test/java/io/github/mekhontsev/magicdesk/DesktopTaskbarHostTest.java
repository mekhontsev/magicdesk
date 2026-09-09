package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import android.graphics.Color;

import org.junit.Test;

public final class DesktopTaskbarHostTest {
    @Test
    public void edgeHiddenActivityHostsOnlyRevealEdge() {
        assertEquals(4, DesktopChromeActivity.resolvePanelHeight(
                true, true, 4, 72));
    }

    @Test
    public void unpresentedActivityDoesNotHostInputPanel() {
        assertEquals(0, DesktopChromeActivity.resolvePanelHeight(
                false, true, 4, 72));
    }

    @Test
    public void visibleActivityHostsFullTaskbar() {
        assertEquals(72, DesktopChromeActivity.resolvePanelHeight(
                true, false, 1, 72));
    }

    @Test
    public void hiddenRevealEdgeHasNoPaintedBackground() {
        assertEquals(Color.TRANSPARENT,
                DesktopChromeActivity.resolvePanelBackgroundColor(true, true));
    }

    @Test
    public void revealingTaskbarRestoresItsBackground() {
        assertEquals(DesktopUiFactory.COLOR_PANEL,
                DesktopChromeActivity.resolvePanelBackgroundColor(true, false));
    }

    @Test
    public void unpresentedPanelHasNoPaintedBackground() {
        assertEquals(Color.TRANSPARENT,
                DesktopChromeActivity.resolvePanelBackgroundColor(false, false));
        assertEquals(Color.TRANSPARENT,
                DesktopChromeActivity.resolvePanelBackgroundColor(false, true));
    }
}

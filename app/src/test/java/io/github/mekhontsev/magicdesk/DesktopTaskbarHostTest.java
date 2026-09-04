package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

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
}

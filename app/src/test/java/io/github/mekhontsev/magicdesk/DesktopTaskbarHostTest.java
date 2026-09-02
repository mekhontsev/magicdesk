package io.github.mekhontsev.magicdesk;

import static android.view.WindowManager.LayoutParams.MATCH_PARENT;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DesktopTaskbarHostTest {
    @Test
    public void edgeHiddenActivityHostsOnlyRevealEdge() {
        assertEquals(4, DesktopTaskbarActivity.resolvePanelHeight(
                true, true, 4));
    }

    @Test
    public void unpresentedActivityDoesNotHostInputPanel() {
        assertEquals(0, DesktopTaskbarActivity.resolvePanelHeight(
                false, true, 4));
    }

    @Test
    public void visibleActivityHostsFullTaskbar() {
        assertEquals(MATCH_PARENT, DesktopTaskbarActivity.resolvePanelHeight(
                true, false, 1));
    }
}

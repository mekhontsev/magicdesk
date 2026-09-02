package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DesktopTaskbarHostTest {
    @Test
    public void edgeHiddenPlaneKeepsOnlyRevealStrip() {
        assertEquals(1076, DesktopTaskbarHost.resolveAppliedTop(
                1016, 1080, true, true, 4));
    }

    @Test
    public void unpresentedPlaneKeepsNormalGeometryForLaterRestore() {
        assertEquals(1016, DesktopTaskbarHost.resolveAppliedTop(
                1016, 1080, false, true, 4));
    }
}

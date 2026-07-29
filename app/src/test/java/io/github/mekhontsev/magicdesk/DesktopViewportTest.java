package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DesktopViewportTest {
    @Test
    public void externalDisplayWithoutInsetsUsesFullBounds() {
        final DesktopViewport viewport = new DesktopViewport(
                0, 0, 1920, 1080, 0, 0, 0, 0);

        assertContentBounds(viewport, 0, 0, 1920, 1080);
        assertEquals(1016, viewport.taskbarTop(64));
    }

    @Test
    public void tabletDisplayReservesSystemBarsAndTaskbar() {
        final DesktopViewport viewport = new DesktopViewport(
                0, 0, 2560, 1600, 0, 48, 0, 32);

        assertContentBounds(viewport, 0, 48, 2560, 1568);
        assertEquals(1504, viewport.taskbarTop(64));
    }

    @Test
    public void asymmetricInsetsPreserveDisplayOrigin() {
        final DesktopViewport viewport = new DesktopViewport(
                100, 200, 1300, 1000, 12, 24, 18, 30);

        assertContentBounds(viewport, 112, 224, 1282, 970);
        assertEquals(906, viewport.taskbarTop(64));
    }

    private static void assertContentBounds(
            final DesktopViewport viewport,
            final int left,
            final int top,
            final int right,
            final int bottom) {
        assertEquals(left, viewport.contentLeft());
        assertEquals(top, viewport.contentTop());
        assertEquals(right, viewport.contentRight());
        assertEquals(bottom, viewport.contentBottom());
    }
}

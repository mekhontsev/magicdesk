package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class DisplayViewportTest {
    @Test public void landscapeFitsWithoutCropping() {
        DisplayViewport viewport = DisplayViewport.fit(960, 540, 640, 480);
        assertEquals(0, viewport.left);
        assertEquals(60, viewport.top);
        assertEquals(640, viewport.width);
        assertEquals(360, viewport.height);
        assertFalse(viewport.contains(320, 59));
        assertFalse(viewport.contains(320, 420));
        assertTrue(viewport.contains(0, 60));
        assertEquals(480, viewport.sourceX(320), .001);
        assertEquals(270, viewport.sourceY(240), .001);
    }

    @Test public void portraitFitsWithSideMargins() {
        DisplayViewport viewport = DisplayViewport.fit(480, 800, 640, 480);
        assertEquals(176, viewport.left);
        assertEquals(0, viewport.top);
        assertEquals(288, viewport.width);
        assertEquals(480, viewport.height);
        assertFalse(viewport.contains(175, 100));
        assertFalse(viewport.contains(464, 100));
        assertEquals(240, viewport.sourceX(320), .001);
        assertEquals(400, viewport.sourceY(240), .001);
    }

    @Test public void identicalGeometryIsIdentity() {
        DisplayViewport viewport = DisplayViewport.fit(1920, 1080, 1920, 1080);
        assertEquals(0, viewport.left);
        assertEquals(0, viewport.top);
        assertEquals(123, viewport.sourceX(123), 0);
        assertEquals(456, viewport.sourceY(456), 0);
        assertFalse(viewport.contains(Float.NaN, 0));
    }

    @Test(expected = IllegalArgumentException.class) public void zeroSizeIsNotAViewport() {
        DisplayViewport.fit(1920, 1080, 0, 0);
    }
}

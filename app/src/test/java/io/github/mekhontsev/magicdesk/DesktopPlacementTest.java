package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopPlacementTest {
    @Test
    public void overflowingEdgesDoNotFitSmallGrid() {
        assertFalse(new DesktopPlacement(Integer.MAX_VALUE, 0, 1, 1)
                .fits(3, 2));
        assertFalse(new DesktopPlacement(0, Integer.MAX_VALUE, 1, 1)
                .fits(3, 2));
        assertFalse(new DesktopPlacement(1, 0, Integer.MAX_VALUE, 1)
                .fits(3, 2));
    }

    @Test
    public void overflowingEdgesStillIntersect() {
        final DesktopPlacement large = new DesktopPlacement(
                1, 1, Integer.MAX_VALUE, Integer.MAX_VALUE);
        final DesktopPlacement inside = new DesktopPlacement(2, 2, 1, 1);

        assertTrue(large.intersects(inside));
        assertTrue(inside.intersects(large));
        assertFalse(large.intersects(new DesktopPlacement(0, 0, 1, 1)));
    }
}

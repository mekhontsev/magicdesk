package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class GlobalDesktopPlacementTest {
    @Test
    public void roundTripPreservesPlacementOnSameGrid() {
        final DesktopPlacement placement =
                new DesktopPlacement(7, 3, 2, 2);

        final GlobalDesktopPlacement global =
                GlobalDesktopPlacement.from(placement, 12, 7);

        assertEquals(placement, global.resolve(12, 7));
    }

    @Test
    public void rightBottomAnchorSurvivesSmallerGrid() {
        final GlobalDesktopPlacement global = GlobalDesktopPlacement.from(
                new DesktopPlacement(10, 5, 2, 2), 12, 7);

        assertEquals(
                new DesktopPlacement(6, 2, 2, 2),
                global.resolve(8, 4));
    }

    @Test
    public void widgetSpanIsClampedToSmallGrid() {
        final GlobalDesktopPlacement global =
                new GlobalDesktopPlacement(5000, 5000, 5, 4);

        assertEquals(
                new DesktopPlacement(0, 0, 3, 2),
                global.resolve(3, 2));
    }
}

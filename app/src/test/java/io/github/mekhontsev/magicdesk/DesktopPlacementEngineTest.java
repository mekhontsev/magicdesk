package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

public final class DesktopPlacementEngineTest {
    @Test
    public void preservesSparsePreferredCells() {
        final Map<String, DesktopPlacement> result =
                DesktopPlacementEngine.arrange(
                        Arrays.asList(
                                request("first", 3, 1),
                                request("second", 0, 2)),
                        4,
                        3);

        assertEquals(new DesktopPlacement(3, 1, 1, 1), result.get("first"));
        assertEquals(new DesktopPlacement(0, 2, 1, 1), result.get("second"));
    }

    @Test
    public void resolvesCollisionsWithoutMovingPlacedItem() {
        final Map<String, DesktopPlacement> result =
                DesktopPlacementEngine.arrange(
                        Arrays.asList(
                                request("first", 1, 1),
                                request("second", 1, 1)),
                        3,
                        3);

        assertEquals(new DesktopPlacement(1, 1, 1, 1), result.get("first"));
        assertFalse(result.get("first").intersects(result.get("second")));
    }

    @Test
    public void keepsWidgetSpanInsideViewport() {
        final Map<String, DesktopPlacement> result =
                DesktopPlacementEngine.arrange(
                        Collections.singletonList(
                                new DesktopPlacementEngine.Request(
                                        "widget",
                                        2,
                                        2,
                                        new DesktopPlacement(3, 2, 2, 2))),
                        4,
                        3);

        assertEquals(new DesktopPlacement(2, 1, 2, 2), result.get("widget"));
    }

    @Test
    public void omitsItemsWhenGridIsFull() {
        final Map<String, DesktopPlacement> result =
                DesktopPlacementEngine.arrange(
                        Arrays.asList(
                                request("first", 0, 0),
                                request("second", 0, 0)),
                        1,
                        1);

        assertEquals(new DesktopPlacement(0, 0, 1, 1), result.get("first"));
        assertNull(result.get("second"));
    }

    private static DesktopPlacementEngine.Request request(
            final String id,
            final int column,
            final int row) {
        return new DesktopPlacementEngine.Request(
                id, 1, 1, new DesktopPlacement(column, row, 1, 1));
    }
}

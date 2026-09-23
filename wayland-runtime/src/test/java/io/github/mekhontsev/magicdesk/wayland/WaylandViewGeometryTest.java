package io.github.mekhontsev.magicdesk.wayland;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class WaylandViewGeometryTest {
    private static final WaylandViewGeometry.Rect PAINT = new WaylandViewGeometry.Rect(-10, -6, 64, 44);

    @Test public void exactInputHolesAreIndependentOfPaint() {
        var geometry = WaylandViewGeometry.fromNative(1, 1, true, -10, -6, 64, 44, true,
                new int[] {0, 0, 8, 24, 56, 0, 64, 24, -10, 24, 20, 44});
        assertEquals(PAINT, geometry.paint());
        assertTrue(geometry.acceptsInput(-5, 30));
        assertTrue(geometry.acceptsInput(60, 10));
        assertFalse(geometry.acceptsInput(30, 12));
        assertFalse(geometry.acceptsInput(64, 12));
        assertFalse(geometry.acceptsInput(10, 44));
    }

    @Test public void snapshotsDoNotBorrowMutableCollections() {
        var input = new ArrayList<>(List.of(PAINT));
        var geometry = new WaylandViewGeometry(1, 2, true, PAINT, true, input);
        input.clear();
        assertEquals(List.of(PAINT), geometry.input());
        assertThrows(UnsupportedOperationException.class, () -> geometry.input().clear());
    }

    @Test public void unavailableOrUnmappedInputDoesNotBecomeABoundingBox() {
        assertFalse(new WaylandViewGeometry(1, 1, true, PAINT, false, List.of()).acceptsInput(0, 0));
        assertFalse(new WaylandViewGeometry(1, 2, false, PAINT, true, List.of(PAINT)).acceptsInput(0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new WaylandViewGeometry(1, 1, true, PAINT, false, List.of(PAINT)));
    }

    @Test public void nativeTransportIsBoundedAndCopied() {
        int[] coordinates = {0, 0, 10, 10};
        var geometry = WaylandViewGeometry.fromNative(1, 1, true, 0, 0, 10, 10, true, coordinates);
        coordinates[2] = 0;
        assertTrue(geometry.acceptsInput(5, 5));
        assertThrows(IllegalArgumentException.class,
                () -> WaylandViewGeometry.fromNative(1, 1, true, 0, 0, 10, 10, true, new int[3]));
        assertThrows(IllegalArgumentException.class,
                () -> WaylandViewGeometry.fromNative(1, 1, true, 0, 0, 10, 10, true,
                        new int[(WaylandViewGeometry.MAX_INPUT_RECTS + 1) * 4]));
        assertThrows(IllegalArgumentException.class, () -> new WaylandViewGeometry.Rect(1, 0, 0, 1));
    }
}

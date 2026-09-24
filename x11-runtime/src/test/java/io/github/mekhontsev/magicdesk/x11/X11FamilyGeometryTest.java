package io.github.mekhontsev.magicdesk.x11;

import org.junit.Test;
import static org.junit.Assert.*;

public final class X11FamilyGeometryTest {
    @Test public void negativeExtentsAndInputHolesSurviveTheWire() {
        var value = X11FamilyGeometry.decode(new int[]{800, 600, 1, -20, 10, 900, 700,
                -20, 10, 20, 50, 80, 20, 900, 700});
        assertEquals(-20, value.paint().left());
        assertEquals(2, value.input().size());
        assertTrue(value.inputComplete());
        assertThrows(UnsupportedOperationException.class, () -> value.input().clear());
    }
    @Test public void invalidOrOversizedGeometryIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> X11FamilyGeometry.decode(new int[8]));
        assertThrows(IllegalArgumentException.class, () -> X11FamilyGeometry.decode(new int[7 + 129 * 4]));
        assertThrows(IllegalArgumentException.class, () -> X11FamilyGeometry.decode(
                new int[]{10, 10, 0, 0, 0, 10, 10, 0, 0, 10, 10}));
    }
}

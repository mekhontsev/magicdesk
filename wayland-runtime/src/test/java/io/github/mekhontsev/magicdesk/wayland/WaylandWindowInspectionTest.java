package io.github.mekhontsev.magicdesk.wayland;

import static org.junit.Assert.*;
import org.junit.Test;

public final class WaylandWindowInspectionTest {
    @Test public void preservesFamilyOriginAndExactFocus() {
        var result = WaylandWindowInspection.decode(new long[]{1, 1, 5, 3, 1, -10, -20, 200, 100, 1, 1, 1}, 1);
        assertTrue(result.found()); assertTrue(result.truncated());
        var node = result.nodes().get(0);
        assertEquals(5, node.id()); assertEquals(3, node.parent()); assertEquals("popup", node.role());
        assertEquals(-10, node.left()); assertTrue(node.focused());
    }
    @Test public void validatesBoundedWireSnapshot() {
        assertFalse(WaylandWindowInspection.decode(new long[]{0, 0}, 1).found());
        assertThrows(IllegalArgumentException.class, () -> WaylandWindowInspection.decode(new long[]{1}, 1));
        assertThrows(IllegalArgumentException.class, () -> WaylandWindowInspection.decode(new long[22], 1));
        assertThrows(IllegalArgumentException.class, () -> WaylandWindowInspection.decode(new long[]{1, 0, 3, 0, 9, 0, 0, 1, 1, 1, 1, 0}, 1));
    }
}

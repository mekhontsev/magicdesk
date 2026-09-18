package io.github.mekhontsev.magicdesk.x11;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class X11WindowInspectionTest {
    @Test public void nativeNodePreservesUnsignedIdsAndDoesNotGuessRoles() throws Exception {
        var constructor = X11WindowInspection.Node.class.getDeclaredConstructor(int.class, int.class, int.class,
                int.class, byte[].class, int.class, int.class, int.class, int.class, int.class, int.class);
        constructor.setAccessible(true);
        var node = constructor.newInstance(-1, -2, 100, 200, "Confirm".getBytes(StandardCharsets.UTF_8),
                -10, 20, 300, 400, 31, 2);
        assertEquals(4294967295L, node.id());
        assertEquals(4294967294L, node.parentId());
        assertEquals(X11WindowInspection.Type.DIALOG, node.type());
        assertEquals(new X11WindowInspection.Bounds(-10, 20, 290, 420), node.bounds());
        assertTrue(node.mapped() && node.realized() && node.inputOnly() && node.overrideRedirect() && node.modal());
        var unknown = constructor.newInstance(1, 0, 0, 0, new byte[0], 0, 0, 1, 1, 0, 999);
        assertEquals(X11WindowInspection.Type.UNKNOWN, unknown.type());
        assertFalse(unknown.mapped());
    }

    @Test public void replyRetainsAnImmutableWindowList() {
        var nodes = new ArrayList<X11WindowInspection.Node>();
        var reply = new X11WindowInspection(4, false, false, new X11WindowInspection.Focus("none", 0),
                new X11WindowInspection.Bounds(0, 0, 800, 600), nodes);
        nodes.add(null);
        assertTrue(reply.windows().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> reply.windows().add(null));
    }
}

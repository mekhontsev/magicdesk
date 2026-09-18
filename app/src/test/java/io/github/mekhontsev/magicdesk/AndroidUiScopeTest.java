package io.github.mekhontsev.magicdesk;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class AndroidUiScopeTest {
    @Test public void explicitDisplayAndOptionalNarrowing() throws Exception {
        final var display = AndroidUiScope.parse(new JSONObject().put("displayId", 0));
        assertEquals(0, display.displayId());
        assertEquals(-1, display.taskId());
        assertEquals(-1, display.windowId());
        assertNull(display.rootElementId());
        assertNull(display.selector());
        assertEquals(200, display.maxNodes());
        assertEquals(12, AndroidUiScope.parse(new JSONObject().put("displayId", 3).put("windowId", 12)).windowId());
        assertEquals("snapshot:2", AndroidUiScope.parse(new JSONObject().put("displayId", 0)
                .put("rootElementId", "snapshot:2")).rootElementId());
    }

    @Test public void explicitTaskWithoutDisplayAndWithOptionalSubtree() throws Exception {
        final var task = AndroidUiScope.parse(new JSONObject().put("taskId", 42).put("rootElementId", "snapshot:2"));
        assertEquals(42, task.taskId());
        assertEquals(-1, task.displayId());
        assertTrue(task.contains(0, 42));
        assertTrue(task.contains(5, 42));
        assertFalse(task.contains(0, null));
        assertFalse(task.contains(0, -1));
        assertFalse(task.contains(0, 43));
    }

    @Test public void rejectsAmbiguousOrCoercedScope() {
        assertThrows(IllegalArgumentException.class, () -> AndroidUiScope.parse(new JSONObject()));
        assertThrows(IllegalArgumentException.class, () -> AndroidUiScope.parse(new JSONObject().put("taskId", 1).put("displayId", 0)));
        for (Object invalid : new Object[] {"1", -1, 1.5, JSONObject.NULL}) {
            assertThrows(IllegalArgumentException.class, () -> AndroidUiScope.parse(new JSONObject().put("taskId", invalid)));
        }
        assertThrows(IllegalArgumentException.class, () -> AndroidUiScope.parse(new JSONObject()
                .put("displayId", 0).put("windowId", 12).put("rootElementId", "snapshot:2")));
        for (Object invalid : new Object[] {"", 12, JSONObject.NULL}) {
            assertThrows(IllegalArgumentException.class, () -> AndroidUiScope.parse(new JSONObject()
                    .put("displayId", 0).put("rootElementId", invalid)));
        }
    }
}

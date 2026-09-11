package io.github.mekhontsev.magicdesk;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class AndroidUiScopeTest {
    @Test public void explicitDisplayAndOptionalNarrowing() throws Exception {
        final var display = AndroidUiScope.parse(new JSONObject().put("displayId", 0));
        assertEquals(0, display.displayId());
        assertEquals(-1, display.windowId());
        assertNull(display.rootElementId());
        assertNull(display.selector());
        assertEquals(200, display.maxNodes());
        assertEquals(12, AndroidUiScope.parse(new JSONObject().put("displayId", 3).put("windowId", 12)).windowId());
        assertEquals("snapshot:2", AndroidUiScope.parse(new JSONObject().put("displayId", 0)
                .put("rootElementId", "snapshot:2")).rootElementId());
    }

    @Test public void rejectsAmbiguousOrCoercedScope() {
        assertThrows(IllegalArgumentException.class, () -> AndroidUiScope.parse(new JSONObject()));
        assertThrows(IllegalArgumentException.class, () -> AndroidUiScope.parse(new JSONObject()
                .put("displayId", 0).put("windowId", 12).put("rootElementId", "snapshot:2")));
        for (Object invalid : new Object[] {"", 12, JSONObject.NULL}) {
            assertThrows(IllegalArgumentException.class, () -> AndroidUiScope.parse(new JSONObject()
                    .put("displayId", 0).put("rootElementId", invalid)));
        }
    }
}

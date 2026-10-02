package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public final class AutomationAppearanceTest {
    @Test public void onlyExplicitSupportedFileFormatsAreAccepted() throws Exception {
        assertEquals("zip", AutomationAppearance.format(new JSONObject()));
        assertEquals("json", AutomationAppearance.format(new JSONObject().put("format", "json")));
        assertThrows(IllegalArgumentException.class, () -> AutomationAppearance.format(new JSONObject().put("format", "html")));
    }
    @Test public void absentScopeIsGlobalButExplicitInvalidScopesFail() throws Exception {
        assertNull(AutomationAppearance.workspaceKey(new JSONObject()));
        assertEquals("portable:workspace", AutomationAppearance.workspaceKey(new JSONObject().put("workspaceKey", "portable:workspace")));
        for (String value : new String[] {"", " ", "local\nworkspace", " local"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> AutomationAppearance.workspaceKey(new JSONObject().put("workspaceKey", value)));
        }
    }

    @Test public void documentResolutionUsesTheSelectedScopeContract() throws Exception {
        assertEquals(ShellAppearance.preset("light"), AutomationAppearance.resolve("{\"version\":5,\"preset\":\"light\"}", null));
        assertThrows(IllegalArgumentException.class,
                () -> AutomationAppearance.resolve("{\"preset\":\"light\"}", "portable:workspace"));
        assertEquals(AppearanceStore.current(), AutomationAppearance.resolve("{}", "portable:workspace"));
    }
}

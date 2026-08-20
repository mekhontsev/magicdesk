package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public final class MagicDeskMcpToolCatalogTest {
    @Test
    public void developerToolsAreExplicitlyGated() throws Exception {
        final Set<String> publicNames = names(
                MagicDeskMcpToolCatalog.create(false));
        final Set<String> developerNames = names(
                MagicDeskMcpToolCatalog.create(true));

        assertTrue(publicNames.contains("magicdesk.get_state"));
        assertTrue(publicNames.contains("magicdesk.launch_app"));
        assertFalse(publicNames.contains("magicdesk.run_self_test"));
        assertTrue(developerNames.contains("magicdesk.run_self_test"));
        assertTrue(developerNames.containsAll(publicNames));
        assertTrue(developerNames.size() > publicNames.size());
    }

    @Test
    public void namesAreUniqueAndSchemasAreClosed() throws Exception {
        final JSONArray tools = MagicDeskMcpToolCatalog.create(true);
        final Set<String> names = new HashSet<>();
        for (int index = 0; index < tools.length(); index++) {
            final JSONObject tool = tools.getJSONObject(index);
            assertTrue(names.add(tool.getString("name")));
            assertEquals("object",
                    tool.getJSONObject("inputSchema").getString("type"));
            assertFalse(tool.getJSONObject("inputSchema")
                    .getBoolean("additionalProperties"));
        }
    }

    private static Set<String> names(final JSONArray tools)
            throws Exception {
        final Set<String> result = new HashSet<>();
        for (int index = 0; index < tools.length(); index++) {
            result.add(tools.getJSONObject(index).getString("name"));
        }
        return result;
    }
}

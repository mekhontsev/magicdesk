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

        assertTrue(publicNames.contains("get_state"));
        assertTrue(publicNames.contains("launch_app"));
        assertTrue(publicNames.contains("list_ui_elements"));
        assertTrue(publicNames.contains("invoke_ui_action"));
        assertTrue(publicNames.contains("begin_trace"));
        assertTrue(publicNames.contains("end_trace"));
        assertFalse(publicNames.contains("run_self_test"));
        assertTrue(developerNames.contains("run_self_test"));
        assertTrue(developerNames.containsAll(publicNames));
        assertTrue(developerNames.size() > publicNames.size());
    }

    @Test
    public void namesAreUniqueAndSchemasAreClosed() throws Exception {
        final JSONArray tools = MagicDeskMcpToolCatalog.create(true);
        final Set<String> names = new HashSet<>();
        for (int index = 0; index < tools.length(); index++) {
            final JSONObject tool = tools.getJSONObject(index);
            assertFalse(tool.getString("name").startsWith("magicdesk."));
            assertTrue(names.add(tool.getString("name")));
            assertEquals("object",
                    tool.getJSONObject("inputSchema").getString("type"));
            assertFalse(tool.getJSONObject("inputSchema")
                    .getBoolean("additionalProperties"));
            final JSONObject output = tool.getJSONObject("outputSchema");
            assertEquals("object", output.getString("type"));
            assertTrue(output.getJSONObject("properties")
                    .has("error"));
            assertEquals(tool.getString("name") + " data",
                    output.getJSONObject("properties")
                            .getJSONObject("data").getString("title"));
        }
    }

    @Test
    public void shellToolsHaveAnIndependentGate() throws Exception {
        final Set<String> normal = names(
                MagicDeskMcpToolCatalog.create(true, false));
        final Set<String> shell = names(
                MagicDeskMcpToolCatalog.create(false, true));

        assertFalse(normal.contains("console.execute"));
        assertFalse(normal.contains("files.list"));
        assertFalse(normal.contains("terminal.read"));
        assertTrue(shell.contains("console.execute"));
        assertTrue(shell.contains("files.list"));
        assertTrue(shell.contains("terminal.open"));
        assertTrue(shell.contains("terminal.list"));
        assertTrue(shell.contains("terminal.read"));
        assertTrue(shell.contains("terminal.write"));
        assertTrue(shell.contains("terminal.send_key"));
        assertTrue(shell.contains("terminal.close"));
        assertFalse(shell.contains("run_self_test"));
    }

    @Test
    public void terminalToolsUseOpaqueIdsAndSemanticInput() throws Exception {
        final JSONArray tools = MagicDeskMcpToolCatalog.create(false, true);
        final JSONObject open = tool(tools, "terminal.open")
                .getJSONObject("outputSchema")
                .getJSONObject("properties")
                .getJSONObject("data")
                .getJSONObject("properties");
        final JSONObject read = tool(tools, "terminal.read")
                .getJSONObject("inputSchema");
        final JSONObject key = tool(tools, "terminal.send_key")
                .getJSONObject("inputSchema");

        assertTrue(open.has("terminalId"));
        assertTrue(open.has("observed"));
        assertEquals("terminalId", read.getJSONArray("required").getString(0));
        assertTrue(read.getJSONObject("properties").has("scope"));
        assertEquals(2, key.getJSONArray("required").length());
        assertTrue(key.getJSONObject("properties").has("ctrl"));
        assertTrue(key.getJSONObject("properties").has("alt"));
        assertTrue(key.getJSONObject("properties").has("shift"));
    }

    @Test
    public void launchAppAcceptsOptionalInitialWindowBounds()
            throws Exception {
        final JSONObject schema = tool(
                MagicDeskMcpToolCatalog.create(false), "launch_app")
                .getJSONObject("inputSchema");
        final JSONObject bounds = schema.getJSONObject("properties")
                .getJSONObject("bounds");

        assertEquals("object", bounds.getString("type"));
        assertFalse(bounds.getBoolean("additionalProperties"));
        assertEquals(4, bounds.getJSONArray("required").length());
        final JSONArray required = schema.getJSONArray("required");
        assertEquals(1, required.length());
        assertEquals("package", required.getString(0));
    }

    @Test
    public void semanticUiAndWaitSchemasExposeStableStateFields()
            throws Exception {
        final JSONArray tools = MagicDeskMcpToolCatalog.create(false);
        final JSONObject invoke = tool(tools, "invoke_ui_action")
                .getJSONObject("inputSchema");
        final JSONObject wait = tool(tools, "wait_for_state")
                .getJSONObject("inputSchema");

        assertEquals(2, invoke.getJSONArray("required").length());
        assertTrue(invoke.getJSONObject("properties").has("elementId"));
        assertTrue(invoke.getJSONObject("properties").has("action"));
        assertTrue(wait.getJSONObject("properties").has("elementId"));
        assertTrue(wait.getJSONObject("properties").has("popupTitle"));
        final JSONArray conditions = wait.getJSONObject("properties")
                .getJSONObject("condition").getJSONArray("enum");
        assertTrue(contains(conditions, "ui_element_state"));
        assertTrue(contains(conditions, "popup_state"));
    }

    private static Set<String> names(final JSONArray tools)
            throws Exception {
        final Set<String> result = new HashSet<>();
        for (int index = 0; index < tools.length(); index++) {
            result.add(tools.getJSONObject(index).getString("name"));
        }
        return result;
    }

    private static JSONObject tool(
            final JSONArray tools,
            final String name) throws Exception {
        for (int index = 0; index < tools.length(); index++) {
            final JSONObject tool = tools.getJSONObject(index);
            if (name.equals(tool.getString("name"))) {
                return tool;
            }
        }
        throw new AssertionError("tool not found: " + name);
    }

    private static boolean contains(
            final JSONArray values, final String expected) {
        for (int index = 0; index < values.length(); index++) {
            if (expected.equals(values.optString(index))) {
                return true;
            }
        }
        return false;
    }
}

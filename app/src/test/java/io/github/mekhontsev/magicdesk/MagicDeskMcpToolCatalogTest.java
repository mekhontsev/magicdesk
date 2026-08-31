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
        assertTrue(publicNames.contains("get_pointer_state"));
        assertTrue(publicNames.contains("launch_app"));
        assertTrue(publicNames.contains("list_ui_elements"));
        assertTrue(publicNames.contains("invoke_ui_action"));
        assertTrue(publicNames.contains("begin_trace"));
        assertTrue(publicNames.contains("end_trace"));
        assertTrue(publicNames.contains("get_termux_x11_status"));
        assertTrue(publicNames.contains("reconnect_termux_x11"));
        assertFalse(publicNames.contains("run_self_test"));
        assertFalse(publicNames.contains("clipboard.read_text"));
        assertFalse(publicNames.contains("clipboard.write_text"));
        assertFalse(publicNames.contains("clipboard.clear"));
        assertTrue(developerNames.contains("run_self_test"));
        assertTrue(developerNames.contains("clipboard.read_text"));
        assertTrue(developerNames.contains("clipboard.write_text"));
        assertTrue(developerNames.contains("clipboard.clear"));
        assertTrue(developerNames.containsAll(publicNames));
        assertTrue(developerNames.size() > publicNames.size());
    }

    @Test
    public void androidIntegrationUsesOneTypedAndRawGateway()
            throws Exception {
        final JSONArray publicTools = MagicDeskMcpToolCatalog.create(false);
        final Set<String> publicNames = names(publicTools);
        final Set<String> developerNames = names(
                MagicDeskMcpToolCatalog.create(true));

        assertTrue(publicNames.contains("query_intent_handlers"));
        assertTrue(publicNames.contains("launch_intent"));
        assertTrue(publicNames.contains("open_uri"));
        assertTrue(publicNames.contains("open_file"));
        assertTrue(publicNames.contains("share"));
        assertTrue(publicNames.contains("list_app_actions"));
        assertTrue(publicNames.contains("invoke_app_action"));
        assertTrue(publicNames.contains("list_notifications"));
        assertTrue(publicNames.contains("invoke_notification"));
        assertTrue(publicNames.contains("get_intent_result"));
        assertTrue(publicNames.contains("search_app_functions"));
        assertTrue(publicNames.contains("execute_app_function"));
        assertTrue(publicNames.contains("launch_desktop_entry"));
        assertFalse(publicNames.contains("launch_spec"));

        assertFalse(publicNames.contains("send_broadcast"));
        assertFalse(publicNames.contains("start_service"));
        assertTrue(developerNames.contains("send_broadcast"));
        assertTrue(developerNames.contains("start_service"));

        final JSONObject intentProperties = tool(
                publicTools, "launch_intent")
                .getJSONObject("inputSchema")
                .getJSONObject("properties");
        assertTrue(intentProperties.has("intentUri"));
        assertTrue(intentProperties.has("action"));
        assertTrue(intentProperties.has("dataUri"));
        assertTrue(intentProperties.has("mimeType"));
        assertTrue(intentProperties.has("component"));
        assertTrue(intentProperties.has("categories"));
        assertTrue(intentProperties.has("extras"));
        assertTrue(intentProperties.has("flagNames"));
        assertTrue(intentProperties.has("chooser"));
        assertTrue(intentProperties.has("expectResult"));

        final JSONObject openFile = tool(publicTools, "open_file")
                .getJSONObject("inputSchema");
        assertEquals(2, openFile.getJSONArray("oneOf").length());
        assertEquals("path", openFile.getJSONArray("oneOf")
                .getJSONObject(0).getJSONArray("required").getString(0));
        assertEquals("uri", openFile.getJSONArray("oneOf")
                .getJSONObject(1).getJSONArray("required").getString(0));

        final JSONObject share = tool(publicTools, "share")
                .getJSONObject("inputSchema");
        assertEquals(2, share.getJSONArray("anyOf").length());
        assertFalse(share.has("oneOf"));

        final JSONObject handlerOutput = dataProperties(
                publicTools, "query_intent_handlers");
        assertTrue(handlerOutput.has("visibilityScope"));
        assertTrue(handlerOutput.has("handlers"));
        assertTrue(handlerOutput.has("truncated"));

        final JSONObject resultOutput = dataProperties(
                publicTools, "get_intent_result");
        assertTrue(resultOutput.has("requestId"));
        assertTrue(resultOutput.has("state"));
        assertTrue(resultOutput.has("resultCode"));

        final JSONObject functionOutput = dataProperties(
                publicTools, "search_app_functions");
        assertTrue(functionOutput.has("functions"));
        assertTrue(functionOutput.has("count"));
        assertTrue(functionOutput.has("truncated"));
    }

    @Test
    public void invisibleAndroidOperationsRemainDeveloperOnly() {
        assertTrue(DesktopAutomationAction.SEND_BROADCAST.developerOnly);
        assertTrue(DesktopAutomationAction.START_SERVICE.developerOnly);
        assertTrue(DesktopAutomationAction.READ_CLIPBOARD_TEXT.developerOnly);
        assertTrue(DesktopAutomationAction.WRITE_CLIPBOARD_TEXT.developerOnly);
        assertTrue(DesktopAutomationAction.CLEAR_CLIPBOARD.developerOnly);
        assertFalse(DesktopAutomationAction.LAUNCH_INTENT.developerOnly);
        assertFalse(DesktopAutomationAction.EXECUTE_APP_FUNCTION.developerOnly);
    }

    @Test
    public void clipboardToolsAreTypedAndPrivacyGated() throws Exception {
        final JSONArray tools = MagicDeskMcpToolCatalog.create(true);
        final JSONObject write = tool(tools, "clipboard.write_text")
                .getJSONObject("inputSchema");
        final JSONObject readOutput = dataProperties(
                tools, "clipboard.read_text");

        assertEquals("text", write.getJSONArray("required").getString(0));
        assertTrue(write.getJSONObject("properties").has("sensitive"));
        assertTrue(readOutput.has("text"));
        assertTrue(readOutput.has("truncated"));
        assertTrue(readOutput.has("mimeTypes"));
        assertTrue(readOutput.has("sensitive"));
    }

    @Test
    public void pointerStateUsesOptionalDisplayAndPortableOutput()
            throws Exception {
        final JSONObject tool = tool(
                MagicDeskMcpToolCatalog.create(false),
                "get_pointer_state");
        final JSONObject input = tool.getJSONObject("inputSchema");
        final JSONObject output = tool.getJSONObject("outputSchema")
                .getJSONObject("properties")
                .getJSONObject("data")
                .getJSONObject("properties");

        assertTrue(input.getJSONObject("properties").has("displayId"));
        assertTrue(output.has("routingReady"));
        assertTrue(output.has("positionAvailable"));
        assertTrue(output.has("x"));
        assertTrue(output.has("y"));
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
        assertTrue(shell.contains("tmux.list"));
        assertTrue(shell.contains("tmux.open"));
        assertFalse(shell.contains("run_self_test"));
    }

    @Test
    public void tmuxToolsExposeAvailabilityAndTypedLaunchFields()
            throws Exception {
        final JSONArray tools = MagicDeskMcpToolCatalog.create(false, true);
        final JSONObject list = tool(tools, "tmux.list")
                .getJSONObject("outputSchema")
                .getJSONObject("properties")
                .getJSONObject("data")
                .getJSONObject("properties");
        final JSONObject openInput = tool(tools, "tmux.open")
                .getJSONObject("inputSchema")
                .getJSONObject("properties");
        final JSONObject openOutput = tool(tools, "tmux.open")
                .getJSONObject("outputSchema")
                .getJSONObject("properties")
                .getJSONObject("data")
                .getJSONObject("properties");

        assertTrue(list.has("available"));
        assertTrue(list.has("sessions"));
        assertTrue(openInput.has("sessionId"));
        assertTrue(openInput.has("name"));
        assertTrue(openOutput.has("terminalId"));
        assertTrue(openOutput.has("tmuxSessionName"));
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
    public void rawAndManagedFullscreenPathsAreExplicit() throws Exception {
        final JSONArray tools = MagicDeskMcpToolCatalog.create(false);
        final JSONObject raw = tool(tools, "set_window_mode");
        final JSONObject managed = tool(tools, "arrange_task");

        assertTrue(raw.getString("description").contains(
                "without MagicDesk fullscreen-plane ownership"));
        assertTrue(managed.getString("description").contains(
                "preserve fullscreen-plane ownership"));
        assertTrue(raw.getJSONObject("inputSchema")
                .getJSONObject("properties").has("bounds"));
        assertFalse(managed.getJSONObject("inputSchema")
                .getJSONObject("properties").has("bounds"));
        assertEquals(2, managed.getJSONObject("inputSchema")
                .getJSONArray("required").length());
    }

    @Test
    public void selfTestSchemaExposesFullAndFailFastModes() throws Exception {
        final JSONObject schema = tool(
                MagicDeskMcpToolCatalog.create(true), "run_self_test")
                .getJSONObject("inputSchema");
        final JSONArray modes = schema.getJSONObject("properties")
                .getJSONObject("mode")
                .getJSONArray("enum");

        assertTrue(contains(modes, "full"));
        assertTrue(contains(modes, "fail_fast"));
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

    private static JSONObject dataProperties(
            final JSONArray tools,
            final String name) throws Exception {
        return tool(tools, name)
                .getJSONObject("outputSchema")
                .getJSONObject("properties")
                .getJSONObject("data")
                .getJSONObject("properties");
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

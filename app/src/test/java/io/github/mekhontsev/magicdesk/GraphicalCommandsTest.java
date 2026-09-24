package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Test;

public final class GraphicalCommandsTest {
    @Test public void authorizationSeparatesObservationExecutionAndPlacement() {
        var observation = new McpAccessPolicy(Set.of());
        assertTrue(observation.allows("graphics.list"));
        assertFalse(observation.allows("graphics.start"));
        var control = new McpAccessPolicy(Set.of("control"));
        assertTrue(control.allows("graphics.open_window"));
        assertFalse(control.allows("graphics.execute"));
        var execution = new McpAccessPolicy(Set.of("shell"));
        assertTrue(execution.allows("graphics.start"));
        assertTrue(execution.allows("graphics.stop"));
        assertFalse(execution.allows("graphics.open_window"));
    }

    @Test public void schemasRequireExplicitProtocolAndExecutorButNotDesktop() throws Exception {
        var tools = AutomationCommandCatalog.create();
        JSONObject start = null, open = null;
        for (int i = 0; i < tools.length(); i++) {
            var tool = tools.getJSONObject(i);
            if (tool.getString("name").equals("graphics.start")) start = tool.getJSONObject("inputSchema");
            if (tool.getString("name").equals("graphics.open_window")) open = tool.getJSONObject("inputSchema");
        }
        assertNotNull(start); assertNotNull(open);
        assertEquals(Set.of("protocol", "backend", "name"), strings(start.getJSONArray("required")));
        assertEquals(Set.of("sessionId", "windowId"), strings(open.getJSONArray("required")));
        assertFalse(start.getJSONObject("properties").has("displayId"));
        assertTrue(open.getJSONObject("properties").has("displayId"));
        assertEquals("boolean", start.getJSONObject("properties").getJSONObject("wholeDesktop").getString("type"));
        AutomationCommandArguments.check("graphics.start", new JSONObject()
                .put("protocol", "wayland").put("backend", "termux").put("name", "Test"));
        try {
            AutomationCommandArguments.check("graphics.start", new JSONObject().put("protocol", "wayland").put("name", "Test"));
            fail("Missing explicit executor accepted");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void managerAndBothProtocolsUseSharedPlacementAndCleanup() throws Exception {
        String manager = source("GraphicalSessionsActivity");
        assertTrue(manager.contains("GraphicalSessions.start("));
        assertTrue(manager.contains("ToolApplications.openSibling("));
        String selection = RuntimeSourceFixture.methods("GraphicalSessionsActivity", "select");
        assertFalse(selection.contains("session.watch("));
        assertFalse(selection.contains("openWindow("));
        for (String name : new String[]{"X11Sessions", "WaylandSessions"})
            assertTrue(source(name).contains("new HostedWindowPresentation(context, this)"));
        assertTrue(source("WaylandActivity").contains("new HostedSurfaceView(this)"));
        assertTrue(source("WaylandExecution").contains("HostedServerProcess.start("));
        assertTrue(source("X11Execution").contains("HostedServerProcess.start("));
        assertTrue(source("MagicDeskRuntimeService").contains("GraphicalSessions.closeAll()"));
        for (String forbidden : new String[]{"startDesktop", "setHome", "Shizuku", "Thread.sleep"})
            assertFalse(forbidden, source("WaylandSessions").contains(forbidden));
    }

    private static String source(String name) throws Exception {
        return Files.readString(Path.of(RuntimeSourceFixture.MAIN + name + ".java"));
    }

    private static Set<String> strings(org.json.JSONArray values) throws Exception {
        Set<String> result = new java.util.HashSet<>();
        for (int i = 0; i < values.length(); i++) result.add(values.getString(i));
        return result;
    }
}

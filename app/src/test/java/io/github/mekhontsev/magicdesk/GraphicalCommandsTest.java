package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.json.JSONObject;
import org.junit.Test;

public final class GraphicalCommandsTest {
    @Test public void launcherScaleUsesEveryExecutorAndPreservesRecentTermuxIdentity() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Activity { }
                enum DesktopExecBackend { APP, SHELL, TERMUX }
                static class DesktopApplicationShortcut {
                    DesktopExecBackend execBackend; Object graphics; boolean exec = true;
                    boolean hasExecLaunch() { return exec; }
                }
                static class IntegrationPackage {
                    static final IntegrationPackage TERMUX = new IntegrationPackage();
                    String selected() { return "com.current.termux"; }
                }
                static class CommandExecution {
                    static boolean shellReady = true;
                    String scope;
                    CommandExecution(Activity activity, DesktopExecBackend backend) {
                        if (backend == DesktopExecBackend.SHELL && !shellReady) throw new IllegalStateException("unavailable");
                        if (backend == DesktopExecBackend.TERMUX) throw new AssertionError("Termux identity recaptured");
                        scope = backend == DesktopExecBackend.APP ? "app:10123" : "shell:2000";
                    }
                }
                public static void verify() {
                    var app = new DesktopApplicationShortcut(); app.graphics = new Object();
                    for (var backend : DesktopExecBackend.values()) {
                        app.execBackend = backend;
                        check(supports(app, "/launcher.desktop"), "executor excluded from scale");
                    }
                    app.graphics = null; check(!supports(app, "/launcher.desktop"), "nongraphical launcher scaled");
                    app.graphics = new Object(); app.exec = false;
                    check(!supports(app, "/launcher.desktop"), "Android launcher treated as Linux");
                    app.exec = true; check(!supports(app, "") && !supports(null, "/launcher.desktop"), "missing identity accepted");
                    var activity = new Activity();
                    check(executorScope(activity, DesktopExecBackend.TERMUX, "com.previous.termux").equals("com.previous.termux"), "recent rebound");
                    check(executorScope(activity, DesktopExecBackend.TERMUX, null).equals("com.current.termux"), "selected Termux lost");
                    check(executorScope(activity, DesktopExecBackend.SHELL, null).equals("shell:2000"), "Shell scope differs from launch");
                    CommandExecution.shellReady = false;
                    check(executorScope(activity, DesktopExecBackend.APP, null).equals("app:10123"), "User requires Shell");
                    try { executorScope(activity, DesktopExecBackend.SHELL, null); throw new AssertionError("identity guessed"); }
                    catch (IllegalStateException expected) { }
                }
                """ + RuntimeSourceFixture.methods("GraphicalScaleDialog", "supports", "executorScope"));
        for (String name : new String[]{"StartMenuContent", "DesktopContextMenuController"}) {
            assertTrue(name, source(name).contains("GraphicalScaleDialog.supports("));
            assertTrue(name, source(name).contains("GraphicalScaleDialog.show("));
        }
        String bind = RuntimeSourceFixture.methods("StartMenuContent", "bindContextMenu");
        assertTrue(bind.contains("mHost.fileActions(menu, application.desktopFile)"));
        assertFalse(bind.contains("mHost.fileContext("));
    }

    @Test public void offscreenUsesExistingInputAndCaptureGrantsAndExplicitTargets() throws Exception {
        for (String operation : new String[]{"capture_screenshot", "sample_pixels", "send_key", "move_pointer",
                "click_pointer", "input.key_chord", "input.gesture"}) {
            var schema = AutomationCommandArguments.command(operation).getJSONObject("inputSchema");
            assertTrue(operation, schema.getJSONObject("properties").has("graphicsSessionId"));
            assertTrue(operation, schema.getJSONObject("properties").has("windowId"));
        }
        AutomationCommandArguments.check("input.key_chord", new JSONObject().put("graphicsSessionId", "test")
                .put("keys", new org.json.JSONArray().put("ENTER")));
        assertTrue(new McpAccessPolicy(Set.of("control")).allows("graphics.set_offscreen"));
        assertFalse(new McpAccessPolicy(Set.of()).allows("graphics.close_session"));
        assertTrue(new McpAccessPolicy(Set.of("input_tests")).allows("input.key_chord"));
        assertTrue(new McpAccessPolicy(Set.of("content")).allows("capture_screenshot"));
        String output = source("AutomationGraphicsOutput");
        assertFalse(output.contains("requireService"));
        assertFalse(output.contains("FrameworkUiAutomationApi"));
        assertTrue(output.contains("AutomationInputSequence.keyChord"));
        assertTrue(source("AndroidAutomationInput").contains("AutomationInputSequence.keyChord"));
        String host = Files.readString(Path.of("../hosted-runtime/src/main/java/io/github/mekhontsev/magicdesk/hosted/HostedImageOutput.java"));
        assertFalse(host.contains("VirtualDisplay"));
        assertFalse(host.contains("Thread.sleep"));
    }
    @Test public void authorizationSeparatesObservationExecutionAndPlacement() {
        var observation = new McpAccessPolicy(Set.of());
        assertTrue(observation.allows("graphics.list"));
        assertFalse(observation.allows("graphics.start"));
        var control = new McpAccessPolicy(Set.of("control"));
        assertTrue(control.allows("graphics.open_window"));
        assertTrue(control.allows("graphics.set_scale"));
        assertFalse(observation.allows("graphics.set_scale"));
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

    @Test public void scaleSchemaAndManagerAreSharedAndNeedNoDesktopOrShell() throws Exception {
        var tool = AutomationCommandArguments.command("graphics.set_scale");
        var schema = tool.getJSONObject("inputSchema");
        assertEquals(Set.of("sessionId", "scalePercent"), strings(schema.getJSONArray("required")));
        var percent = schema.getJSONObject("properties").getJSONObject("scalePercent");
        assertEquals(50, percent.getInt("minimum"));
        assertEquals(200, percent.getInt("maximum"));
        AutomationCommandArguments.check("graphics.set_scale", new JSONObject().put("sessionId", "test").put("scalePercent", 150));
        assertThrows(IllegalArgumentException.class, () -> AutomationCommandArguments.check("graphics.set_scale",
                new JSONObject().put("sessionId", "test")));
        assertTrue(source("GraphicalSessionsActivity").contains("GraphicalScaleDialog.show(this, session)"));
        assertFalse(source("StartMenuContent").contains("graphics.protocol() == GraphicalProtocol.X11"));
        for (String name : new String[]{"X11Sessions", "WaylandSessions"})
            assertTrue(source(name).contains("GraphicalPresentationPreferences.load(context, presentationKey)"));
    }

    private static Set<String> strings(org.json.JSONArray values) throws Exception {
        Set<String> result = new java.util.HashSet<>();
        for (int i = 0; i < values.length(); i++) result.add(values.getString(i));
        return result;
    }
}

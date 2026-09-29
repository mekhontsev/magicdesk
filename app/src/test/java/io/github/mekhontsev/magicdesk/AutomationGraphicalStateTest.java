package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import org.junit.Test;
import org.json.JSONObject;
import io.github.mekhontsev.magicdesk.hosted.HostedWindowLayout;
import io.github.mekhontsev.magicdesk.hosted.HostedMaximization;
import io.github.mekhontsev.magicdesk.hosted.HostedWindowInteraction;

public final class AutomationGraphicalStateTest {
    @Test public void requestsAreNotObservedStates() throws Exception {
        var control = new GraphicalSessions.Control(7, true, null, 8, HostedMaximization.HORIZONTAL, null,
                new HostedWindowInteraction(9, HostedWindowInteraction.Action.MINIMIZE, true));
        var window = AutomationGraphics.window(new GraphicalSessions.Window(31, "Editor", true, "editor", "application", HostedWindowLayout.NONE, control));
        assertTrue(window.getJSONObject("fullscreen").getBoolean("requested"));
        assertTrue(window.getJSONObject("fullscreen").isNull("actual"));
        assertEquals("horizontal", window.getJSONObject("maximization").getString("requested"));
        assertTrue(window.getJSONObject("maximization").isNull("actual"));
        assertEquals(31, window.getLong("windowId"));
        assertFalse(window.has("taskId"));
        assertEquals("minimize", window.getJSONObject("interaction").getString("action"));
        assertTrue(window.getJSONObject("interaction").getBoolean("attention"));
        assertFalse(window.getJSONObject("interaction").has("minimized"));
        assertEquals(1, window.getJSONObject("constraints").getJSONObject("resize").getInt("widthIncrement"));
    }
    @Test public void unknownDoesNotProveDisabled() throws Exception {
        var args = new JSONObject().put("state", "fullscreen").put("enabled", false);
        assertFalse(AutomationGraphicsObservation.stateMatches(new JSONObject(), args));
        assertFalse(AutomationGraphicsObservation.stateMatches(new JSONObject().put("fullscreen", JSONObject.NULL), args));
        assertTrue(AutomationGraphicsObservation.stateMatches(new JSONObject().put("fullscreen", false), args));
        assertThrows(IllegalArgumentException.class, () -> AutomationGraphicsObservation.stateMatches(new JSONObject(), new JSONObject().put("state", "fullscreen")));
    }
    @Test public void conditionsUseEventWaitAndSeparateIdentityKinds() throws Exception {
        AutomationGraphicsObservation.validate("graphics_window_present", new JSONObject().put("sessionId", "session"));
        assertThrows(IllegalArgumentException.class, () -> AutomationGraphicsObservation.validate("graphics_window_absent", new JSONObject().put("sessionId", "session")));
        assertThrows(IllegalArgumentException.class, () -> AutomationGraphicsObservation.validate("task_state", new JSONObject().put("taskId", 1)));
        assertThrows(IllegalArgumentException.class, () -> AutomationGraphicsObservation.validate("shell_surface_absent", new JSONObject().put("workspaceId", "workspace")));
        for (String condition : new String[]{"graphics_ready", "graphics_window_present", "graphics_window_absent", "graphics_host_attached", "graphics_window_state", "task_state", "shell_surface_present"}) {
            AutomationCommandArguments.check("wait_for_state", new JSONObject().put("condition", condition));
            assertTrue(AutomationGraphicsObservation.supports(condition));
            assertEquals(4321, DesktopAutomationController.waitInterval(condition, 4321, false));
        }
        assertThrows(IllegalArgumentException.class, () -> AutomationCommandArguments.command("x11.inspect_window"));
        assertThrows(IllegalArgumentException.class, () -> AutomationCommandArguments.check("graphics.detach_viewer", new JSONObject().put("sessionId", "x").put("windowId", 1)));
        assertThrows(IllegalArgumentException.class, () -> AutomationCommandArguments.check("graphics.close_window", new JSONObject().put("sessionId", "x").put("taskId", 1)));
    }
    @Test public void capabilityGrantsRemainIndependent() {
        var observe = new McpAccessPolicy(java.util.Set.of());
        assertTrue(observe.allows("inspect_workspace"));
        assertFalse(observe.allows("graphics.inspect_window"));
        assertFalse(observe.allows("set_task_state"));
        var control = new McpAccessPolicy(java.util.Set.of("control"));
        assertTrue(control.allows("graphics.detach_viewer"));
        assertTrue(control.allows("graphics.close_window"));
        assertFalse(control.allows("graphics.stop"));
    }
    @Test public void dependentPresenceUsesSharedCatalogAndDoesNotAcceptOtherFamilies() throws Exception {
        var args = new JSONObject().put("condition", "graphics_window_present").put("sessionId", "session")
                .put("parentWindowId", 4294967295L);
        AutomationCommandArguments.check("wait_for_state", args);
        AutomationGraphicsObservation.validate("graphics_window_present", args);
        var layout = new HostedWindowLayout(4294967295L, 100, 100,
                io.github.mekhontsev.magicdesk.hosted.HostedWindowConstraints.NONE);
        var child = new GraphicalSessions.Window(30, "Open", true, "editor", "dialog", layout, null);
        assertTrue(AutomationGraphicsObservation.presentMatches(child, args));
        assertFalse(AutomationGraphicsObservation.presentMatches(new GraphicalSessions.Window(
                31, "Hidden", false, "editor", "dialog", layout, null), args));
        assertFalse(AutomationGraphicsObservation.presentMatches(new GraphicalSessions.Window(
                32, "Other", true, "editor", "application", HostedWindowLayout.NONE, null), args));
        args.put("windowId", 31);
        assertFalse(AutomationGraphicsObservation.presentMatches(child, args));
        args.put("windowId", 30);
        assertTrue(AutomationGraphicsObservation.presentMatches(child, args));
        assertThrows(IllegalArgumentException.class, () -> AutomationGraphicsObservation.validate("graphics_window_absent", args));
        args.put("parentWindowId", 0);
        assertThrows(IllegalArgumentException.class, () -> AutomationGraphicsObservation.validate("graphics_window_present", args));
        args.put("parentWindowId", -1);
        assertThrows(IllegalArgumentException.class, () -> AutomationGraphicsObservation.validate("graphics_window_present", args));
    }
    @Test public void titlePresenceIsExactMappedAndIdentityQualified() throws Exception {
        var args = new JSONObject().put("condition", "graphics_window_present").put("sessionId", "session")
                .put("windowTitle", "Document - Editor").put("windowId", 30).put("parentWindowId", 10);
        AutomationCommandArguments.check("wait_for_state", args);
        AutomationGraphicsObservation.validate("graphics_window_present", args);
        var layout = new HostedWindowLayout(10, 100, 100,
                io.github.mekhontsev.magicdesk.hosted.HostedWindowConstraints.NONE);
        var window = new GraphicalSessions.Window(30, "Document - Editor", true, "editor", "dialog", layout, null);
        assertTrue(AutomationGraphicsObservation.presentMatches(window, args));
        assertFalse(AutomationGraphicsObservation.presentMatches(new GraphicalSessions.Window(
                30, "Document - Editor", false, "editor", "dialog", layout, null), args));
        args.put("windowTitle", "Document");
        assertFalse(AutomationGraphicsObservation.presentMatches(window, args));
        args.put("windowTitle", "Document - Editor").put("windowId", 31);
        assertFalse(AutomationGraphicsObservation.presentMatches(window, args));
        args.put("windowId", 30).put("parentWindowId", 11);
        assertFalse(AutomationGraphicsObservation.presentMatches(window, args));
    }
    @Test public void titleFilterCannotBeSilentlyIgnoredOrCoerced() throws Exception {
        var args = new JSONObject().put("sessionId", "session").put("windowTitle", "Document");
        for (String condition : new String[]{"graphics_ready", "graphics_window_absent", "graphics_host_attached", "display_present", "task_focused"})
            assertThrows(IllegalArgumentException.class, () -> AutomationGraphicsObservation.validate(condition, args));
        for (Object title : new Object[]{JSONObject.NULL, 42, "x".repeat(4097)}) {
            args.put("windowTitle", title);
            assertThrows(IllegalArgumentException.class, () -> AutomationGraphicsObservation.validate("graphics_window_present", args));
        }
        args.put("windowTitle", "");
        AutomationGraphicsObservation.validate("graphics_window_present", args);
    }
}

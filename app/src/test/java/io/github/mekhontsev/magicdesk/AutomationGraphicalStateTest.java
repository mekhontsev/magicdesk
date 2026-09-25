package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import org.junit.Test;
import org.json.JSONObject;
import io.github.mekhontsev.magicdesk.hosted.HostedWindowLayout;
import io.github.mekhontsev.magicdesk.hosted.HostedMaximization;

public final class AutomationGraphicalStateTest {
    @Test public void requestsAreNotObservedStates() throws Exception {
        var control = new GraphicalSessions.Control(7, true, null, 8, HostedMaximization.HORIZONTAL, null);
        var window = AutomationGraphics.window(new GraphicalSessions.Window(31, "Editor", true, "editor", "application", HostedWindowLayout.NONE, control));
        assertTrue(window.getJSONObject("fullscreen").getBoolean("requested"));
        assertTrue(window.getJSONObject("fullscreen").isNull("actual"));
        assertEquals("horizontal", window.getJSONObject("maximization").getString("requested"));
        assertTrue(window.getJSONObject("maximization").isNull("actual"));
        assertEquals(31, window.getLong("windowId"));
        assertFalse(window.has("taskId"));
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
}

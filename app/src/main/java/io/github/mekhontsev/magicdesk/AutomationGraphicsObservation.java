package io.github.mekhontsev.magicdesk;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

/** Predicates over existing publications. Unknown state never satisfies a negative assertion. */
final class AutomationGraphicsObservation {
    static boolean supports(String condition) {
        return condition.startsWith("graphics_") || condition.startsWith("shell_surface_") || condition.equals("task_state");
    }
    static JSONObject observe(String condition, JSONObject args) {
        validate(condition, args);
        return AutomationMainThread.read(() -> current(condition, args));
    }
    static void validate(String condition, JSONObject args) {
        boolean workspace = condition.equals("task_state") || condition.startsWith("shell_surface_");
        String identity = workspace ? "workspaceId" : "sessionId";
        if (args.optString(identity, "").isBlank()) throw new IllegalArgumentException("Expected " + identity);
        if (condition.equals("task_state") && AutomationJsonArguments.requiredInt(args, "taskId") < 0)
            throw new IllegalArgumentException("Expected taskId");
        if (condition.startsWith("shell_surface_") && args.optString("surfaceId", "").isBlank()
                && args.optString("sessionId", "").isBlank()) throw new IllegalArgumentException("Select surfaceId or sessionId");
        if (condition.equals("graphics_window_absent") || condition.equals("graphics_window_state") || condition.equals("graphics_host_attached")
                || condition.equals("graphics_window_present") && args.has("windowId")) {
            long window = AutomationJsonArguments.requiredLong(args, "windowId");
            if (window < 0 || window == 0 && condition.startsWith("graphics_window_") && !condition.equals("graphics_window_state"))
                throw new IllegalArgumentException("Expected native windowId");
        }
        if (condition.equals("task_state") || condition.equals("graphics_window_state")) {
            try { stateMatches(new JSONObject(), args); }
            catch (JSONException error) { throw new IllegalArgumentException("Expected state and enabled", error); }
        }
    }
    private static JSONObject current(String condition, JSONObject args) throws JSONException {
        var result = new JSONObject().put("condition", condition).put("matched", false);
        if (condition.equals("task_state") || condition.startsWith("shell_surface_")) {
            var snapshot = AutomationWorkspace.snapshot(args.getString("workspaceId"));
            result.put("workspace", snapshot);
            if (!snapshot.optBoolean("available")) return result;
            if (condition.equals("task_state")) {
                if (!snapshot.optBoolean("tasksKnown")) return result;
                int task = AutomationJsonArguments.requiredInt(args, "taskId");
                for (int i = 0; i < snapshot.getJSONArray("tasks").length(); ++i) {
                    var state = snapshot.getJSONArray("tasks").getJSONObject(i);
                    if (state.getInt("taskId") == task) return result.put("matched", stateMatches(state, args));
                }
                return result;
            }
            if (!args.has("surfaceId") && !args.has("sessionId")) throw new IllegalArgumentException("Select surfaceId or sessionId");
            boolean present = false;
            for (int i = 0; i < snapshot.getJSONArray("surfaces").length(); ++i) {
                var surface = snapshot.getJSONArray("surfaces").getJSONObject(i);
                if (args.has("surfaceId") && !args.getString("surfaceId").equals(surface.getString("surfaceId"))) continue;
                if (args.has("sessionId") && !args.getString("sessionId").equals(surface.optString("sessionId"))) continue;
                if (surface.getBoolean("mapped")) { present = true; break; }
            }
            return result.put("matched", condition.equals("shell_surface_present") == present);
        }
        String id = args.getString("sessionId");
        var session = GraphicalSessions.find(id);
        result.put("sessionId", id).put("sessionPresent", session != null);
        if (condition.equals("graphics_session_absent")) return result.put("matched", session == null || session.stopped());
        if (condition.equals("graphics_window_absent") && (session == null || session.stopped()))
            return result.put("matched", true);
        if (session == null) return result;
        var snapshot = AutomationGraphics.describe(session);
        result.put("session", snapshot);
        if (condition.equals("graphics_ready")) return result.put("matched", session.ready());
        if (!session.ready()) return result;
        if (condition.equals("graphics_window_present") && !args.has("windowId"))
            return result.put("matched", session.windows().stream().anyMatch(GraphicalSessions.Window::mapped));
        long window = AutomationJsonArguments.requiredLong(args, "windowId");
        var selected = session.windows().stream().filter(item -> item.id() == window).findFirst().orElse(null);
        switch (condition) {
            case "graphics_window_present" -> { return result.put("matched", selected != null && selected.mapped()); }
            case "graphics_window_absent" -> { return result.put("matched", selected == null); }
            case "graphics_host_attached", "graphics_window_state" -> {
                var matching = new JSONArray();
                var hosts = snapshot.getJSONArray("hosts");
                for (int i = 0; i < hosts.length(); ++i) {
                    var host = hosts.getJSONObject(i);
                    if ((window == 0 ? !host.getBoolean("wholeDesktop") : host.getLong("windowId") != window)
                            || args.has("taskId") && host.getInt("taskId") != args.getInt("taskId")) continue;
                    matching.put(host);
                }
                result.put("matchingHosts", matching);
                if (condition.equals("graphics_host_attached")) {
                    for (int i = 0; i < matching.length(); ++i) if (matching.getJSONObject(i).getBoolean("attached")) return result.put("matched", true);
                } else if (matching.length() == 1) {
                    var state = matching.getJSONObject(0).optJSONObject("state");
                    if (state != null) return result.put("matched", stateMatches(state, args));
                }
                return result;
            }
            default -> throw new IllegalArgumentException("Unknown graphical condition");
        }
    }
    static boolean stateMatches(JSONObject state, JSONObject args) throws JSONException {
        String name = args.getString("state");
        if (!java.util.Set.of("fullscreen", "maximized", "concealed").contains(name)) throw new IllegalArgumentException("Unknown state");
        if (!args.has("enabled")) throw new IllegalArgumentException("Expected enabled is required");
        return state.has(name) && !state.isNull(name) && state.getBoolean(name) == args.getBoolean("enabled");
    }
    private AutomationGraphicsObservation() { }
}

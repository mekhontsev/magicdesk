package io.github.mekhontsev.magicdesk;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

/** Read the actual shell layout and dispatch task intent through its existing catalog gateway. */
final class AutomationWorkspace {
    static DesktopAutomationResult inspect(JSONObject args) {
        return AutomationMainThread.await(AutomationMainThread.submit(() -> snapshot(args.getString("workspaceId"))),
                "inspect_workspace", true);
    }
    static DesktopWorkspaceRuntime find(String id) {
        for (var item : DesktopRuntimeBridge.getWorkspaces()) {
            var runtime = DesktopRuntimeBridge.getWorkspaceRuntime(item.activeWorkspaceDisplayId());
            if (runtime != null && runtime.id.equals(id) && !runtime.isClosed()) return runtime;
        }
        return null;
    }
    static JSONObject snapshot(String id) throws JSONException {
        var runtime = find(id);
        if (runtime == null || runtime.host() == null) return new JSONObject().put("workspaceId", id).put("available", false);
        var host = runtime.host();
        var scope = host.panels().shellScope();
        var state = scope.snapshot();
        var surfaces = new JSONArray(); var exclusions = new JSONArray(); var tasks = new JSONArray();
        var origins = scope.origins();
        if (state != null) for (var entry : state.surfaces().entrySet()) {
            var resolved = entry.getValue(); var request = resolved.request(); var origin = origins.get(entry.getKey());
            surfaces.put(new JSONObject().put("surfaceId", entry.getKey()).put("mapped", request.mapped())
                    .put("sessionId", origin == null || origin.sessionId().isEmpty() ? JSONObject.NULL : origin.sessionId())
                    .put("localId", origin == null ? JSONObject.NULL : origin.localId())
                    .put("layer", AutomationGraphics.name(request.layer())).put("keyboard", AutomationGraphics.name(request.keyboard()))
                    .put("layerPresented", host.shellPresentation().visible(request.layer()))
                    .put("contentBounds", bounds(resolved.content())).put("paintBounds", bounds(resolved.paint()))
                    .put("layoutInputBounds", bounds(resolved.input())));
        }
        if (state != null) for (var item : state.exclusions()) exclusions.put(new JSONObject().put("surfaceId", item.owner())
                .put("edge", AutomationGraphics.name(item.edge())).put("bounds", bounds(item.bounds())).put("affectsWindows", item.windows()));
        for (var item : host.shellTasks().snapshot()) {
            var task = item.task();
            tasks.put(new JSONObject().put("handle", item.id()).put("taskId", task.taskId()).put("title", task.title()).put("appId", task.appId())
                    .put("active", task.active()).put("fullscreen", task.fullscreen()).put("maximized", task.maximized()).put("concealed", task.minimized()));
        }
        return new JSONObject().put("workspaceId", id).put("available", state != null).put("displayId", runtime.displayId)
                .put("coordinateSpace", "android_display_pixels").put("output", state == null ? JSONObject.NULL : bounds(state.output()))
                .put("workArea", state == null ? JSONObject.NULL : bounds(state.workArea()))
                .put("panelArea", state == null ? JSONObject.NULL : bounds(state.panelArea())).put("surfaces", surfaces)
                .put("exclusions", exclusions).put("tasksKnown", host.shellTasks().available()).put("tasks", tasks);
    }
    static JSONObject bounds(ShellBounds bounds) throws JSONException {
        return new JSONObject().put("left", bounds.left()).put("top", bounds.top()).put("right", bounds.right()).put("bottom", bounds.bottom());
    }
    static DesktopAutomationResult setTaskState(JSONObject args) {
        return AutomationMainThread.await(AutomationMainThread.submit(() -> {
            int task = AutomationJsonArguments.requiredInt(args, "taskId");
            boolean enabled = args.getBoolean("enabled");
            var action = switch (args.getString("state")) {
                case "fullscreen" -> enabled ? ShellTaskCatalog.Action.FULLSCREEN : ShellTaskCatalog.Action.UNFULLSCREEN;
                case "maximized" -> enabled ? ShellTaskCatalog.Action.MAXIMIZE : ShellTaskCatalog.Action.UNMAXIMIZE;
                case "concealed" -> enabled ? ShellTaskCatalog.Action.MINIMIZE : ShellTaskCatalog.Action.UNMINIMIZE;
                default -> throw new IllegalArgumentException("Unknown task state");
            };
            for (var item : DesktopRuntimeBridge.getWorkspaces()) {
                var runtime = DesktopRuntimeBridge.getWorkspaceRuntime(item.activeWorkspaceDisplayId());
                if (runtime == null || runtime.host() == null) continue;
                var catalog = runtime.host().shellTasks();
                for (var window : catalog.snapshot()) if (window.task().taskId() == task) {
                    if (!catalog.request(window.id(), action)) throw new IllegalStateException("Task observation unavailable");
                    return new JSONObject().put("accepted", true).put("taskId", task).put("workspaceId", runtime.id)
                            .put("state", args.getString("state")).put("enabled", enabled);
                }
            }
            throw new IllegalArgumentException("Select a live managed Desktop task");
        }), "set_task_state", false);
    }
    private AutomationWorkspace() { }
}

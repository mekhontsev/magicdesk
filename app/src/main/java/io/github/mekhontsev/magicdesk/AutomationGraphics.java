package io.github.mekhontsev.magicdesk;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.CountDownLatch;
import org.json.JSONArray;
import org.json.JSONObject;

/** Thin adapter to the same retained graphical sessions used by the manager. */
final class AutomationGraphics {
    static DesktopAutomationResult execute(String operation, JSONObject args) {
        if (operation.equals("graphics.inspect_window")) return AutomationGraphicsInspection.inspect(args);
        Handler main = new Handler(Looper.getMainLooper());
        CountDownLatch completed = new CountDownLatch(1);
        JSONObject[] result = new JSONObject[1];
        Throwable[] failure = new Throwable[1];
        Intent[] intent = new Intent[1];
        Runnable action = () -> {
            try {
                var context = MagicDeskApplication.applicationContext();
                if (operation.equals("graphics.list")) {
                    var workspaces = new JSONArray();
                    for (var item : DesktopRuntimeBridge.getWorkspaces()) if (item.hasHost())
                        workspaces.put(new JSONObject().put("workspaceId", item.workspace().id)
                                .put("displayId", item.activeWorkspaceDisplayId()));
                    result[0] = new JSONObject().put("sessions", snapshot()).put("workspaces", workspaces);
                } else if (operation.equals("graphics.start")) {
                    String command = args.optString("command", "");
                    if (!command.isBlank()) DesktopExecCommand.normalize(command);
                    var session = GraphicalSessions.start(context, GraphicalProtocol.parse(args.getString("protocol")),
                            args.getString("name"), command, args.optString("directory", ""),
                            DesktopExecBackend.parse(args.getString("backend")), args.optString("keyboardDirectory", ""),
                            args.optBoolean("wholeDesktop", false));
                    result[0] = describe(session).put("accepted", true);
                } else {
                    var session = GraphicalSessions.find(args.getString("sessionId"));
                    if (session == null) throw new IllegalArgumentException("Graphical session is unavailable");
                    switch (operation) {
                        case "graphics.set_workspace" -> GraphicalShells.select(session, args.getString("workspaceId"));
                        case "graphics.set_scale" -> GraphicalPresentationPreferences.save(context, session,
                                AutomationJsonArguments.requiredInt(args, "scalePercent"));
                        case "graphics.execute" -> session.execute(DesktopExecCommand.normalize(args.getString("command")), args.optString("directory", ""));
                        case "graphics.stop" -> session.close();
                        case "graphics.close_window" -> {
                            long window = AutomationJsonArguments.requiredLong(args, "windowId");
                            if (window <= 0 || session.windows().stream().noneMatch(item -> item.id() == window))
                                throw new IllegalArgumentException("Select a live native client window");
                            session.closeWindow(window, args.optBoolean("force", false));
                        }
                        case "graphics.detach_viewer" -> session.detachViewer(AutomationJsonArguments.requiredInt(args, "taskId"));
                        case "graphics.open_window" -> {
                            long window = AutomationJsonArguments.requiredLong(args, "windowId");
                            if (window < 0 || (window == 0 && !session.desktop())
                                    || (window != 0 && session.windows().stream().noneMatch(item -> item.id() == window)))
                                throw new IllegalArgumentException("Select a live window from graphics.list");
                            intent[0] = session.windowIntent(context, window);
                        }
                        default -> throw new IllegalArgumentException("Unknown graphical operation");
                    }
                    result[0] = describe(session).put("accepted", true);
                }
            } catch (Exception error) { failure[0] = error; }
            finally { completed.countDown(); }
        };
        main.post(action);
        // EVENT_WAIT: main-thread command completion; expiry cancels only work that has not started.
        var pending = AutomationCallbackWait.await(completed, 10_000, operation, operation.equals("graphics.list"),
                new JSONObject());
        if (pending != null) {
            // Only queued work can be withdrawn. A started launch remains observable in graphics.list.
            main.removeCallbacks(action);
            return pending;
        }
        if (failure[0] != null) return DesktopAutomationResult.failure(
                failure[0] instanceof IllegalArgumentException ? DesktopAutomationErrorCode.INVALID_ARGUMENT
                        : DesktopAutomationErrorCode.ACTION_FAILED, ShellAccess.usefulMessage(failure[0]), false);
        if (intent[0] != null) return AutomationToolWindows.open(intent[0], args);
        return DesktopAutomationResult.success("ok", result[0]);
    }

    static JSONArray snapshot() throws org.json.JSONException {
        return AutomationMainThread.read(() -> {
            JSONArray sessions = new JSONArray();
            for (var session : GraphicalSessions.list()) sessions.put(describe(session));
            return sessions;
        });
    }

    static JSONObject describe(GraphicalSessions.Session session) throws org.json.JSONException {
        JSONArray windows = new JSONArray();
        for (var window : session.windows()) {
            var item = window(window).put("protocolDetails", new JSONObject(session.windowDetails(window.id())));
            windows.put(item);
        }
        var shell = GraphicalShells.state(session.id());
        var identity = session.identity();
        var details = new JSONObject(session.details());
        return new JSONObject().put("sessionId", session.id()).put("name", session.name())
                .put("protocol", session.protocol().name().toLowerCase(java.util.Locale.ROOT))
                .put("state", session.state()).put("ready", session.ready()).put("error", session.error())
                .put("scalePercent", session.scalePercent()).put("scalePersistent", !session.presentationKey().isEmpty())
                .put("wholeDesktop", session.desktop()).put("windows", windows)
                .put("hosts", hosts(session))
                .put("executor", identity.backend().wireName).put("executorUid", identity.executorUid())
                .put("serverUid", identity.serverUid()).put("protocolDetails", details)
                .put("shellIntegration", new JSONObject().put("available", session.canIntegrateShell())
                        .put("workspaceId", shell.workspaceId()).put("displayId", shell.displayId()).put("error", shell.error()));
    }

    static JSONObject window(GraphicalSessions.Window window) throws org.json.JSONException {
        var layout = window.layout(); var limits = layout.constraints(); var control = window.control();
        return new JSONObject().put("windowId", window.id()).put("title", window.title()).put("mapped", window.mapped())
                .put("appId", window.appId()).put("role", window.role()).put("parentWindowId", layout.parent())
                .put("width", layout.width()).put("height", layout.height())
                .put("constraints", new JSONObject().put("minWidth", limits.minWidth()).put("minHeight", limits.minHeight())
                        .put("maxWidth", limits.maxWidth()).put("maxHeight", limits.maxHeight()))
                .put("fullscreen", new JSONObject().put("serial", control.serial()).put("requested", control.requestedFullscreen())
                        .put("actual", nullable(control.actualFullscreen())))
                .put("maximization", new JSONObject().put("serial", control.maximizeSerial())
                        .put("requested", name(control.requestedMaximization())).put("actual", name(control.actualMaximization())));
    }

    static JSONArray hosts(GraphicalSessions.Session session) throws org.json.JSONException {
        var hosts = new JSONArray();
        for (var host : session.hosts()) {
            var workspace = DesktopRuntimeBridge.getWorkspaceRuntime(host.displayId());
            ShellTaskCatalog.Task task = null;
            boolean ownershipKnown = workspace == null || workspace.host() != null && workspace.host().shellTasks().available();
            if (workspace != null && ownershipKnown) for (var item : workspace.host().shellTasks().snapshot())
                if (item.task().taskId() == host.taskId()) { task = item.task(); break; }
            var item = new JSONObject().put("taskId", host.taskId()).put("displayId", host.displayId())
                    .put("windowId", host.windowId()).put("focused", host.focused()).put("attached", host.attached())
                    .put("wholeDesktop", host.wholeDesktop()).put("managed", ownershipKnown ? task != null : JSONObject.NULL)
                    .put("workspaceId", task == null ? JSONObject.NULL : workspace.id)
                    .put("state", task == null ? JSONObject.NULL : new JSONObject().put("fullscreen", task.fullscreen())
                            .put("maximized", task.maximized()).put("concealed", task.minimized()).put("active", task.active()));
            var geometry = host.geometry();
            item.put("content", geometry == null ? JSONObject.NULL : new JSONObject()
                    .put("coordinateSpace", "android_display_pixels").put("width", geometry.contentWidth()).put("height", geometry.contentHeight())
                    .put("bounds", new JSONObject().put("left", geometry.left()).put("top", geometry.top())
                            .put("right", geometry.right()).put("bottom", geometry.bottom())));
            hosts.put(item);
        }
        return hosts;
    }

    static Object nullable(Object value) { return value == null ? JSONObject.NULL : value; }
    static Object name(Enum<?> value) { return value == null ? JSONObject.NULL : value.name().toLowerCase(java.util.Locale.ROOT); }
    static java.util.Map<Integer, JSONObject> taskLinks() {
        return AutomationMainThread.read(() -> {
            var result = new java.util.LinkedHashMap<Integer, JSONObject>();
            for (var session : GraphicalSessions.list()) for (var host : session.hosts())
                result.put(host.taskId(), new JSONObject().put("sessionId", session.id()).put("protocol", name(session.protocol()))
                        .put("windowId", host.windowId()).put("wholeDesktop", host.wholeDesktop()));
            return result;
        });
    }

    private AutomationGraphics() { }
}

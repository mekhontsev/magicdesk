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
        Handler main = new Handler(Looper.getMainLooper());
        CountDownLatch completed = new CountDownLatch(1);
        JSONObject[] result = new JSONObject[1];
        Throwable[] failure = new Throwable[1];
        Intent[] intent = new Intent[1];
        Runnable action = () -> {
            try {
                var context = MagicDeskApplication.applicationContext();
                if (operation.equals("graphics.list")) {
                    result[0] = new JSONObject().put("sessions", snapshot());
                } else if (operation.equals("graphics.start")) {
                    String command = args.optString("command", "");
                    if (!command.isBlank()) DesktopExecCommand.normalize(command);
                    var session = GraphicalSessions.start(context, GraphicalSessions.protocol(args.getString("protocol")),
                            args.getString("name"), command, args.optString("directory", ""),
                            DesktopExecBackend.parse(args.getString("backend")), args.optString("keyboardDirectory", ""));
                    result[0] = describe(session).put("accepted", true);
                } else {
                    var session = GraphicalSessions.find(args.getString("sessionId"));
                    if (session == null) throw new IllegalArgumentException("Graphical session is unavailable");
                    switch (operation) {
                        case "graphics.execute" -> session.execute(DesktopExecCommand.normalize(args.getString("command")), args.optString("directory", ""));
                        case "graphics.stop" -> session.close();
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
        JSONArray sessions = new JSONArray();
        for (var session : GraphicalSessions.list()) sessions.put(describe(session));
        return sessions;
    }

    static JSONObject describe(GraphicalSessions.Session session) throws org.json.JSONException {
        JSONArray windows = new JSONArray();
        for (var window : session.windows()) windows.put(new JSONObject().put("windowId", window.id())
                .put("title", window.title()).put("mapped", window.mapped()));
        return new JSONObject().put("sessionId", session.id()).put("name", session.name())
                .put("protocol", session.protocol().name().toLowerCase(java.util.Locale.ROOT))
                .put("state", session.state()).put("ready", session.ready()).put("error", session.error())
                .put("wholeDesktop", session.desktop()).put("windows", windows);
    }

    private AutomationGraphics() { }
}

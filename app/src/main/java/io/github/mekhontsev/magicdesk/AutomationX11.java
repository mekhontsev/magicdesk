package io.github.mekhontsev.magicdesk;

import io.github.mekhontsev.magicdesk.x11.X11WindowInspection;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Read-only adapter; X11 owns membership and focus, Android hosts own their placement. */
final class AutomationX11 {
    static DesktopAutomationResult inspect(JSONObject args) throws JSONException {
        String sessionId = args.getString("sessionId");
        long window = AutomationJsonArguments.requiredLong(args, "windowId");
        int limit = args.has("limit") ? AutomationJsonArguments.requiredInt(args, "limit") : 256;
        if (sessionId.isBlank() || window <= 0 || window > 0xffffffffL || limit < 1 || limit > 256)
            throw new IllegalArgumentException("Expected sessionId, X11 windowId and limit from 1 to 256");
        X11Sessions.Session session = X11Sessions.find(sessionId);
        if (session == null) return DesktopAutomationResult.failure(DesktopAutomationErrorCode.HOST_UNAVAILABLE,
                "X11 session is unavailable", false);
        var request = session.inspectWindow(window, limit);
        CountDownLatch completed = new CountDownLatch(1);
        request.whenComplete((value, error) -> completed.countDown());
        try {
            var pending = AutomationCallbackWait.await(completed, 7000, "X11 window inspection", true,
                    new JSONObject().put("sessionId", sessionId).put("windowId", window));
            if (pending != null) return pending;
            X11Sessions.Inspection result = request.join();
            JSONObject data = family(sessionId, result.family());
            JSONArray hosts = new JSONArray();
            for (X11Sessions.Host host : result.hosts()) {
                var geometry = host.geometry();
                hosts.put(new JSONObject().put("taskId", host.taskId()).put("displayId", host.displayId())
                        .put("windowId", host.windowId()).put("focused", host.focused())
                        .put("contentWidth", geometry.contentWidth()).put("contentHeight", geometry.contentHeight())
                        .put("contentBoundsOnDisplay", new JSONObject().put("left", geometry.left()).put("top", geometry.top())
                                .put("right", geometry.right()).put("bottom", geometry.bottom())));
            }
            return DesktopAutomationResult.success("X11 window inspected", data.put("hosts", hosts));
        } catch (java.util.concurrent.CompletionException error) {
            return DesktopAutomationResult.failure(DesktopAutomationErrorCode.ACTION_FAILED,
                    ShellAccess.usefulMessage(error.getCause()), true);
        } finally { request.cancel(false); }
    }

    static JSONObject family(String sessionId, X11WindowInspection family) throws JSONException {
        JSONArray windows = new JSONArray();
        for (var node : family.windows()) windows.put(new JSONObject()
                .put("id", node.id()).put("parentId", node.parentId()).put("transientFor", node.transientFor())
                .put("clientLeader", node.clientLeader()).put("title", node.title())
                .put("type", node.type().name().toLowerCase(Locale.ROOT)).put("bounds", bounds(node.bounds()))
                .put("mapped", node.mapped()).put("realized", node.realized()).put("inputOnly", node.inputOnly())
                .put("overrideRedirect", node.overrideRedirect()).put("modal", node.modal())
                .put("focused", family.focus().kind().equals("window") && family.focus().windowId() == node.id()));
        return new JSONObject().put("sessionId", sessionId).put("windowId", family.windowId())
                .put("found", family.found()).put("truncated", family.truncated())
                .put("coordinateSpace", "x11_root").put("screenBounds", bounds(family.screenBounds()))
                .put("focus", new JSONObject().put("kind", family.focus().kind()).put("windowId", family.focus().windowId()))
                .put("windows", windows);
    }

    private static JSONObject bounds(X11WindowInspection.Bounds bounds) throws JSONException {
        return new JSONObject().put("left", bounds.left()).put("top", bounds.top())
                .put("right", bounds.right()).put("bottom", bounds.bottom());
    }

    private AutomationX11() { }
}

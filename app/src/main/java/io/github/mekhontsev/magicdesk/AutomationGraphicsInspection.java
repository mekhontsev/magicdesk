package io.github.mekhontsev.magicdesk;

import io.github.mekhontsev.magicdesk.x11.X11WindowInspection;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Read-only adapter; each native protocol owns its family, Android hosts own placement. */
final class AutomationGraphicsInspection {
    static final class Requests implements AutoCloseable {
        private final java.util.List<CompletableFuture<?>> pending = new java.util.ArrayList<>();
        private boolean closed;
        synchronized <T> CompletableFuture<T> start(Supplier<CompletableFuture<T>> action) {
            if (closed) return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException());
            var request = action.get();
            pending.add(request);
            return request;
        }
        @Override public void close() {
            java.util.List<CompletableFuture<?>> cancelled;
            synchronized (this) { closed = true; cancelled = java.util.List.copyOf(pending); pending.clear(); }
            for (var request : cancelled) request.cancel(false);
        }
    }
    static DesktopAutomationResult inspect(JSONObject args) {
        try { return inspectChecked(args); }
        catch (JSONException error) { throw new IllegalArgumentException(error); }
    }
    private static DesktopAutomationResult inspectChecked(JSONObject args) throws JSONException {
        String sessionId = args.getString("sessionId");
        long window = AutomationJsonArguments.requiredLong(args, "windowId");
        int limit = args.has("limit") ? AutomationJsonArguments.requiredInt(args, "limit") : 256;
        if (sessionId.isBlank() || window <= 0 || limit < 1 || limit > 256)
            throw new IllegalArgumentException("Expected sessionId, native windowId and limit from 1 to 256");
        var session = GraphicalSessions.find(sessionId);
        if (session == null) return DesktopAutomationResult.failure(DesktopAutomationErrorCode.HOST_UNAVAILABLE,
                "Graphical session is unavailable", false);
        try (var pending = new Requests()) {
            var request = pending.start(() -> AutomationMainThread.submit(() -> {
                if (session.protocol() == GraphicalProtocol.X11) {
                    var nativeRequest = pending.start(() -> X11Sessions.find(sessionId).inspectWindow(window, limit));
                    return nativeRequest.thenApply(result -> {
                        try { return family(sessionId, result).put("protocol", "x11").put("nativeAtomic", true); }
                        catch (JSONException error) { throw new IllegalStateException(error); }
                    });
                }
                return inspectWayland(session, window, limit, pending);
            })).thenCompose(value -> value).thenCompose(data -> pending.start(() -> AutomationMainThread.submit(() -> {
                if (GraphicalSessions.find(sessionId) == null || !session.ready())
                    throw new IllegalStateException("Session ended during inspection");
                return data.put("hosts", AutomationGraphics.hosts(session)).put("catalog", AutomationGraphics.describe(session).getJSONArray("windows"))
                        .put("atomicWithHosts", false);
            })));
            return AutomationMainThread.await(request, "graphics.inspect_window", true);
        }
    }

    record Selection(java.util.Map<Long, Long> parents, boolean truncated) { }
    static Selection selectFamily(java.util.List<GraphicalSessions.Window> catalog, long root, int limit) {
        var parents = new java.util.LinkedHashMap<Long, Long>();
        parents.put(root, 0L);
        int visited = 0;
        boolean expanded, truncated = false;
        traversal: do {
            expanded = false;
            for (var item : catalog) {
                if (++visited > 4096) { truncated = true; break traversal; }
                if (item.id() == root) parents.put(root, item.layout().parent());
                if (parents.containsKey(item.id()) || !parents.containsKey(item.layout().parent())) continue;
                if (parents.size() == limit) { truncated = true; break traversal; }
                parents.put(item.id(), item.layout().parent());
                expanded = true;
            }
        } while (expanded);
        return new Selection(java.util.Collections.unmodifiableMap(parents), truncated);
    }

    private static java.util.concurrent.CompletableFuture<JSONObject> inspectWayland(GraphicalSessions.Session session, long window,
            int limit, Requests pending) throws JSONException {
        var nativeSession = WaylandSessions.find(session.id());
        var selected = selectFamily(session.windows(), window, limit);
        var nodes = new JSONArray();
        var data = new JSONObject().put("sessionId", session.id()).put("windowId", window).put("protocol", "wayland")
                .put("coordinateSpace", "wayland_surface_family").put("found", false).put("truncated", selected.truncated())
                .put("nativeAtomic", selected.parents().size() == 1).put("windows", nodes);
        java.util.concurrent.CompletableFuture<JSONObject> result = java.util.concurrent.CompletableFuture.completedFuture(data);
        for (long target : selected.parents().keySet()) result = result.thenCompose(current -> {
            try {
                if (nodes.length() == limit) { current.put("truncated", true); return java.util.concurrent.CompletableFuture.completedFuture(current); }
                var next = pending.start(() -> nativeSession.inspectWindow(target, limit - nodes.length()));
                return next.thenApply(family -> {
                    try {
                        if (target == window) current.put("found", family.found());
                        if (family.truncated()) current.put("truncated", true);
                        for (var node : family.nodes()) {
                            long parent = node.parent();
                            if (node.role().equals("owner")) parent = selected.parents().get(target);
                            nodes.put(new JSONObject().put("id", node.id()).put("parentId", parent).put("ownerWindowId", target)
                                    .put("type", node.role()).put("mapped", node.mapped()).put("enabled", node.enabled()).put("focused", node.focused())
                                    .put("bounds", new JSONObject().put("left", node.left()).put("top", node.top()).put("right", node.right()).put("bottom", node.bottom())));
                        }
                        return current;
                    } catch (JSONException error) { throw new IllegalStateException(error); }
                });
            } catch (JSONException error) { return java.util.concurrent.CompletableFuture.failedFuture(error); }
        });
        return result;
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

    private AutomationGraphicsInspection() { }
}

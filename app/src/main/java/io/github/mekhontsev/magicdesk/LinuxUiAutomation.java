package io.github.mekhontsev.magicdesk;

import android.os.SystemClock;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** AT-SPI implementation of the shared semantic UI contract. No display/task ownership or input emulation. */
final class LinuxUiAutomation implements AutoCloseable {
    private final Map<String, Session> sessions = new HashMap<>();
    private final LinkedHashMap<String, Snapshot> snapshots = new LinkedHashMap<>();
    private record Session(AtSpiClient client, CompletableFuture<Void> released) { }
    private record Handle(AtSpiClient.Node node, long generation) { }
    private record Snapshot(String id, Session session, long created, Map<String, Handle> handles) { }
    private record Pending(AtSpiClient.Ref ref, String parent, int depth) { }

    static boolean handles(String id) { return id.startsWith("atspi-"); }

    JSONObject execute(String operation, JSONObject args) throws IOException, JSONException {
        if (operation.equals("ui.read_text") || operation.equals("ui.perform")) {
            String id = args.getString("elementId");
            Snapshot snapshot = snapshot(id);
            Handle handle = snapshot.handles.get(id);
            if (operation.equals("ui.read_text")) {
                String field = args.optString("field", "text");
                if (!Set.of("text", "description").contains(field)) throw new IllegalArgumentException("Invalid text field");
                return UiText.page(handle.node.data().optString(field, ""), handle.node.data().getBoolean("password"), args)
                        .put("elementId", id).put("snapshotId", snapshot.id).put("field", field)
                        .put("capturedAtUptimeMillis", snapshot.created).put("textComplete", handle.node.textComplete());
            }
            requireUnchanged(snapshot.session, handle);
            return new JSONObject().put("elementId", id).put("action", args.getString("action"))
                    .put("accepted", snapshot.session.client.perform(handle.node, args));
        }
        String sessionId = args.getString("sessionId");
        if (operation.equals("ui.release")) { release(sessionId); return new JSONObject().put("released", true); }
        Session session = session(sessionId);
        if (operation.equals("ui.prepare")) return prepare(session);
        if (operation.equals("ui.inspect")) return inspect(session, args);
        if (!operation.equals("ui.wait")) throw new IllegalArgumentException("Unknown UI operation");
        if (!args.has("selector")) throw new IllegalArgumentException("ui.wait requires selector");
        boolean present = args.optString("condition", "present").equals("present");
        long until = SystemClock.uptimeMillis() + UiSelector.integer(args, "timeoutMillis", 5000, 0, 60000);
        for (;;) {
            if (session.released.isDone()) throw new IOException("UI observation released");
            JSONObject observation = inspect(session, args);
            boolean matched = UiSelector.satisfied(present, observation.getJSONArray("nodes").length(),
                    observation.getBoolean("complete"), observation.getBoolean("stable"));
            long remaining = until - SystemClock.uptimeMillis();
            if (matched || remaining <= 0) return waitResult(observation, matched);
            try {
                // EVENT_WAIT: AT-SPI change or explicit release; timeout returns the last qualified observation.
                CompletableFuture.anyOf(session.client.bus.after(observation.getLong("generationStart")), session.released)
                        .get(remaining, TimeUnit.MILLISECONDS);
            } catch (TimeoutException error) { return waitResult(observation, false); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("UI wait interrupted", error); }
            catch (ExecutionException error) { throw new IOException("Linux UI event stream ended", error); }
        }
    }

    private static JSONObject waitResult(JSONObject observation, boolean matched) throws JSONException {
        return observation.put("matched", matched).put("timedOut", !matched).put("matches", observation.getJSONArray("nodes"));
    }

    private synchronized Session session(String id) throws IOException {
        var bus = LinuxAutomationLaunch.target(id).bus();
        sessions.entrySet().removeIf(entry -> !entry.getValue().client.bus.alive());
        return sessions.computeIfAbsent(id, ignored -> new Session(new AtSpiClient(bus), new CompletableFuture<>()));
    }
    private synchronized Snapshot snapshot(String id) throws IOException {
        expire();
        for (Snapshot snapshot : snapshots.values()) if (snapshot.handles.containsKey(id)) return snapshot;
        throw new IOException("Stale UI element; inspect the current Linux UI again");
    }
    private void expire() {
        long now = SystemClock.uptimeMillis();
        snapshots.values().removeIf(snapshot -> now - snapshot.created > 60000 || snapshot.session.released.isDone() || !snapshot.session.client.bus.alive());
    }

    private JSONObject inspect(Session session, JSONObject args) throws IOException, JSONException {
        for (String incompatible : List.of("displayId", "taskId", "windowId"))
            if (args.has(incompatible)) throw new IllegalArgumentException("Linux session cannot use " + incompatible);
        int limit = UiSelector.integer(args, "maxNodes", 200, 1, 256);
        UiSelector selector = args.has("selector") ? new UiSelector(args.getJSONObject("selector"), Set.of("name", "role", "accessibleId", "applicationId")) : null;
        session.client.observe();
        long generation = session.client.bus.generation(), created = SystemClock.uptimeMillis(), deadline = created + 3000;
        String snapshotId = "atspi-" + UUID.randomUUID();
        Map<String, Handle> handles = new LinkedHashMap<>();
        JSONArray nodes = new JSONArray(), failures = new JSONArray();
        ArrayDeque<Pending> pending = new ArrayDeque<>();
        String application = args.optString("applicationId", "");
        if (args.has("rootElementId")) {
            Snapshot previous = snapshot(args.getString("rootElementId"));
            if (previous.session != session) throw new IllegalArgumentException("UI element belongs to another Linux session");
            Handle handle = previous.handles.get(args.getString("rootElementId")); requireUnchanged(session, handle);
            if (!application.isEmpty() && !application.equals(handle.node.ref().destination())) throw new IllegalArgumentException("Wrong application owner");
            pending.add(new Pending(handle.node.ref(), null, 0));
        } else for (AtSpiClient.Ref ref : session.client.applications()) {
            if (application.isEmpty() || ref.destination().equals(application)) pending.add(new Pending(ref, null, 0));
        }
        boolean available = !pending.isEmpty(), complete = available, redacted = false, textTruncated = false;
        Set<AtSpiClient.Ref> visited = new HashSet<>();
        int textBudget = 256 * 1024;
        while (!pending.isEmpty()) {
            if (nodes.length() >= limit || visited.size() >= 4096 || SystemClock.uptimeMillis() >= deadline || textBudget <= 0) { complete = false; break; }
            Pending next = pending.removeFirst();
            if (!visited.add(next.ref)) { complete = false; continue; }
            try {
                AtSpiClient.Node node = session.client.read(next.ref, deadline);
                if (!node.textComplete()) complete = false;
                if (selector != null && selector.couldMatchRedacted(node.data())) redacted = true;
                String elementId = null;
                if (selector == null || selector.matches(node.data())) {
                    int retained = 0;
                    for (String field : List.of("text", "description", "name"))
                        if (!node.data().isNull(field)) retained += node.data().getString(field).length();
                    if (retained > textBudget) { complete = false; break; }
                    textBudget -= retained;
                    elementId = snapshotId + ":" + handles.size();
                    handles.put(elementId, new Handle(node, generation));
                    JSONObject data = new JSONObject(node.data().toString());
                    for (String field : List.of("text", "description", "name")) {
                        if (data.isNull(field)) { data.put(field + "Length", JSONObject.NULL); continue; }
                        String full = data.getString(field);
                        int length = Math.min(512, full.length());
                        if (length < full.length() && length > 0 && Character.isHighSurrogate(full.charAt(length - 1))) --length;
                        data.put(field, full.substring(0, length)).put(field + "Length", full.length()).put(field + "Truncated", length < full.length());
                        textTruncated |= length < full.length();
                    }
                    nodes.put(data.put("elementId", elementId).put("parentId", next.parent == null ? JSONObject.NULL : next.parent).put("depth", next.depth));
                }
                List<AtSpiClient.Ref> children = AtSpiClient.references(node.children());
                if (next.depth >= 40) { if (!children.isEmpty()) complete = false; continue; }
                for (AtSpiClient.Ref child : children) {
                    if (pending.size() + visited.size() >= 4096) { complete = false; break; }
                    pending.addLast(new Pending(child, elementId, next.depth + 1));
                }
            } catch (IOException error) {
                complete = false;
                if (failures.length() < 8) failures.put(ShellAccess.usefulMessage(error));
            }
        }
        long end = session.client.bus.generation();
        boolean stable = generation == end;
        synchronized (this) {
            expire(); snapshots.put(snapshotId, new Snapshot(snapshotId, session, created, handles));
            while (snapshots.size() > 4) snapshots.remove(snapshots.keySet().iterator().next());
        }
        return new JSONObject().put("backend", "atspi").put("sessionId", session.client.bus.id).put("applicationId", application)
                .put("snapshotId", snapshotId).put("nodes", nodes).put("windows", new JSONArray())
                .put("targetAvailable", available).put("unavailableReason", available ? "" : "no_accessible_application")
                .put("complete", complete && stable && !redacted).put("hierarchyComplete", complete).put("stable", stable)
                .put("generationStart", generation).put("generationEnd", end).put("redactedMatchPossible", redacted)
                .put("textTruncated", textTruncated).put("visitedNodes", visited.size()).put("handleLifetimeMillis", 60000)
                .put("capturedAtUptimeMillis", created).put("errors", failures);
    }

    private static void requireUnchanged(Session session, Handle handle) throws IOException, JSONException {
        JSONObject events = session.client.bus.events(handle.generation);
        if (events.getBoolean("overflow")) throw new IOException("UI event history expired; inspect again");
        JSONArray changes = events.getJSONArray("events");
        for (int i = 0; i < changes.length(); ++i) {
            JSONObject event = changes.getJSONObject(i);
            if (event.optString("member").equals("ChildrenChanged") || event.optString("member").equals("NameOwnerChanged"))
                throw new IOException("UI structure changed; inspect again");
        }
    }
    private static JSONObject prepare(Session session) throws IOException, JSONException {
        session.client.observe();
        JSONObject status = AtSpiClient.dictionary(session.client.bus.call(0, "org.a11y.Bus", "/org/a11y/bus",
                "org.freedesktop.DBus.Properties", "GetAll", AtSpiClient.args(AtSpiClient.v("s", "org.a11y.Status")), 3000));
        for (String property : List.of("IsEnabled", "ScreenReaderEnabled")) {
            if (!status.has(property) || status.getJSONObject(property).optString("value").equals("true")) continue;
            session.client.bus.call(0, "org.a11y.Bus", "/org/a11y/bus", "org.freedesktop.DBus.Properties", "Set",
                    AtSpiClient.args(AtSpiClient.v("s", "org.a11y.Status"), AtSpiClient.v("s", property),
                            AtSpiClient.v("v", AtSpiClient.v("b", true))), 3000);
        }
        JSONObject confirmed = AtSpiClient.dictionary(session.client.bus.call(0, "org.a11y.Bus", "/org/a11y/bus",
                "org.freedesktop.DBus.Properties", "GetAll", AtSpiClient.args(AtSpiClient.v("s", "org.a11y.Status")), 3000));
        return new JSONObject().put("enabled", confirmed.has("IsEnabled")
                        ? confirmed.getJSONObject("IsEnabled").getBoolean("value") : JSONObject.NULL)
                .put("sessionId", session.client.bus.id)
                .put("runningApplicationsRestarted", false).put("treeAvailability", "application-dependent");
    }
    private void release(String id) {
        Session session;
        synchronized (this) { session = sessions.remove(id); if (session != null) { session.released.complete(null); expire(); } }
        if (session != null) session.client.release();
    }
    @Override public void close() {
        List<String> ids; synchronized (this) { ids = List.copyOf(sessions.keySet()); }
        for (String id : ids) release(id);
    }
}

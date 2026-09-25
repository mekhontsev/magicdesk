package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Workspace-scoped public handles; stale protocol requests never become raw task operations. */
final class ShellTaskCatalog implements AutoCloseable {
    enum Action { ACTIVATE, MAXIMIZE, FULLSCREEN, UNMAXIMIZE, UNFULLSCREEN, CLOSE, MINIMIZE, UNMINIMIZE }
    record Task(int taskId, String identity, String title, String appId, boolean active, boolean fullscreen, boolean maximized, boolean minimized) { }
    record Window(long id, Task task) { }
    interface Actions { void request(Task task, Action action); }
    private final Actions actions;
    private final Map<Integer, Window> windows = new LinkedHashMap<>();
    private final Set<Runnable> listeners = new LinkedHashSet<>();
    private List<Window> snapshot = List.of();
    private long serial, generation;
    private boolean available, closed;

    ShellTaskCatalog(Actions actions) { this.actions = java.util.Objects.requireNonNull(actions); }
    List<Window> snapshot() { return snapshot; }
    boolean available() { return available && !closed; }
    void listen(Runnable listener) { if (closed) throw new IllegalStateException("Workspace closed"); listeners.add(listener); }
    void unlisten(Runnable listener) { listeners.remove(listener); }

    void update(List<Task> tasks, boolean known) {
        if (closed) return;
        boolean availabilityChanged = available != known;
        available = known;
        if (!known) { if (availabilityChanged) changed(); return; }
        var next = new LinkedHashMap<Integer, Window>();
        for (var task : tasks) {
            if (next.containsKey(task.taskId())) throw new IllegalArgumentException("Duplicate task identity");
            var previous = windows.get(task.taskId());
            long id = previous != null && previous.task().identity().equals(task.identity()) ? previous.id() : ++serial;
            next.put(task.taskId(), new Window(id, task));
        }
        windows.clear(); windows.putAll(next);
        var publication = List.copyOf(next.values());
        if (snapshot.equals(publication) && !availabilityChanged) return;
        snapshot = publication;
        changed();
    }

    boolean request(long id, Action action) {
        if (closed || !available) return false;
        for (var window : snapshot) if (window.id() == id) {
            actions.request(window.task(), java.util.Objects.requireNonNull(action));
            return true;
        }
        return false;
    }

    private void changed() {
        long current = ++generation;
        for (var listener : new ArrayList<>(listeners)) {
            if (current != generation) break;
            if (listeners.contains(listener)) listener.run();
        }
    }
    @Override public void close() {
        if (closed) return;
        closed = true; available = false;
        windows.clear(); snapshot = List.of();
        changed(); listeners.clear();
    }
}

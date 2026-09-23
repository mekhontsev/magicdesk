package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Event-thread presentation policy for one workspace, independent of geometry and protocol. */
final class ShellPresentationScope implements AutoCloseable {
    private Set<ShellSurface.Layer> visible = Set.of(ShellSurface.Layer.values());
    private final List<Runnable> listeners = new ArrayList<>();
    private boolean closed;
    private long generation;

    boolean visible(ShellSurface.Layer layer) { return visible.contains(layer); }
    boolean isClosed() { return closed; }

    void update(Set<ShellSurface.Layer> layers) {
        if (closed) throw new IllegalStateException("Shell presentation scope is closed");
        if (visible.equals(layers)) return;
        visible = Set.copyOf(layers);
        publish();
    }

    void listen(Runnable listener) {
        if (closed) throw new IllegalStateException("Shell presentation scope is closed");
        if (!listeners.contains(listener)) listeners.add(Objects.requireNonNull(listener));
    }

    void unlisten(Runnable listener) { listeners.remove(listener); }

    private void publish() {
        long current = ++generation;
        for (var listener : List.copyOf(listeners)) {
            if (current != generation) break;
            if (listeners.contains(listener)) listener.run();
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        visible = Set.of();
        publish();
        listeners.clear();
    }
}

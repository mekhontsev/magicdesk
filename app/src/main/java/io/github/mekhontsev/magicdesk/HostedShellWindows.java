package io.github.mekhontsev.magicdesk;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** Event-thread reconciliation of borrowed shell windows, independent of protocol and Android. */
final class HostedShellWindows implements AutoCloseable {
    record Surface(long id, ShellSurface.Layer layer, ShellSurface.Keyboard keyboard,
            ShellBounds bounds, HostedShellFrame frame) {
        Surface {
            Objects.requireNonNull(layer);
            Objects.requireNonNull(keyboard);
            if ((bounds == null) != (frame == null) || bounds != null && bounds.isEmpty())
                throw new IllegalArgumentException("Invalid shell presentation");
        }
    }

    interface Host {
        void validate(Surface surface);
        Window open(Surface surface);
    }

    interface Window extends AutoCloseable {
        CompletableFuture<Void> ready();
        CompletableFuture<Void> ended();
        CompletableFuture<Void> present(ShellBounds bounds, HostedShellFrame frame);
        @Override void close();
    }

    private final Host host;
    private final ShellPresentationScope presentation;
    private final Runnable presentationChanged = this::presentationChanged;
    private final Executor events;
    private final Consumer<Throwable> failure;
    private final Map<Long, Entry> windows = new LinkedHashMap<>();
    private List<Surface> surfaces = List.of();
    private boolean closed, reconciling, reconcilePending;

    HostedShellWindows(Host host, ShellPresentationScope presentation, Executor events, Consumer<Throwable> failure) {
        this.host = Objects.requireNonNull(host);
        this.presentation = Objects.requireNonNull(presentation);
        this.events = Objects.requireNonNull(events);
        this.failure = Objects.requireNonNull(failure);
        presentation.listen(presentationChanged);
    }

    void update(List<Surface> next) {
        if (closed) return;
        try {
            var accepted = new LinkedHashMap<Long, Surface>();
            for (var surface : next) {
                if (accepted.put(surface.id(), surface) != null)
                    throw new IllegalArgumentException("Duplicate shell presentation identity");
                // Validate unmapped clients too: unsupported roles must not silently reserve workspace space.
                host.validate(surface);
            }
            surfaces = List.copyOf(accepted.values());
            reconcile();
        } catch (RuntimeException error) { fail(error); }
    }

    private void presentationChanged() {
        if (closed) return;
        if (presentation.isClosed()) { fail(new IllegalStateException("Shell presentation scope was released")); return; }
        try { reconcile(); }
        catch (RuntimeException error) { fail(error); }
    }

    CompletableFuture<Void> presented(long id) {
        var entry = windows.get(id);
        return entry == null ? CompletableFuture.failedFuture(new IllegalStateException("Shell surface is not hosted"))
                : entry.receipt.copy();
    }

    private void reconcile() {
        if (reconciling) { reconcilePending = true; return; }
        reconciling = true;
        try {
            do {
                reconcilePending = false;
                reconcileOnce();
            } while (!closed && reconcilePending);
        } finally { reconciling = false; }
    }

    private void reconcileOnce() {
        var wanted = new LinkedHashMap<Long, Surface>();
        for (var surface : surfaces)
            if (surface.frame() != null && presentation.visible(surface.layer())) wanted.put(surface.id(), surface);
        for (var entry : List.copyOf(windows.values())) {
            if (!wanted.containsKey(entry.surface.id())) remove(entry);
        }
        for (var surface : wanted.values()) {
            if (closed || reconcilePending) break;
            var entry = windows.get(surface.id());
            if (entry != null && (entry.surface.layer() != surface.layer()
                    || entry.surface.keyboard() != surface.keyboard())) {
                remove(entry);
                entry = null;
            }
            if (closed || reconcilePending) break;
            if (entry == null) {
                entry = new Entry(surface);
                windows.put(surface.id(), entry);
                final var current = entry;
                try {
                    current.window = host.open(surface);
                    if (!owns(current)) { current.window.close(); break; }
                    current.window.ended().whenComplete((ignored, error) -> events.execute(() -> {
                        if (owns(current)) fail(error != null ? error : new IllegalStateException("Shell host was released"));
                    }));
                    current.window.ready().whenComplete((ignored, error) -> events.execute(() -> {
                        if (!owns(current)) return;
                        if (error != null) { fail(error); return; }
                        current.ready = true;
                        reconcile();
                    }));
                } catch (RuntimeException error) { fail(error); }
            } else if (!surface.equals(entry.surface)) {
                entry.surface = surface;
                entry.generation++;
                var previous = entry.receipt;
                entry.receipt = new CompletableFuture<>();
                previous.completeExceptionally(new CancellationException("Shell presentation replaced"));
            }
            if (!closed && !reconcilePending && owns(entry) && entry.ready) present(entry);
        }
    }

    private void present(Entry entry) {
        long generation = entry.generation;
        if (entry.submitted == generation) return;
        entry.submitted = generation;
        try {
            entry.window.present(entry.surface.bounds(), entry.surface.frame()).whenComplete((ignored, error) ->
                    events.execute(() -> {
                        if (!owns(entry) || entry.generation != generation) return;
                        if (error != null) { fail(error); return; }
                        entry.receipt.complete(null);
                    }));
        } catch (RuntimeException error) { fail(error); }
    }

    private boolean owns(Entry entry) { return !closed && windows.get(entry.surface.id()) == entry; }

    private void remove(Entry entry) {
        if (!windows.remove(entry.surface.id(), entry)) return;
        entry.receipt.completeExceptionally(new CancellationException("Shell window released"));
        if (entry.window != null) entry.window.close();
    }

    private void fail(Throwable error) {
        if (closed) return;
        close();
        failure.accept(error);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        presentation.unlisten(presentationChanged);
        surfaces = List.of();
        for (var entry : List.copyOf(windows.values())) remove(entry);
    }

    private static final class Entry {
        Surface surface;
        Window window;
        boolean ready;
        long generation;
        long submitted = -1;
        CompletableFuture<Void> receipt = new CompletableFuture<>();
        Entry(Surface surface) { this.surface = surface; }
    }
}

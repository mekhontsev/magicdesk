package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** One UI-thread catalog source; readers retain the last successful immutable result. */
final class ApplicationCatalogSource<T> {
    record Snapshot<T>(List<T> entries, boolean ready, boolean loading, String error) { }
    interface Completion<T> { void complete(List<T> entries, String error); }
    interface Loader<T> { void load(Completion<T> complete); }

    private final Loader<T> loader;
    private final Runnable changed;
    private final List<Consumer<Snapshot<T>>> waiting = new ArrayList<>();
    private Snapshot<T> snapshot = new Snapshot<>(List.of(), false, false, "");
    private long requestGeneration, revision;
    private boolean dirty = true;

    ApplicationCatalogSource(Loader<T> loader, Runnable changed) {
        this.loader = loader;
        this.changed = changed;
    }

    Snapshot<T> snapshot() { return snapshot; }

    void ensureLoaded() {
        if (dirty || !snapshot.ready()) refresh(null);
    }

    void invalidate() { dirty = true; revision++; }

    void reset(String error) {
        requestGeneration++;
        revision++;
        dirty = true;
        snapshot = new Snapshot<>(List.of(), false, false, error);
        finishWaiters(new Snapshot<>(List.of(), false, false,
                error.isEmpty() ? "Application catalog identity changed" : error));
        changed.run();
    }

    void refresh(Consumer<Snapshot<T>> complete) {
        if (complete != null) waiting.add(complete);
        if (snapshot.loading()) return;
        final long request = ++requestGeneration, requestedRevision = revision;
        dirty = false;
        snapshot = new Snapshot<>(snapshot.entries(), snapshot.ready(), true, "");
        changed.run();
        try {
            loader.load((entries, error) -> complete(request, requestedRevision, entries, error));
        } catch (RuntimeException error) {
            complete(request, requestedRevision, List.of(), error.toString());
        }
    }

    private void complete(long request, long requestedRevision, List<T> entries, String error) {
        if (request != requestGeneration || !snapshot.loading()) return;
        if (requestedRevision != revision) {
            snapshot = new Snapshot<>(snapshot.entries(), snapshot.ready(), false, "");
            refresh(null);
            return;
        }
        dirty = !error.isEmpty();
        snapshot = error.isEmpty()
                ? new Snapshot<>(List.copyOf(entries), true, false, "")
                : new Snapshot<>(snapshot.entries(), snapshot.ready(), false, error);
        finishWaiters(snapshot);
        changed.run();
    }

    private void finishWaiters(Snapshot<T> result) {
        final var callbacks = List.copyOf(waiting);
        waiting.clear();
        for (var callback : callbacks) callback.accept(result);
    }
}

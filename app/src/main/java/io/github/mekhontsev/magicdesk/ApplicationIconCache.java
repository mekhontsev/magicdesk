package io.github.mekhontsev.magicdesk;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** UI-thread, source-scoped icon loading; renderers only read immutable snapshots. */
final class ApplicationIconCache<T> {
    interface Loader<T> { void load(List<String> keys, Consumer<Map<String, T>> complete); }

    private final int batchSize;
    private final Loader<T> loader;
    private final Runnable changed;
    private Set<String> keys = Set.of();
    private volatile Map<String, T> snapshot = Map.of();
    private long generation;
    private boolean loading;

    ApplicationIconCache(int batchSize, Loader<T> loader, Runnable changed) {
        if (batchSize < 1) throw new IllegalArgumentException("Invalid icon batch size");
        this.batchSize = batchSize;
        this.loader = loader;
        this.changed = changed;
    }

    Map<String, T> snapshot() { return snapshot; }

    void reset() {
        generation++;
        loading = false;
        keys = Set.of();
        snapshot = Map.of();
    }

    void update(Set<String> requested) {
        keys = new LinkedHashSet<>(requested);
        if (!keys.containsAll(snapshot.keySet())) {
            final var retained = new HashMap<>(snapshot);
            retained.keySet().retainAll(keys);
            snapshot = Collections.unmodifiableMap(retained);
            changed.run();
        }
        loadNext();
    }

    private void loadNext() {
        if (loading) return;
        final List<String> batch = keys.stream().filter(key -> !snapshot.containsKey(key))
                .limit(batchSize).toList();
        if (batch.isEmpty()) return;
        loading = true;
        final long request = generation;
        loader.load(batch, values -> {
            if (request != generation) return;
            final var next = new HashMap<>(snapshot);
            // Null is a cached miss, including invalid images and failed reads.
            for (String key : batch) if (keys.contains(key)) next.put(key, values.get(key));
            snapshot = Collections.unmodifiableMap(next);
            loading = false;
            changed.run();
            loadNext();
        });
    }
}

package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.Test;

public final class ApplicationIconCacheTest {
    private final List<List<String>> requests = new ArrayList<>();
    private final List<Consumer<Map<String, String>>> pending = new ArrayList<>();
    private int changes;
    private final ApplicationIconCache<String> cache = new ApplicationIconCache<>(2, (keys, complete) -> {
        requests.add(keys);
        pending.add(complete);
    }, () -> changes++);

    @Test public void batchesDeduplicateAndCacheBothHitsAndMisses() {
        var keys = new LinkedHashSet<>(List.of("gimp", "firefox", "missing"));
        cache.update(keys);
        cache.update(keys);
        assertEquals(List.of(List.of("gimp", "firefox")), requests);
        pending.get(0).accept(Map.of("gimp", "pixels", "firefox", "browser"));
        assertEquals(List.of("missing"), requests.get(1));
        pending.get(1).accept(Map.of());
        var snapshot = cache.snapshot();
        cache.update(keys);
        assertEquals(2, requests.size());
        assertSame(snapshot, cache.snapshot());
        assertEquals("pixels", snapshot.get("gimp"));
        assertTrue(snapshot.containsKey("missing"));
        assertNull(snapshot.get("missing"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
    }

    @Test public void endpointResetDiscardsOldResultsAndReloadsNewOwner() {
        cache.update(Set.of("gimp"));
        cache.reset();
        cache.update(Set.of("gimp"));
        pending.get(0).accept(Map.of("gimp", "wrong endpoint"));
        assertTrue(cache.snapshot().isEmpty());
        assertEquals(0, changes);
        pending.get(1).accept(Map.of("gimp", "current endpoint"));
        assertEquals("current endpoint", cache.snapshot().get("gimp"));
        assertEquals(1, changes);
    }

    @Test public void catalogChangesPruneIconsAndDoNotResurrectRemovedKeys() {
        cache.update(Set.of("old"));
        cache.update(Set.of("new"));
        pending.get(0).accept(Map.of("old", "removed"));
        assertFalse(cache.snapshot().containsKey("old"));
        assertEquals(List.of("new"), requests.get(1));
        pending.get(1).accept(Map.of("new", "current"));
        var snapshot = cache.snapshot();
        cache.update(Set.of());
        assertTrue(cache.snapshot().isEmpty());
        assertEquals("current", snapshot.get("new"));
    }
}

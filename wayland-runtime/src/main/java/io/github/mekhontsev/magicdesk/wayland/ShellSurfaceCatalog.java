package io.github.mekhontsev.magicdesk.wayland;

import java.util.LinkedHashMap;
import java.util.List;

/** Event-thread state for one revocable shell owner, separate from application windows. */
final class ShellSurfaceCatalog {
    private final LinkedHashMap<Long, WaylandShellSurface> surfaces = new LinkedHashMap<>();
    private long current, last;

    long owner() { return current; }

    boolean acquire(long owner) {
        if (current != 0 || owner <= last) return false;
        current = last = owner;
        return true;
    }

    boolean release(long owner) {
        if (owner == 0 || owner != current) return false;
        current = 0;
        surfaces.clear();
        return true;
    }

    boolean update(long owner, long id, WaylandShellSurface surface) {
        if (owner == 0 || owner != current) return false;
        if (surface == null) return surfaces.remove(id) != null;
        if (id != surface.id()) throw new IllegalArgumentException("Shell surface identity mismatch");
        WaylandShellSurface previous = surfaces.get(id);
        if (previous != null && surface.revision() <= previous.revision()) return false;
        if (previous == null && surfaces.size() >= 32) throw new IllegalStateException("Shell surface limit exceeded");
        surfaces.put(id, surface);
        return true;
    }

    boolean contains(long id) { return surfaces.containsKey(id); }

    boolean accepts(long owner, long id, long revision) {
        WaylandShellSurface surface = surfaces.get(id);
        return owner != 0 && owner == current && surface != null && surface.revision() == revision;
    }

    List<WaylandShellSurface> snapshot() { return List.copyOf(surfaces.values()); }
}

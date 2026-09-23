package io.github.mekhontsev.magicdesk.wayland;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ShellSurfaceCatalogTest {
    @Test public void onlyOneLiveOwnerAndNoReuseAfterRelease() {
        ShellSurfaceCatalog catalog = new ShellSurfaceCatalog();
        assertFalse(catalog.acquire(0));
        assertTrue(catalog.acquire(1));
        assertFalse(catalog.acquire(1));
        assertFalse(catalog.acquire(2));
        assertFalse(catalog.release(2));
        assertTrue(catalog.release(1));
        assertFalse(catalog.acquire(1));
        assertTrue(catalog.acquire(2));
    }

    @Test public void revocationRejectsLateEventsAndConfigurations() {
        ShellSurfaceCatalog catalog = new ShellSurfaceCatalog();
        catalog.acquire(1);
        assertTrue(catalog.update(1, 10, surface(10, 1, false)));
        var before = catalog.snapshot();
        assertTrue(catalog.accepts(1, 10, 1));
        catalog.release(1);
        assertTrue(catalog.snapshot().isEmpty());
        assertFalse(catalog.accepts(1, 10, 1));
        catalog.acquire(2);
        assertFalse(catalog.update(1, 10, surface(10, 2, true)));
        assertTrue(catalog.update(2, 10, surface(10, 3, true)));
        assertFalse(catalog.update(1, 10, null));
        assertFalse(catalog.accepts(2, 10, 2));
        assertTrue(catalog.accepts(2, 10, 3));
        assertEquals(1, before.size());
        assertFalse(before.get(0).mapped());
    }

    @Test public void newerCommittedRevisionSupersedesInFlightGeometry() {
        ShellSurfaceCatalog catalog = new ShellSurfaceCatalog();
        catalog.acquire(1);
        catalog.update(1, 10, surface(10, 1, false));
        assertTrue(catalog.update(1, 10, surface(10, 2, true)));
        assertFalse(catalog.update(1, 10, surface(10, 1, false)));
        assertFalse(catalog.accepts(1, 10, 1));
        assertTrue(catalog.accepts(1, 10, 2));
        assertThrows(IllegalArgumentException.class, () -> catalog.update(1, 20, surface(10, 3, false)));
        assertTrue(catalog.update(1, 10, null));
        assertFalse(catalog.accepts(1, 10, 2));
        assertTrue(catalog.snapshot().isEmpty());
    }

    @Test public void metadataChangesDoNotReorderPanels() {
        ShellSurfaceCatalog catalog = new ShellSurfaceCatalog();
        catalog.acquire(1);
        catalog.update(1, 10, surface(10, 1, false));
        catalog.update(1, 20, surface(20, 2, false));
        catalog.update(1, 10, surface(10, 3, true));
        assertEquals(java.util.List.of(10L, 20L), catalog.snapshot().stream().map(WaylandShellSurface::id).toList());
    }

    @Test public void liveSurfaceCountIsBounded() {
        ShellSurfaceCatalog catalog = new ShellSurfaceCatalog();
        catalog.acquire(1);
        for (int i = 1; i <= 32; ++i) catalog.update(1, i, surface(i, i, false));
        assertThrows(IllegalStateException.class, () -> catalog.update(1, 33, surface(33, 33, false)));
        assertTrue(catalog.update(1, 1, surface(1, 40, true)));
        assertTrue(catalog.update(1, 1, null));
        assertTrue(catalog.update(1, 33, surface(33, 41, false)));
    }

    private static WaylandShellSurface surface(long id, long revision, boolean mapped) {
        return new WaylandShellSurface(id, revision, "panel", mapped, !mapped,
                WaylandShellSurface.Layer.TOP, WaylandShellSurface.Keyboard.NONE,
                WaylandShellSurface.TOP | WaylandShellSurface.LEFT | WaylandShellSurface.RIGHT,
                0, 24, 0, 3, 0, 0, 24);
    }
}

package io.github.mekhontsev.magicdesk;

import io.github.mekhontsev.magicdesk.wayland.WaylandShellSurface;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public final class WaylandShellLayoutTest {
    private static final ShellBounds OUTPUT = new ShellBounds(100, 200, 2020, 1280);
    private static final int TOP = WaylandShellSurface.TOP | WaylandShellSurface.LEFT | WaylandShellSurface.RIGHT;

    @Test public void reservesOnlyAfterMappingAndReleasesOnUnmap() {
        var scope = scope();
        try (var adapter = new WaylandShellLayout(scope)) {
            adapter.update(List.of(panel(1, 1, false, true, TOP, 24, 24)), 160);
            assertEquals(OUTPUT, scope.snapshot().workArea());
            assertEquals(new ShellBounds(0, 3, 1920, 27), adapter.configurations().get(0).bounds());
            adapter.update(List.of(panel(1, 2, true, false, TOP, 24, 24)), 160);
            assertEquals(227, scope.snapshot().workArea().top());
            adapter.update(List.of(panel(1, 3, false, false, TOP, 24, 24)), 160);
            assertEquals(OUTPUT, scope.snapshot().workArea());
            assertTrue(adapter.configurations().isEmpty());
            adapter.update(List.of(panel(1, 4, false, true, TOP, 24, 24)), 160);
            assertEquals(4, adapter.configurations().get(0).revision());
        }
        assertTrue(scope.snapshot().surfaces().isEmpty());
    }

    @Test public void densityConvertsRequestsReservationsAndConfigurationsAtOneBoundary() {
        var scope = scope();
        try (var adapter = new WaylandShellLayout(scope)) {
            adapter.update(List.of(panel(1, 1, true, false, TOP, 24, 24)), 320);
            assertEquals(new ShellBounds(0, 0, 960, 540), adapter.output());
            assertEquals(new ShellBounds(100, 206, 2020, 254), adapter.surface(1).content());
            assertEquals(254, scope.snapshot().workArea().top());
            assertEquals(new ShellBounds(0, 3, 960, 27), adapter.configurations().get(0).bounds());
            adapter.update(List.of(panel(1, 1, true, false, TOP, 24, 24)), 160);
            assertEquals(227, scope.snapshot().workArea().top());
            assertEquals(new ShellBounds(0, 3, 1920, 27), adapter.configurations().get(0).bounds());
        }
    }

    @Test public void panelsStackAndZeroZoneAvoidsThemWhileNegativeZoneIgnoresThem() {
        var scope = scope();
        try (var adapter = new WaylandShellLayout(scope)) {
            adapter.update(List.of(panel(1, 1, true, false, TOP, 24, 24),
                    panel(2, 2, true, false, TOP, 30, 30),
                    panel(3, 3, true, false, TOP, 10, 0),
                    panel(4, 4, true, false, TOP, 10, -1)), 160);
            assertEquals(203, adapter.surface(1).content().top());
            assertEquals(230, adapter.surface(2).content().top());
            assertEquals(263, adapter.surface(3).content().top());
            assertEquals(203, adapter.surface(4).content().top());
            assertEquals(260, scope.snapshot().workArea().top());
        }
    }

    @Test public void ambiguousExclusiveEdgeDoesNotReserveSpace() {
        var scope = scope();
        try (var adapter = new WaylandShellLayout(scope)) {
            var corner = new WaylandShellSurface(1, 1, "corner", true, false,
                    WaylandShellSurface.Layer.TOP, WaylandShellSurface.Keyboard.NONE,
                    WaylandShellSurface.LEFT | WaylandShellSurface.TOP, 30, 30, 0, 0, 0, 0, 100);
            adapter.update(List.of(corner), 160);
            assertEquals(OUTPUT, scope.snapshot().workArea());
        }
    }

    @Test public void nestedScopeAndRevokedOwnerCannotAffectWorkspace() {
        var outer = scope();
        var nested = scope();
        try (var adapter = new WaylandShellLayout(nested)) {
            adapter.update(List.of(panel(1, 1, true, false, TOP, 24, 24)), 160);
            assertEquals(OUTPUT, outer.snapshot().workArea());
            nested.clear();
            assertTrue(adapter.isClosed());
            assertTrue(adapter.configurations().isEmpty());
            assertThrows(IllegalStateException.class,
                    () -> adapter.update(List.of(panel(1, 2, true, false, TOP, 40, 40)), 160));
            assertEquals(OUTPUT, nested.snapshot().workArea());
        }
    }

    @Test public void invalidReplacementIsAtomicAndHugeProtocolSizesAreBounded() {
        var scope = scope();
        try (var adapter = new WaylandShellLayout(scope)) {
            var huge = new WaylandShellSurface(1, 1, "huge", true, false,
                    WaylandShellSurface.Layer.OVERLAY, WaylandShellSurface.Keyboard.EXCLUSIVE,
                    0, 0xffffffffL, 0xffffffffL, 0, 0, 0, 0, -1);
            adapter.update(List.of(huge), 320);
            assertEquals(OUTPUT, adapter.surface(1).content());
            assertEquals(new ShellBounds(0, 0, 960, 540), adapter.configurations().get(0).bounds());
            var before = scope.snapshot();
            assertThrows(IllegalArgumentException.class, () -> adapter.update(List.of(huge, huge), 160));
            assertSame(before, scope.snapshot());
            assertEquals(new ShellBounds(0, 0, 960, 540), adapter.output());
            assertEquals(ShellSurface.Keyboard.EXCLUSIVE, adapter.surface(1).request().keyboard());
        }
    }

    @Test public void fractionalDensityAndNonzeroOriginStayInsideLogicalOutput() {
        var scope = new ShellLayoutScope();
        var output = new ShellBounds(17, 23, 118, 74);
        scope.resize(output, output);
        try (var adapter = new WaylandShellLayout(scope)) {
            adapter.update(List.of(panel(1, 1, true, false,
                    WaylandShellSurface.BOTTOM | WaylandShellSurface.LEFT | WaylandShellSurface.RIGHT, 24, 24)), 212);
            var bounds = adapter.configurations().get(0).bounds();
            assertEquals(bounds, bounds.intersect(adapter.output()));
            assertEquals(adapter.output().bottom(), bounds.bottom());
        }
    }

    private static WaylandShellSurface panel(long id, long revision, boolean mapped, boolean configure,
            int anchors, int height, int zone) {
        return new WaylandShellSurface(id, revision, "panel", mapped, configure,
                WaylandShellSurface.Layer.TOP, WaylandShellSurface.Keyboard.NONE,
                anchors, 0, height, 0, 3, 0, 0, zone);
    }

    private static ShellLayoutScope scope() {
        var scope = new ShellLayoutScope();
        scope.resize(OUTPUT, OUTPUT);
        return scope;
    }
}

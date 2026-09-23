package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public final class ShellLayoutScopeTest {
    private static final ShellBounds OUTPUT = new ShellBounds(0, 0, 1920, 1080);

    @Test public void bindingOwnsOnlyItsSurfacesIncludingCollidingClientIds() {
        final ShellLayoutScope scope = scope();
        final var first = scope.bind();
        final var second = scope.bind();
        first.commit(List.of(panel("bar", 40)));
        second.commit(List.of(panel("bar", 60)));
        assertNotEquals(first.surface("bar").request().id(), second.surface("bar").request().id());
        assertEquals(100, scope.snapshot().workArea().top());
        first.close();
        assertEquals(60, scope.snapshot().workArea().top());
        assertNull(first.surface("bar"));
        assertNotNull(second.surface("bar"));
        assertThrows(IllegalStateException.class, () -> first.commit(List.of(panel("bar", 40))));
        first.close();
        assertEquals(60, scope.snapshot().workArea().top());
    }

    @Test public void invalidCommitCannotPartiallyReplaceAnOwner() {
        final ShellLayoutScope scope = scope();
        final var owner = scope.bind();
        owner.commit(List.of(panel("bar", 40)));
        final var before = scope.snapshot();
        assertThrows(IllegalArgumentException.class,
                () -> owner.commit(List.of(panel("bar", 20), panel("bar", 50))));
        assertSame(before, scope.snapshot());
        owner.commit(List.of(panel("bar", 40)));
        assertSame(before, scope.snapshot());
    }

    @Test public void viewportAndOwnerCommitPublishOneConsistentSnapshot() {
        final ShellLayoutScope scope = scope();
        final var owner = scope.bind();
        owner.commit(List.of(panel("bar", 40)));
        final var observed = new java.util.ArrayList<ShellLayout.Snapshot>();
        scope.listen(() -> observed.add(scope.snapshot()));
        final ShellBounds smaller = new ShellBounds(0, 0, 800, 600);
        owner.commit(smaller, smaller, List.of(panel("bar", 20)));
        assertEquals(1, observed.size());
        assertEquals(smaller, observed.get(0).output());
        assertEquals(new ShellBounds(0, 20, 800, 600), observed.get(0).workArea());
        final var valid = scope.snapshot();
        assertThrows(IllegalArgumentException.class,
                () -> owner.commit(smaller, OUTPUT, List.of(panel("bar", 10))));
        assertSame(valid, scope.snapshot());
        assertEquals(20, owner.surface("bar").content().height());
        assertEquals(1, observed.size());
    }

    @Test public void reentrantReleaseDoesNotDeliverAnObsoleteSnapshot() {
        final ShellLayoutScope scope = scope();
        final var owner = scope.bind();
        scope.listen(() -> {
            if (scope.snapshot().workArea().top() > 0) owner.close();
        });
        final var observed = new java.util.ArrayList<ShellBounds>();
        scope.listen(() -> observed.add(scope.snapshot().workArea()));
        owner.commit(List.of(panel("bar", 40)));
        assertEquals(List.of(OUTPUT), observed);
    }

    @Test public void scopeReleaseRevokesBindingsWithoutAffectingOtherScopes() {
        final ShellLayoutScope outer = scope();
        final ShellLayoutScope guest = scope();
        final var owner = outer.bind();
        final var nested = guest.bind();
        owner.commit(List.of(panel("bar", 40)));
        nested.commit(List.of(panel("bar", 60)));
        outer.clear();
        assertEquals(OUTPUT, outer.snapshot().workArea());
        assertEquals(60, guest.snapshot().workArea().top());
        assertThrows(IllegalStateException.class, () -> owner.commit(List.of()));
        final var replacement = outer.bind();
        replacement.commit(List.of(panel("bar", 20)));
        owner.close();
        assertEquals(20, outer.snapshot().workArea().top());
    }

    @Test public void changingCommitOrderChangesSameLayerPlacementPrecedence() {
        final ShellLayoutScope scope = scope();
        final var owner = scope.bind();
        final var first = panel("first", 40);
        final var second = panel("second", 60);
        owner.commit(List.of(first, second));
        assertEquals(0, owner.surface("first").content().top());
        assertEquals(40, owner.surface("second").content().top());
        owner.commit(List.of(second, first));
        assertEquals(0, owner.surface("second").content().top());
        assertEquals(60, owner.surface("first").content().top());
    }

    @Test public void emptyOwnerStillObservesScopeRevocation() {
        final ShellLayoutScope scope = scope();
        final var owner = scope.bind();
        final boolean[] notified = {false};
        scope.listen(() -> notified[0] = owner.isClosed());
        scope.clear();
        assertTrue(notified[0]);
    }

    @Test public void secondEdgePanelChangesStartAndWorkAreaWithoutConsumerSpecialCases() {
        final DesktopShellLayout desktop = new DesktopShellLayout();
        desktop.update(new DesktopViewport(0, 0, 1920, 1080, 0, 0, 0, 0), 64, true);
        final var start = desktop.bind();
        start.commit(List.of(new ShellSurface("start", true, ShellSurface.Layer.OVERLAY,
                ShellSurface.Keyboard.ON_DEMAND,
                new ShellSurface.Placement(ShellSurface.Reference.PANEL,
                        ShellSurface.LEFT | ShellSurface.TOP, 500, 400, ShellSurface.Margins.NONE),
                ShellSurface.Margins.NONE, ShellSurface.Input.CONTENT, List.of())));
        final var external = desktop.bind();
        external.commit(List.of(panel("top", 40)));
        assertEquals(40, start.surface("start").content().top());
        assertEquals(new ShellBounds(0, 40, 1920, 1080), desktop.snapshot().workArea());
        assertEquals(new ShellBounds(0, 40, 1920, 1016), desktop.snapshot().panelArea());
        external.close();
        assertEquals(0, start.surface("start").content().top());
        assertEquals(1080, desktop.snapshot().workArea().bottom());
    }

    private static ShellLayoutScope scope() {
        final ShellLayoutScope scope = new ShellLayoutScope();
        scope.resize(OUTPUT, OUTPUT);
        return scope;
    }

    private static ShellSurface panel(final String id, final int height) {
        return new ShellSurface(id, true, ShellSurface.Layer.TOP, ShellSurface.Keyboard.NONE,
                new ShellSurface.Placement(ShellSurface.Reference.AVAILABLE,
                        ShellSurface.LEFT | ShellSurface.RIGHT | ShellSurface.TOP,
                        0, height, ShellSurface.Margins.NONE), ShellSurface.Margins.NONE,
                ShellSurface.Input.CONTENT,
                List.of(ShellReservation.exclusive(ShellReservation.Edge.TOP, height, true)));
    }
}

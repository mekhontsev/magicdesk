package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ShellPresentationScopeTest {
    @Test public void commitsAreImmutableDistinctAndWorkspaceLocal() {
        var scope = new ShellPresentationScope();
        var other = new ShellPresentationScope();
        var calls = new ArrayList<Boolean>();
        Runnable listener = () -> calls.add(scope.visible(ShellSurface.Layer.TOP));
        scope.listen(listener);
        scope.listen(listener);
        var next = new java.util.HashSet<ShellSurface.Layer>();
        next.add(ShellSurface.Layer.BOTTOM);
        scope.update(next);
        next.add(ShellSurface.Layer.TOP);
        assertFalse(scope.visible(ShellSurface.Layer.TOP));
        assertTrue(other.visible(ShellSurface.Layer.TOP));
        scope.update(Set.of(ShellSurface.Layer.BOTTOM));
        scope.unlisten(listener);
        scope.update(next);
        assertEquals(List.of(false), calls);
    }

    @Test public void reentrantClosureDoesNotDeliverSupersededEmptyState() {
        var scope = new ShellPresentationScope();
        var calls = new ArrayList<Boolean>();
        scope.listen(scope::close);
        scope.listen(() -> calls.add(scope.isClosed()));
        scope.update(Set.of());
        scope.close();
        assertEquals(List.of(true), calls);
        assertThrows(IllegalStateException.class, () -> scope.update(Set.of()));
        assertThrows(IllegalStateException.class, () -> scope.listen(() -> {}));
    }
}

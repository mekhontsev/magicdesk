package io.github.mekhontsev.magicdesk;

import io.github.mekhontsev.magicdesk.x11.X11ShellSurface;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class X11ShellLayoutTest {
    private static final ShellBounds OUTPUT = new ShellBounds(100, 200, 2020, 1280);
    private static X11ShellSurface panel(long id, boolean mapped, long depth) {
        return new X11ShellSurface(id, X11ShellSurface.Role.DOCK, mapped,
                new X11ShellSurface.Rect(20, 0, 600, 40), new X11ShellSurface.Rect(0, 0, 580, 40), true,
                List.of(new X11ShellSurface.Rect(0, 0, 580, 40)),
                List.of(0L, 0L, depth, 0L, 0L, 0L, 0L, 0L, 20L, 599L, 0L, 0L));
    }
    @Test public void rootPixelsAndInclusivePartialRangeRespectScopeOrigin() {
        var intent = X11ShellLayout.intent(panel(1, true, 40), OUTPUT);
        assertEquals(ShellSurface.Keyboard.ON_DEMAND, intent.keyboard());
        assertEquals(List.of(ShellReservation.absolute(ShellReservation.Edge.TOP, 40, 120, 700)), intent.reservations());
        var scope = new ShellLayoutScope(); scope.resize(OUTPUT, OUTPUT);
        try (var layout = new X11ShellLayout(scope)) {
            layout.update(List.of(panel(1, true, 40)));
            assertEquals(new ShellBounds(120, 200, 700, 240), layout.surface(1).content());
            assertEquals(240, scope.snapshot().workArea().top());
            layout.update(List.of(panel(1, false, 40)));
            assertEquals(OUTPUT, scope.snapshot().workArea());
        }
    }
    @Test public void absoluteReservationsOverlapRatherThanStackAndReleaseTogether() {
        var scope = new ShellLayoutScope(); scope.resize(OUTPUT, OUTPUT);
        var layout = new X11ShellLayout(scope);
        layout.update(List.of(panel(1, true, 40), panel(2, true, 60)));
        assertEquals(260, scope.snapshot().workArea().top());
        layout.close();
        assertEquals(OUTPUT, scope.snapshot().workArea());
    }
    @Test public void unsignedStrutsClampToOutputAndRevocationRejectsFurtherContribution() {
        var intent = X11ShellLayout.intent(panel(1, true, 0xffffffffL), OUTPUT);
        assertEquals(1080, intent.reservations().get(0).depth());
        var scope = new ShellLayoutScope(); scope.resize(OUTPUT, OUTPUT);
        try (var layout = new X11ShellLayout(scope)) {
            scope.clear();
            assertTrue(layout.isClosed());
            assertThrows(IllegalStateException.class, () -> layout.update(List.of(panel(1, true, 40))));
        }
    }
}

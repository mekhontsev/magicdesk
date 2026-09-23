package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public final class ShellPanelPlacementTest {
    @Test public void startAvoidsAutoHiddenTaskbarButDoesNotReserveApplicationSpace() {
        final DesktopShellLayout layout = desktop();
        final ShellBounds work = layout.snapshot().workArea();
        assertEquals(new ShellBounds(16, 396, 576, 1016), place(layout,
                ShellPanelPlacement.anchored(560, 620, ShellSurface.LEFT | ShellSurface.BOTTOM,
                        16, 0, 0, 0)));
        assertEquals(work, layout.snapshot().workArea());
    }

    @Test public void pointerPopupFlipsAtBothEdgesAndConservesMargin() {
        assertEquals(new ShellBounds(1492, 792, 1892, 992), place(desktop(),
                ShellPanelPlacement.atPointer(1900, 1000, 400, 200, 8)));
    }

    @Test public void popupClampsOutsideAnchorWithoutOverflow() {
        assertEquals(new ShellBounds(8, 8, 1912, 1008), place(desktop(),
                ShellPanelPlacement.atPointer(Integer.MIN_VALUE, Integer.MAX_VALUE,
                        Integer.MAX_VALUE, Integer.MAX_VALUE, 8)));
    }

    @Test public void anchorMenuUsesAnchorRightAndTopRatherThanTaskbarHeight() {
        assertEquals(new ShellBounds(600, 800, 900, 1000), place(desktop(),
                ShellPanelPlacement.aboveRight(new ShellBounds(850, 1000, 900, 1050), 300, 200)));
    }

    @Test public void popupPreservesNonzeroScopeOrigin() {
        final DesktopShellLayout layout = new DesktopShellLayout();
        layout.update(new DesktopViewport(100, 200, 1100, 900, 0, 0, 0, 0), 50, false);
        assertEquals(new ShellBounds(118, 218, 418, 418), place(layout,
                ShellPanelPlacement.atPointer(110, 210, 300, 200, 8)));
    }

    @Test public void tinyOutputRetainsNonemptyBoundedPopup() {
        final DesktopShellLayout layout = new DesktopShellLayout();
        layout.update(new DesktopViewport(0, 0, 1, 1, 0, 0, 0, 0), 64, false);
        assertEquals(new ShellBounds(0, 0, 1, 1), place(layout,
                ShellPanelPlacement.atPointer(5, 6, 400, 200, 8)));
    }

    @Test public void childAnchorFollowsItsOwnerAndCannotOutliveIt() {
        final DesktopShellLayout layout = desktop();
        final var owner = layout.bind();
        owner.commit(List.of(new ShellSurface("start", true, ShellSurface.Layer.OVERLAY,
                ShellSurface.Keyboard.ON_DEMAND,
                ShellPanelPlacement.anchored(600, 500, ShellSurface.LEFT | ShellSurface.TOP,
                        0, 0, 0, 0).resolve(layout.snapshot()),
                ShellSurface.Margins.NONE, ShellSurface.Input.CONTENT, List.of())));
        final var popup = ShellPanelPlacement.atPointer(100, 100, 200, 100, 8)
                .ownedBy(owner.surface("start"));
        assertEquals(new ShellBounds(108, 108, 308, 208), place(layout, popup));
        final var top = layout.bind();
        top.commit(List.of(new ShellSurface("bar", true, ShellSurface.Layer.TOP, ShellSurface.Keyboard.NONE,
                new ShellSurface.Placement(ShellSurface.Reference.AVAILABLE,
                        ShellSurface.LEFT | ShellSurface.RIGHT | ShellSurface.TOP,
                        0, 40, ShellSurface.Margins.NONE), ShellSurface.Margins.NONE,
                ShellSurface.Input.CONTENT,
                List.of(ShellReservation.exclusive(ShellReservation.Edge.TOP, 40, true)))));
        assertEquals(new ShellBounds(108, 148, 308, 248), place(layout, popup));
        owner.close();
        assertThrows(IllegalStateException.class, () -> popup.resolve(layout.snapshot()));
    }

    private static DesktopShellLayout desktop() {
        final DesktopShellLayout layout = new DesktopShellLayout();
        layout.update(new DesktopViewport(0, 0, 1920, 1080, 0, 0, 0, 0), 64, true);
        return layout;
    }

    private static ShellBounds place(final DesktopShellLayout layout, final ShellPanelPlacement placement) {
        final var owner = layout.bind();
        owner.commit(List.of(new ShellSurface("panel", true, ShellSurface.Layer.OVERLAY,
                ShellSurface.Keyboard.ON_DEMAND, placement.resolve(layout.snapshot()),
                ShellSurface.Margins.NONE, ShellSurface.Input.CONTENT, List.of())));
        return owner.surface("panel").content();
    }
}

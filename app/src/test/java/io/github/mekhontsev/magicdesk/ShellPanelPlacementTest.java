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
        ShellLayoutTestSupport.update(layout, new DesktopViewport(100, 200, 1100, 900, 0, 0, 0, 0), 50, false);
        assertEquals(new ShellBounds(118, 218, 418, 418), place(layout,
                ShellPanelPlacement.atPointer(110, 210, 300, 200, 8)));
    }

    @Test public void tinyOutputRetainsNonemptyBoundedPopup() {
        final DesktopShellLayout layout = new DesktopShellLayout();
        ShellLayoutTestSupport.update(layout, new DesktopViewport(0, 0, 1, 1, 0, 0, 0, 0), 64, false);
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

    @Test public void taskbarPanelsFollowFloatingAndOverlayGeometry() {
        final var layout = new DesktopShellLayout();
        final var viewport = new DesktopViewport(0, 0, 1920, 1080, 0, 0, 0, 0);
        final var style = ShellAppearance.PanelStyle.floating();
        final var panel = ShellLayoutTestSupport.panel("main", ShellPanel.Edge.BOTTOM, style, ShellComposition.Kind.START);
        layout.update(viewport, List.of(PanelGeometry.resolve(panel, 1, 1920, 1080, 64, 900, 500)), false);
        final var start = new ShellPanelPlacement.BesideSurface(layout.taskbar().request().id(), panel.edge(), 560, 620, false, 16, 0);
        final var end = new ShellPanelPlacement.BesideSurface(layout.taskbar().request().id(), panel.edge(), 300, 200, true, 8, 8);
        assertEquals(new ShellBounds(526, 384, 1086, 1004), place(layout, start));
        assertEquals(new ShellBounds(1102, 796, 1402, 996), place(layout, end));
        final var overlay = new ShellAppearance.PanelStyle(style.length(), ShellAppearance.Alignment.END,
                style.maxLengthDp(), 12, 12, 0, 8, 8, ShellAppearance.Backdrop.defaults(), false, style.hover());
        layout.update(viewport, List.of(PanelGeometry.resolve(panel.withStyle(overlay), 1, 1920, 1080, 64, 900, 500)), false);
        assertEquals(1080, layout.snapshot().workArea().bottom());
        assertEquals(new ShellBounds(1024, 384, 1584, 1004), place(layout, start));
        assertEquals(new ShellBounds(1600, 796, 1900, 996), place(layout, end));
    }

    @Test public void componentPopupsOpenInwardFromEveryEdgeWithStartAndEndAlignment() {
        final var viewport = new DesktopViewport(0, 0, 1000, 800, 0, 0, 0, 0);
        for (ShellPanel.Edge edge : ShellPanel.Edge.values()) {
            final var layout = new DesktopShellLayout();
            final var panel = ShellLayoutTestSupport.panel("main", edge, ShellAppearance.PanelStyle.defaults(), ShellComposition.Kind.START);
            layout.update(viewport, List.of(ShellLayoutTestSupport.geometry(panel, viewport, 40)), true);
            final var start = new ShellPanelPlacement.BesideSurface(layout.taskbar().request().id(), edge, 200, 100, false, 10, 8);
            final var end = new ShellPanelPlacement.BesideSurface(layout.taskbar().request().id(), edge, 200, 100, true, 10, 8);
            final var expectedStart = switch (edge) {
                case TOP -> new ShellBounds(10, 48, 210, 148);
                case BOTTOM -> new ShellBounds(10, 652, 210, 752);
                case LEFT -> new ShellBounds(48, 10, 248, 110);
                case RIGHT -> new ShellBounds(752, 10, 952, 110);
            };
            final var expectedEnd = switch (edge) {
                case TOP -> new ShellBounds(790, 48, 990, 148);
                case BOTTOM -> new ShellBounds(790, 652, 990, 752);
                case LEFT -> new ShellBounds(48, 690, 248, 790);
                case RIGHT -> new ShellBounds(752, 690, 952, 790);
            };
            assertEquals(expectedStart, place(layout, start));
            assertEquals(expectedEnd, place(layout, end));
        }
    }

    @Test public void popupsFollowTheirComponentPanelAndClampPastOtherNativeReservations() {
        final var viewport = new DesktopViewport(0, 0, 1000, 800, 0, 0, 0, 0);
        final var layout = new DesktopShellLayout();
        final var style = ShellAppearance.PanelStyle.defaults();
        layout.update(viewport, List.of(
                ShellLayoutTestSupport.geometry(ShellLayoutTestSupport.panel("top", ShellPanel.Edge.TOP, style,
                        ShellComposition.Kind.CLOCK), viewport, 40),
                ShellLayoutTestSupport.geometry(ShellLayoutTestSupport.panel("left", ShellPanel.Edge.LEFT, style,
                        ShellComposition.Kind.START), viewport, 50)), false);
        final var owner = layout.panelFor(ShellComposition.Kind.START);
        assertEquals(new ShellBounds(58, 50, 258, 150), place(layout,
                new ShellPanelPlacement.BesideSurface(owner.request().id(), ShellPanel.Edge.LEFT, 200, 100, false, 10, 8)));
        final var clock = layout.panelFor(ShellComposition.Kind.CLOCK);
        assertEquals(new ShellBounds(50, 48, 250, 148), place(layout,
                new ShellPanelPlacement.BesideSurface(clock.request().id(), ShellPanel.Edge.TOP, 200, 100, false, 0, 8)));
    }

    @Test public void besideSurfaceClampsTinyViewportsAndRejectsRemovedOwners() {
        for (ShellPanel.Edge edge : ShellPanel.Edge.values()) {
            final var viewport = new DesktopViewport(10, 20, 11, 21, 0, 0, 0, 0);
            final var layout = new DesktopShellLayout();
            final var panel = ShellLayoutTestSupport.panel("main", edge, ShellAppearance.PanelStyle.floating(), ShellComposition.Kind.START);
            layout.update(viewport, List.of(ShellLayoutTestSupport.geometry(panel, viewport, 64)), false);
            final var popup = new ShellPanelPlacement.BesideSurface(layout.taskbar().request().id(), edge,
                    Integer.MAX_VALUE, Integer.MAX_VALUE, true, Integer.MAX_VALUE, Integer.MAX_VALUE);
            assertEquals(viewport.contentGeometry(), place(layout, popup));
            assertTrue(popup.hasMappedOwner(layout.snapshot()));
            layout.update(viewport, List.of(), false);
            assertFalse(popup.hasMappedOwner(layout.snapshot()));
            assertThrows(IllegalStateException.class, () -> popup.resolve(layout.snapshot()));
        }
    }

    @Test public void retainedPopupFollowsItsPanelWhenThePanelChangesEdge() {
        final var viewport = new DesktopViewport(0, 0, 1000, 800, 0, 0, 0, 0);
        final var layout = new DesktopShellLayout();
        final var bottom = ShellLayoutTestSupport.panel("main", ShellPanel.Edge.BOTTOM,
                ShellAppearance.PanelStyle.defaults(), ShellComposition.Kind.START);
        layout.update(viewport, List.of(ShellLayoutTestSupport.geometry(bottom, viewport, 40)), false);
        final var popup = new ShellPanelPlacement.BesideSurface(layout.taskbar().request().id(), ShellPanel.Edge.BOTTOM,
                200, 100, false, 10, 8);
        assertEquals(new ShellBounds(10, 652, 210, 752), place(layout, popup));
        final var left = new ShellPanel(bottom.id(), ShellPanel.Edge.LEFT, bottom.style(), bottom.components());
        layout.update(viewport, List.of(ShellLayoutTestSupport.geometry(left, viewport, 40)), false);
        assertEquals(new ShellBounds(48, 10, 248, 110), place(layout, popup));
    }

    private static DesktopShellLayout desktop() {
        final DesktopShellLayout layout = new DesktopShellLayout();
        ShellLayoutTestSupport.update(layout, new DesktopViewport(0, 0, 1920, 1080, 0, 0, 0, 0), 64, true);
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

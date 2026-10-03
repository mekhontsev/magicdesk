package io.github.mekhontsev.magicdesk;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;
import static io.github.mekhontsev.magicdesk.ShellLayoutTestSupport.panel;

public final class PanelGeometryTest {
    @Test public void allEdgesResolveTheLongAxisAndFloatingGaps() {
        for (ShellPanel.Edge edge : ShellPanel.Edge.values()) {
            final var definition = panel("main", edge, ShellAppearance.PanelStyle.floating(), ShellComposition.Kind.TASKS);
            final var geometry = PanelGeometry.resolve(definition, 1, 1920, 1080, 64, 900, 500);
            final var layout = new DesktopShellLayout();
            layout.update(new DesktopViewport(0, 0, 1920, 1080, 0, 0, 0, 0), List.of(geometry), false);
            final var expected = switch (edge) {
                case TOP -> new ShellBounds(510, 12, 1410, 76);
                case BOTTOM -> new ShellBounds(510, 1004, 1410, 1068);
                case LEFT -> new ShellBounds(12, 90, 76, 990);
                case RIGHT -> new ShellBounds(1844, 90, 1908, 990);
            };
            assertEquals(expected, layout.taskbar().content());
            assertEquals(expected, layout.taskbar().paint());
            assertEquals(expected, layout.taskbar().input());
            assertEquals(64, geometry.thickness());
        }
    }

    @Test public void overlayChangesWindowReservationNotPanelGeometry() {
        final var style = ShellAppearance.PanelStyle.floating();
        final var viewport = new DesktopViewport(0, 0, 1920, 1080, 0, 0, 0, 0);
        final var layout = new DesktopShellLayout();
        final var definition = panel("main", ShellPanel.Edge.BOTTOM, style, ShellComposition.Kind.TASKS);
        layout.update(viewport, List.of(PanelGeometry.resolve(definition, 1, 1920, 1080, 64, 900, 500)), false);
        final var before = layout.snapshot();
        final var bounds = layout.taskbar().content();
        final var overlay = new ShellAppearance.PanelStyle(style.length(), style.alignment(), style.maxLengthDp(),
                style.sideGapDp(), style.edgeGapDp(), style.thicknessDp(), style.paddingDp(), style.radiusDp(), style.backdrop(), false, style.hover());
        layout.update(viewport, List.of(PanelGeometry.resolve(definition.withStyle(overlay), 1, 1920, 1080, 64, 900, 500)), false);
        assertEquals(viewport.contentGeometry(), layout.snapshot().workArea());
        assertEquals(before.panelArea(), layout.snapshot().panelArea());
        assertEquals(bounds, layout.taskbar().content());
    }

    @Test public void maximumLengthAndAccessibleMinimumAreBoundedByViewportOnBothAxes() {
        for (ShellPanel.Edge edge : ShellPanel.Edge.values()) {
            final var definition = panel("main", edge, ShellAppearance.PanelStyle.floating(), ShellComposition.Kind.TASKS);
            final var normal = PanelGeometry.resolve(definition, 1, 1920, 1920, 64, 2500, 600);
            assertEquals(1100, edge.vertical() ? normal.height() : normal.width());
            final var narrow = PanelGeometry.resolve(definition, 1, 374, 374, 64, 900, 500);
            assertEquals(374, edge.vertical() ? narrow.height() : narrow.width());
            final var dense = PanelGeometry.resolve(definition, 2, 3840, 3840, 128, 1800, 1000);
            assertEquals(1800, edge.vertical() ? dense.height() : dense.width());
        }
    }

    @Test public void explicitThicknessIsDensityResolvedAndAutoUsesRuntimeMeasurement() {
        final var auto = panel("main", ShellPanel.Edge.LEFT, ShellAppearance.PanelStyle.defaults(), ShellComposition.Kind.TASKS);
        final var fixed = auto.withStyle(new ShellAppearance.PanelStyle(ShellAppearance.Width.FILL,
                ShellAppearance.Alignment.CENTER, 4096, 0, 0, 40, 8, 0, null, true, ShellDockEffect.NONE));
        assertEquals(123, PanelGeometry.resolve(auto, 2, 1920, 1080, 123, 900, 500).width());
        assertEquals(80, PanelGeometry.resolve(fixed, 2, 1920, 1080, 123, 900, 500).width());
        assertEquals(1, PanelGeometry.resolve(fixed, 2, 1, 1, 123, 900, 500).width());
    }

    @Test public void contentPanelCanStartBeforeItsViewsHaveBeenMeasured() {
        final var definition = panel("main", ShellPanel.Edge.BOTTOM, ShellAppearance.PanelStyle.floating(), ShellComposition.Kind.TASKS);
        final var viewport = new DesktopViewport(0, 0, 1920, 1080, 0, 0, 0, 0);
        final var layout = new DesktopShellLayout();
        layout.update(viewport, List.of(PanelGeometry.resolve(definition, 1, 1920, 1080, 64, 0, 0)), false);
        assertTrue(layout.taskbar().content().width() > 0);
        layout.update(viewport, List.of(PanelGeometry.resolve(definition, 1, 1920, 1080, 64, 900, 500)), false);
        assertEquals(900, layout.taskbar().content().width());
        assertEquals(1004, layout.snapshot().workArea().bottom());
    }

    @Test public void phonePanelExtendsPaintOnlyWhenFlushToItsEdge() {
        final var viewport = new DesktopViewport(0, 0, 1080, 2400, 0, 80, 0, 120);
        final var layout = new DesktopShellLayout();
        final var definition = panel("main", ShellPanel.Edge.BOTTOM, ShellAppearance.PanelStyle.defaults(), ShellComposition.Kind.TASKS);
        layout.update(viewport, List.of(PanelGeometry.resolve(definition, 3, 1080, 2200, 192, 900, 500)), false);
        assertEquals(2400, layout.taskbar().paint().bottom());
        assertEquals(2280, layout.taskbar().content().bottom());
        layout.update(viewport, List.of(PanelGeometry.resolve(definition.withStyle(ShellAppearance.PanelStyle.floating()),
                3, 1080, 2200, 192, 900, 500)), false);
        assertEquals(layout.taskbar().content(), layout.taskbar().paint());
        assertEquals(2244, layout.taskbar().paint().bottom());
    }

    @Test public void revealUsesEachPhysicalEdgeAndOnlyThePanelsLongAxisSpan() {
        final var output = new ShellBounds(100, 200, 1100, 900);
        final var surface = new ShellBounds(300, 400, 700, 800);
        for (ShellPanel.Edge edge : ShellPanel.Edge.values()) {
            final var expected = switch (edge) {
                case TOP -> new ShellBounds(300, 200, 700, 204);
                case BOTTOM -> new ShellBounds(300, 896, 700, 900);
                case LEFT -> new ShellBounds(100, 400, 104, 800);
                case RIGHT -> new ShellBounds(1096, 400, 1100, 800);
            };
            assertEquals(expected, PanelGeometry.presented(output, surface, edge, true, true, 4));
            assertSame(surface, PanelGeometry.presented(output, surface, edge, true, false, 4));
            assertTrue(PanelGeometry.presented(output, surface, edge, false, true, 4).isEmpty());
        }
        assertEquals(0, PanelGeometry.paintAlpha(true, true));
        assertEquals(0, PanelGeometry.paintAlpha(false, false));
        assertEquals(255, PanelGeometry.paintAlpha(true, false));
    }

    @Test public void tinyRevealRemainsInsideOutputForEveryEdge() {
        final var output = new ShellBounds(10, 20, 11, 21);
        for (ShellPanel.Edge edge : ShellPanel.Edge.values()) {
            assertEquals(output, PanelGeometry.reveal(output, output, edge, Integer.MAX_VALUE));
        }
    }
}

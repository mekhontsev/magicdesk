package io.github.mekhontsev.magicdesk;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ShellThemesTest {
    static ShellAppearance load(String id) throws Exception {
        return ShellThemes.load(id, path -> Files.newInputStream(Path.of("src/main/assets", path)));
    }

    @Test public void bundledDocumentsUseTheProductionSchemaAndRoundTripInEitherScope() throws Exception {
        var ids = new HashSet<String>();
        var files = new HashSet<String>();
        for (var entry : ShellThemes.ENTRIES) {
            assertTrue(ids.add(entry.id())); files.add(entry.id() + ".json");
            var theme = load(entry.id());
            var json = ShellAppearanceJson.encode(theme);
            ShellAppearanceSchema.validate(json);
            assertEquals(theme, ShellAppearanceJson.parse(json.toString()));
            assertEquals(theme, WorkspaceAppearancePatch.parse(json.toString()).resolve(ShellAppearance.preset("contrast")));
            assertFalse(theme.resources().hasBundleAssets());
            assertEquals(UiColor.TRANSPARENT, theme.feedback().normal());
            assertNotNull(theme.composition().panelFor(ShellComposition.Kind.START));
            assertTrue(theme.composition().start().sections().containsAll(List.of(ShellComposition.Section.values())));
        }
        try (var paths = Files.list(Path.of("src/main/assets/themes"))) {
            assertEquals(files, paths.map(path -> path.getFileName().toString()).collect(java.util.stream.Collectors.toSet()));
        }
        assertEquals(java.util.Set.of("workbench", "material", "cupertino", "glass-dock", "two-panels", "contours"), ids);
    }

    @Test public void invalidNamesNeverOpenPathsAndMalformedDocumentsCloseTheirInput() {
        for (String id : new String[] {"../workbench", "", "dark", "WORKBENCH"}) {
            assertThrows(IllegalArgumentException.class, () -> ShellThemes.load(id, path -> { fail("unexpected file access"); return null; }));
        }
        boolean[] closed = {false};
        assertThrows(Exception.class, () -> ShellThemes.load("workbench", path -> new ByteArrayInputStream(new byte[] {'{'}) {
            @Override public void close() { closed[0] = true; }
        }));
        assertTrue(closed[0]);
    }

    @Test public void primaryTextRemainsLegibleAndThemesHaveDistinctLayouts() throws Exception {
        for (var entry : ShellThemes.ENTRIES) {
            for (var mode : List.of(ShellAppearance.ColorMode.LIGHT, ShellAppearance.ColorMode.DARK)) {
                var palette = load(entry.id()).palette().withMode(mode);
                for (UiColor background : List.of(UiColor.BACKGROUND, UiColor.PANEL, UiColor.SURFACE, UiColor.HOVER)) {
                    assertTrue(entry.id() + "/" + mode + "/" + background, contrast(palette.color(UiColor.TEXT), palette.color(background)) >= 4.5);
                }
                assertTrue(entry.id() + "/" + mode, contrast(palette.color(UiColor.MUTED), palette.color(UiColor.PANEL)) >= 4.5);
            }
        }
        var workbench = load("workbench");
        assertEquals(ShellComposition.Presentation.LIST, workbench.composition().start().presentation());
        assertEquals(ShellAppearance.Width.FILL, workbench.composition().panels().get(0).style().length());
        var dock = load("glass-dock");
        assertTrue(dock.backdrop().opacity() < 1 && dock.backdrop().blurRadiusDp() > 0);
        assertTrue(dock.composition().panels().get(0).style().edgeGapDp() > 0);
        assertEquals(List.of(ShellPanel.Edge.TOP, ShellPanel.Edge.BOTTOM),
                load("two-panels").composition().panels().stream().map(ShellPanel::edge).toList());
    }

    @Test public void materialUsesSemanticSystemColorsAndACenteredLauncher() throws Exception {
        var theme = load("material");
        assertEquals(ShellAppearance.ColorSource.SYSTEM, theme.palette().source());
        assertEquals(ShellAppearance.ColorMode.SYSTEM, theme.palette().mode());
        assertEquals(1, theme.composition().panels().size());
        var bar = theme.composition().panels().get(0);
        assertEquals(ShellAppearance.Width.FILL, bar.style().length());
        assertEquals(ShellPanel.Edge.BOTTOM, bar.edge());
        assertEquals(56, bar.style().thicknessDp());
        assertEquals(ShellComposition.Group.CENTER, bar.components().stream()
                .filter(item -> item.type() == ShellComposition.Kind.START).findFirst().orElseThrow().group());
        var button = theme.controls().style(ShellControls.Role.ACTION_BUTTON);
        assertEquals(ShellControls.Shape.CAPSULE, button.shape());
        assertEquals(UiColor.ACCENT, button.normal().fill());
        assertEquals(UiColor.ON_ACCENT, button.normal().content());
        var system = new java.util.EnumMap<UiColor, Integer>(UiColor.class);
        system.putAll(ShellAppearance.preset("light").palette().colors());
        system.put(UiColor.ACCENT, 0xff006633);
        assertEquals(0xff006633, theme.palette().resolve(false, system).color(UiColor.ACCENT));
    }

    @Test public void cupertinoSeparatesTheStatusBarFromTheMagnifyingDock() throws Exception {
        var theme = load("cupertino");
        assertEquals(ShellAppearance.ColorMode.SYSTEM, theme.palette().mode());
        assertEquals(2, theme.composition().panels().size());
        var top = theme.composition().panelFor(ShellComposition.Kind.START);
        assertEquals(ShellPanel.Edge.TOP, top.edge());
        assertEquals(ShellAppearance.Width.FILL, top.style().length());
        assertEquals(40, top.style().thicknessDp());
        assertTrue(top.style().backdrop().blurRadiusDp() > 0);
        var dock = theme.composition().panelFor(ShellComposition.Kind.TASKS);
        assertEquals(ShellPanel.Edge.BOTTOM, dock.edge());
        assertEquals(ShellAppearance.Width.CONTENT, dock.style().length());
        assertTrue(dock.style().edgeGapDp() > 0);
        assertTrue(dock.style().hover().scale() > 1);
        assertTrue(dock.style().backdrop().opacity() < 1);
        assertEquals(0, dock.style().backdrop().blurRadiusDp(), 0);
        assertEquals(ShellComposition.Indicator.DOT, dock.components().stream()
                .filter(item -> item.type() == ShellComposition.Kind.TASKS).findFirst().orElseThrow().indicator());
        assertEquals(ShellComposition.Navigation.PAGES, theme.composition().start().navigation());
    }

    @Test public void themedIconsHaveNoPermanentBackplatesAndFilledControlsHaveContrast() throws Exception {
        for (String id : List.of("material", "cupertino")) {
            var theme = load(id);
            for (var role : List.of(ShellControls.Role.PANEL_BUTTON, ShellControls.Role.APP_TILE)) {
                assertEquals(UiColor.TRANSPARENT, theme.controls().style(role).normal().fill());
            }
            for (var mode : List.of(ShellAppearance.ColorMode.LIGHT, ShellAppearance.ColorMode.DARK)) {
                var palette = theme.palette().withMode(mode);
                for (var role : ShellControls.Role.values()) {
                    var style = theme.controls().style(role);
                    var paints = new ArrayList<ShellControls.Paint>();
                    paints.add(style.normal()); paints.addAll(style.states().values());
                    for (var paint : paints) {
                        if (paint.fill() == UiColor.TRANSPARENT || role == ShellControls.Role.SWITCH) continue;
                        assertTrue(id + "/" + mode + "/" + role,
                                contrast(paint.contentColor(palette), paint.background(palette)) >= 4.5);
                    }
                }
            }
        }
    }

    @Test public void panelsAndStartFitPhoneTabletAndDesktopAtMultipleDensities() throws Exception {
        for (var entry : ShellThemes.ENTRIES) for (int[] size : new int[][] {{360, 800}, {800, 360}, {960, 540}, {1600, 900}}) {
            for (float density : new float[] {1, 1.5f, 3.25f}) {
                var theme = load(entry.id());
                int width = Math.round(size[0] * density), height = Math.round(size[1] * density);
                var viewport = new DesktopViewport(0, 0, width, height, 0, 0, 0, 0);
                var panels = new ArrayList<PanelGeometry>();
                for (var panel : theme.composition().panels()) {
                    int minimum = 2 * panel.style().paddingDp();
                    for (var item : panel.components()) if (item.visible(false, true, size[0])) {
                        minimum += item.widthDp() > 0 ? item.widthDp() : item.type() == ShellComposition.Kind.SPACER ? 0 : 64;
                    }
                    panels.add(PanelGeometry.resolve(panel, density, width, height, Math.round(64 * density),
                            Math.round((minimum + 5 * 48) * density), Math.round(minimum * density)));
                }
                var layout = new DesktopShellLayout();
                layout.update(viewport, panels, false);
                var output = viewport.contentGeometry();
                var work = layout.snapshot().workArea();
                assertEquals(work, work.intersect(output));
                assertTrue(work.height() > height / 2);
                var start = theme.composition().panelFor(ShellComposition.Kind.START);
                var owner = layout.panelFor(ShellComposition.Kind.START);
                var placement = new ShellPanelPlacement.BesideSurface(owner.request().id(), start.edge(),
                        Math.round(560 * density), Math.round(620 * density), false, 16, 0);
                var binding = layout.bind();
                binding.commit(List.of(new ShellSurface("start", true, ShellSurface.Layer.OVERLAY, ShellSurface.Keyboard.ON_DEMAND,
                        placement.resolve(layout.snapshot()), ShellSurface.Margins.NONE, ShellSurface.Input.CONTENT, List.of())));
                var bounds = binding.surface("start").content();
                assertEquals(entry.id(), bounds, bounds.intersect(work));
                binding.close();
            }
        }
    }

    private static double contrast(int first, int second) {
        double a = luminance(first), b = luminance(second);
        return (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
    }
    private static double luminance(int color) {
        double value = 0;
        double[] weights = {.0722, .7152, .2126};
        for (int index = 0; index < 3; index++) {
            double channel = ((color >>> (index * 8)) & 255) / 255.0;
            value += weights[index] * (channel <= .04045 ? channel / 12.92 : Math.pow((channel + .055) / 1.055, 2.4));
        }
        return value;
    }
}

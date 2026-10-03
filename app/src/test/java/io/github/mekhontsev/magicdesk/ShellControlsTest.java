package io.github.mekhontsev.magicdesk;

import java.util.EnumMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ShellControlsTest {
    @Test public void semanticStylesRoundTripAndAreImmutable() throws Exception {
        var theme = ShellAppearanceJson.parse("""
                {"version":6,"controls":{
                  "action_button":{"shape":"capsule","paddingHorizontalDp":18,"minHeightDp":48,
                    "textWeight":600,"normal":{"fill":"accent","content":"on_accent"},
                    "states":{"hover":{"fill":"accent","content":"on_accent","layer":"on_accent","layerOpacity":0.08}}},
                  "panel_button":{"radiusDp":0,"borderDp":2},
                  "search_field":{"textSizeSp":16},"tab":{},"switch":{},"settings_row":{},"app_tile":{}}}
                """);
        assertEquals(theme, ShellAppearanceJson.parse(ShellAppearanceJson.encode(theme).toString()));
        assertEquals(ShellControls.Shape.CAPSULE, theme.controls().style(ShellControls.Role.ACTION_BUTTON).shape());
        assertEquals(7, theme.controls().styles().size());
        assertNull(theme.controls().style(ShellControls.Role.APP_TILE).paddingHorizontalDp());
        assertThrows(UnsupportedOperationException.class, () -> theme.controls().styles().clear());
        assertThrows(UnsupportedOperationException.class, () -> theme.controls().style(ShellControls.Role.ACTION_BUTTON).states().clear());
        assertEquals(theme.controls(), theme.withPalette(ShellAppearance.preset("light").palette()).controls());
        assertEquals(ShellControls.defaults(), theme.withStyle(ShellAppearance.defaults()).controls());
    }
    @Test public void systemColorsAreNotCapturedByEditingOrExport() throws Exception {
        var theme = ShellAppearanceJson.parse("""
                {"palette":{"source":"system","mode":"light"},"colors":{"danger":"#AB1234"}}
                """);
        var android = new EnumMap<UiColor, Integer>(UiColor.class);
        android.putAll(ShellAppearance.preset("contrast").palette().colors());
        android.put(UiColor.ACCENT, 0xffaabbcc);
        var resolved = theme.withPalette(theme.palette().resolve(false, android));
        assertEquals(0xffaabbcc, resolved.palette().color(UiColor.ACCENT));
        assertEquals(0xffab1234, resolved.palette().color(UiColor.DANGER));
        assertEquals(ShellAppearanceJson.encode(theme).toString(), ShellAppearanceJson.encode(resolved).toString());
        assertEquals(theme, ShellAppearanceJson.parse(ShellAppearanceJson.encode(resolved).toString()));
        var changed = resolved.palette().withColor(UiColor.TEXT, 0xff112233);
        assertEquals(1, changed.overrides().size());
        assertEquals(Map.of(UiColor.TEXT, 0xff112233), changed.overrides(false));
        assertFalse(changed.overrides().containsKey(UiColor.ACCENT));
        assertEquals(ShellAppearance.ColorSource.SYSTEM, changed.source());
        assertSame(ShellAppearance.defaults().palette().source(), ShellAppearance.ColorSource.FIXED);
        var fixed = ShellAppearance.defaults().palette();
        assertSame(fixed, fixed.resolve(false, android));
    }
    @Test public void stateLayersUseSourceOverAndPreserveTransparency() {
        var palette = ShellAppearance.defaults().palette().withColor(UiColor.TEXT, 0xffffffff).withColor(UiColor.SURFACE, 0xff000000);
        assertEquals(0x80ffffff, new ShellControls.Paint(UiColor.TRANSPARENT, UiColor.TEXT, UiColor.TRANSPARENT,
                UiColor.TEXT, .5f, 1).background(palette));
        assertEquals(0xff808080, new ShellControls.Paint(UiColor.SURFACE, UiColor.TEXT, UiColor.TRANSPARENT,
                UiColor.TEXT, .5f, 1).background(palette));
        assertEquals(0x40ffffff, new ShellControls.Paint(UiColor.TRANSPARENT, UiColor.TEXT, UiColor.TRANSPARENT,
                UiColor.TEXT, .5f, .5f).background(palette));
        assertEquals(0, new ShellControls.Paint(UiColor.TRANSPARENT, UiColor.TEXT, UiColor.TRANSPARENT,
                UiColor.TEXT, 0, 1).background(palette));
    }
    @Test public void invalidStylesReportTheirSemanticPath() {
        for (String[] invalid : new String[][] {
                {"\"unknown\":{}", "/controls/unknown"},
                {"\"tab\":{\"textSizeSp\":80}", "/controls/tab/textSizeSp"},
                {"\"app_tile\":{\"paddingVerticalDp\":-1}", "/controls/app_tile/paddingVerticalDp"},
                {"\"switch\":{\"shape\":\"script\"}", "/controls/switch/shape"},
                {"\"action_button\":{\"states\":{\"pressed\":{\"layerOpacity\":2}}}", "/controls/action_button/states/pressed/layerOpacity"}}) {
            var error = assertThrows(IllegalArgumentException.class, () -> ShellAppearanceJson.parse("{\"controls\":{" + invalid[0] + "}}"));
            assertTrue(error.getMessage(), error.getMessage().startsWith(invalid[1] + ":"));
        }
        assertThrows(IllegalArgumentException.class, () -> new ShellControls.Paint(UiColor.TEXT, UiColor.TEXT, UiColor.TEXT, UiColor.TEXT, Float.NaN, 1));
    }
    @Test public void workspaceStylesAndDynamicPalettesSurvivePreviewCancellation() throws Exception {
        var global = ShellAppearanceJson.parse("""
                {"palette":{"source":"system"},"controls":{"tab":{"radiusDp":12,"textSizeSp":14}}}
                """);
        var store = WorkspaceAppearance.defaults().apply(global).apply("work", "{\"controls\":{\"tab\":{\"textSizeSp\":16}}}");
        assertEquals(12f, store.current("work").controls().style(ShellControls.Role.TAB).radiusDp(), 0);
        assertEquals(16f, store.current("work").controls().style(ShellControls.Role.TAB).textSizeSp(), 0);
        var preview = store.preview("work", "{\"palette\":{\"mode\":\"light\"},\"controls\":{\"tab\":{\"radiusDp\":0}}}");
        assertEquals(store.savedGlobal(), preview.savedGlobal());
        assertEquals(ShellAppearance.ColorMode.LIGHT, preview.current("work").palette().mode());
        var canceled = preview.cancel("work", preview.snapshot("work").previewId());
        assertEquals(store.current("work"), canceled.current("work"));
        assertEquals(global, canceled.current());
    }
    @Test public void removingStylesAndColorOverridesDoesNotFreezeInheritedPalette() throws Exception {
        var global = ShellAppearanceJson.parse("{\"palette\":{\"source\":\"system\"},\"colors\":{\"accent\":\"#112233\"}}");
        var before = global.withControls(new ShellControls(Map.of(ShellControls.Role.TAB, ShellControls.Style.inherit())));
        var after = global.withPalette(new ShellAppearance.Palette(ShellAppearance.ColorSource.SYSTEM,
                ShellAppearance.ColorMode.SYSTEM, false, Map.of(), Map.of(), Map.of()));
        String patch = AppearanceSettings.changedPatch("{}", before, after);
        var resolved = WorkspaceAppearancePatch.parse(patch).resolve(before);
        assertEquals(after, resolved);
        assertEquals(ShellAppearance.defaults(), ShellAppearanceJson.parse("{\"colors\":{\"accent\":null}}"));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceAppearancePatch.parse("{\"controls\":{\"evil\":null}}"));
    }
    @Test public void completeThemeReplacementClearsForeignColorsAndControlMetrics() throws Exception {
        var global = ShellAppearanceJson.parse("""
                {"colors":{"accent":"#012345"},"controls":{"tab":{"radiusDp":40,"textSizeSp":25,
                  "states":{"hover":{"fill":"danger"}}},"app_tile":{"textWeight":800}}}
                """);
        for (var replacement : java.util.List.of(ShellAppearance.preset("light"), ShellAppearanceJson.parse("""
                {"palette":{"source":"system"},"controls":{"tab":{"radiusDp":2}}}
                """))) {
            var resolved = WorkspaceAppearancePatch.parse(ShellAppearanceJson.encode(replacement).toString()).resolve(global);
            assertEquals(replacement, resolved);
        }
    }
}

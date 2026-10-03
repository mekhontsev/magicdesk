package io.github.mekhontsev.magicdesk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public final class AppearanceModeTest {
    @Test public void fixedAndAndroidPalettesUseTheSameModePolicy() {
        for (var source : ShellAppearance.ColorSource.values()) {
            for (var mode : ShellAppearance.ColorMode.values()) {
                var palette = ShellAppearance.defaults().palette().withSource(source).withMode(mode);
                for (boolean systemNight : new boolean[] {false, true}) {
                    boolean night = mode == ShellAppearance.ColorMode.SYSTEM ? systemNight : mode == ShellAppearance.ColorMode.DARK;
                    var base = ShellAppearance.preset(night ? "dark" : "light").palette().colors();
                    var resolved = palette.resolve(systemNight, source == ShellAppearance.ColorSource.SYSTEM ? base : null);
                    assertEquals(night, resolved.night());
                    assertEquals(base.get(UiColor.TEXT).intValue(), resolved.color(UiColor.TEXT));
                    assertEquals(mode, resolved.mode());
                }
            }
        }
    }

    @Test public void variantsAndCommonOverridesRoundTripWithoutCapturingSystemColors() throws Exception {
        var definition = ShellAppearanceJson.parse("""
                {"palette":{"mode":"system","light":{"panel":"#EEEEEE"},"dark":{"panel":"#111111"}},
                 "colors":{"accent":"#123456"}}
                """);
        for (boolean night : new boolean[] {false, true}) {
            var resolved = definition.withPalette(definition.palette().resolve(night, null));
            assertEquals(night ? 0xff111111 : 0xffeeeeee, resolved.palette().color(UiColor.PANEL));
            assertEquals(0xff123456, resolved.palette().color(UiColor.ACCENT));
            assertEquals(definition, ShellAppearanceJson.parse(ShellAppearanceJson.encode(resolved).toString()));
        }
        var changed = definition.palette().resolve(false, null).withColor(UiColor.TEXT, 0xff223344);
        assertEquals(Map.of(UiColor.ACCENT, 0xff123456), changed.overrides());
        assertEquals(0xff223344, changed.overrides(false).get(UiColor.TEXT).intValue());
        assertFalse(changed.overrides(true).containsKey(UiColor.TEXT));
        assertEquals(definition.palette().overrides(true), changed.overrides(true));
        assertThrows(UnsupportedOperationException.class, () -> changed.overrides(false).clear());
    }

    @Test public void modeOnlyWorkspaceEditKeepsThemeAndOtherModesInherited() throws Exception {
        var global = ShellAppearanceJson.parse("""
                {"palette":{"light":{"accent":"#112233"},"dark":{"accent":"#DDEEFF"}},
                 "typography":{"font":"mono","scale":1.2},"backdrop":{"opacity":0.6,"blurRadiusDp":20}}
                """);
        var local = global.withPalette(global.palette().withMode(ShellAppearance.ColorMode.SYSTEM));
        var patch = AppearanceSettings.changedPatch("{}", global, local);
        var object = new org.json.JSONObject(patch);
        assertEquals(1, object.length());
        assertEquals("{\"mode\":\"system\"}", object.getJSONObject("palette").toString());
        var next = global.withPalette(global.palette().withColor(UiColor.TEXT, 0xff234567, false));
        var merged = WorkspaceAppearancePatch.parse(patch).resolve(next);
        assertEquals(next.palette().overrides(false), merged.palette().overrides(false));
        assertEquals(next.typography(), merged.typography());
        assertEquals(next.backdrop(), merged.backdrop());
        var store = WorkspaceAppearance.defaults().apply(global);
        var preview = store.preview("screen", patch);
        assertEquals(local, preview.current("screen"));
        assertEquals(global, preview.cancel("screen", preview.snapshot("screen").previewId()).current("screen"));
    }

    @Test public void fullReplacementClearsBothModesWithoutFreezingThePalette() throws Exception {
        var customized = ShellAppearanceJson.parse("""
                {"palette":{"light":{"text":"#123456"},"dark":{"text":"#FEDCBA"}}}
                """);
        var replacement = ShellAppearance.preset("light");
        var result = WorkspaceAppearancePatch.parse(ShellAppearanceJson.encode(replacement).toString()).resolve(customized);
        assertEquals(replacement, result);
        assertTrue(result.palette().overrides(false).isEmpty());
        assertTrue(result.palette().overrides(true).isEmpty());
    }

    @Test public void bundledThemesHaveUsableLightAndDarkVariantsWithoutLayoutChanges() throws Exception {
        for (String theme : new String[] {"glass-dock", "workbench", "two-panels", "contours"}) {
            var original = ShellAppearanceJson.parse(Files.readString(Path.of("src/main/assets/themes/" + theme + ".json")));
            for (var mode : new ShellAppearance.ColorMode[] {ShellAppearance.ColorMode.LIGHT, ShellAppearance.ColorMode.DARK}) {
                var changed = original.withPalette(original.palette().withMode(mode));
                int background = changed.palette().color(UiColor.PANEL), foreground = changed.palette().color(UiColor.TEXT);
                double a = luminance(background), b = luminance(foreground);
                assertTrue(theme + " " + mode, (Math.max(a, b) + .05) / (Math.min(a, b) + .05) >= 7);
                assertEquals(mode == ShellAppearance.ColorMode.LIGHT, a > b);
                assertEquals(original.withPalette(changed.palette()), changed);
                assertEquals(changed, ShellAppearanceJson.parse(ShellAppearanceJson.encode(changed).toString()));
            }
        }
    }

    @Test public void contrastAndModeAreIndependentAndInvalidVariantsFailAtTheirPath() throws Exception {
        var contrast = ShellAppearance.preset("contrast").palette();
        assertEquals(0xff000000, contrast.color(UiColor.PANEL));
        assertEquals(0xffffffff, contrast.withMode(ShellAppearance.ColorMode.LIGHT).color(UiColor.PANEL));
        assertEquals(0xff000000, contrast.withMode(ShellAppearance.ColorMode.LIGHT).color(UiColor.TEXT));
        for (String json : new String[] {"{\"palette\":{\"preset\":\"dark\"}}", "{\"version\":5}",
                "{\"palette\":{\"light\":{\"unknown\":\"#112233\"}}}",
                "{\"palette\":{\"dark\":{\"text\":\"#AABBCCDD\"}}}"}) {
            assertThrows(IllegalArgumentException.class, () -> ShellAppearanceJson.parse(json));
        }
    }

    @Test public void uiHasOneShellModeControlAndNoPresetResetButtons() throws Exception {
        String page = RuntimeSourceFixture.methods("AppearanceSettings", "createPage");
        assertTrue(page.contains("R.string.appearance_mode"));
        assertFalse(page.contains("withStyle("));
        assertFalse(page.contains("paletteMode.setEnabled"));
        assertTrue(page.contains("R.string.appearance_shell"));
        assertTrue(RuntimeSourceFixture.methods("AppearanceSettings", "systemThemeControls").contains("R.string.appearance_android"));
        assertFalse(RuntimeSourceFixture.methods("AppearanceSettings", "systemThemeControls").contains("apply("));
        String resolve = RuntimeSourceFixture.methods("SystemAppearancePalette", "resolveTheme");
        assertTrue(resolve.contains("definition.palette().source() == ShellAppearance.ColorSource.SYSTEM"));
        assertTrue(resolve.contains("definition.palette().resolve(systemNight, base)"));
    }

    private static double luminance(int color) {
        return .2126 * channel(color >> 16) + .7152 * channel(color >> 8) + .0722 * channel(color);
    }
    private static double channel(int value) {
        double c = (value & 255) / 255.0;
        return c <= .04045 ? c / 12.92 : Math.pow((c + .055) / 1.055, 2.4);
    }
}

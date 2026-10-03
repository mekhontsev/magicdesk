package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public final class ShellAppearanceTest {
    @Test public void defaultsAndPresetsRoundTripWithoutAndroid() throws Exception {
        assertEquals(ShellAppearance.defaults(), ShellAppearanceJson.parse("{}"));
        for (String preset : new String[] {"dark", "light", "contrast"}) {
            final var value = floating(ShellAppearance.preset(preset));
            assertEquals(value, ShellAppearanceJson.parse(ShellAppearanceJson.encode(value).toString()));
        }
    }
    @Test public void partialDocumentInheritsDefaultsAndNeverMergesPreviousState() throws Exception {
        var value = ShellAppearanceJson.parse("""
                {"colors":{"accent":"#123456"},"composition":{"panels":[
                  {"id":"main","style":{"edgeGapDp":12},"components":[{"type":"start"}]}]}}
                """);
        assertEquals(0xff123456, value.palette().color(UiColor.ACCENT));
        assertEquals(ShellAppearance.defaults().typography(), value.typography());
        assertEquals(12, value.composition().panels().get(0).style().edgeGapDp());
        assertEquals(ShellAppearance.Width.FILL, value.composition().panels().get(0).style().length());
        assertEquals(ShellPanel.Edge.BOTTOM, value.composition().panels().get(0).edge());
        assertEquals(ShellAppearance.defaults(), ShellAppearanceJson.parse("{}"));
    }
    @Test public void presetSelectionPreservesGeometryAndBackdropAndSnapshotIsImmutable() {
        var source = floating(ShellAppearance.defaults()).withBackdrop(new ShellAppearance.Backdrop(.6f, 24));
        var target = source.withStyle(ShellAppearance.preset("light"));
        assertEquals(source.composition(), target.composition());
        assertEquals(source.backdrop(), target.backdrop());
        assertEquals(source.panelBackdrop("main"), target.panelBackdrop("main"));
        assertNotEquals(source.palette(), target.palette());
        assertThrows(UnsupportedOperationException.class, () -> target.palette().colors().put(UiColor.TEXT, 0));
    }
    @Test public void backdropDefaultsAndModelBoundsAreValidated() {
        assertEquals(new ShellAppearance.Backdrop(1, 0), ShellAppearance.Backdrop.defaults());
        assertEquals(ShellAppearance.Backdrop.defaults(), ShellAppearance.defaults().backdrop());
        assertNull(ShellAppearance.PanelStyle.defaults().backdrop());
        assertEquals(.15f, new ShellAppearance.Backdrop(.15f, 64).opacity(), 0);
        assertEquals(64, new ShellAppearance.Backdrop(1, 64).blurRadiusDp());
        for (float opacity : new float[] {0, .149f, 1.001f, Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new ShellAppearance.Backdrop(opacity, 0));
        }
        for (int blur : new int[] {-1, 65, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> new ShellAppearance.Backdrop(1, blur));
        }
        assertThrows(NullPointerException.class, () -> ShellAppearance.defaults().withBackdrop(null));
    }

    @Test public void copyHelpersRetainTheGlobalBackdrop() {
        var source = ShellAppearance.defaults().withBackdrop(new ShellAppearance.Backdrop(.6f, 24));
        var light = ShellAppearance.preset("light");
        for (var changed : List.of(source.withPalette(light.palette()),
                source.withTypography(new ShellAppearance.Typography(ShellAppearance.Font.MONO, 1.2f)),
                source.withShape(new ShellAppearance.Shape(0, 2)),
                source.withComposition(floating(source).composition()),
                source.withResources(ShellResources.defaults()), source.withStyle(light))) {
            assertEquals(source.backdrop(), changed.backdrop());
        }
    }

    @Test public void panelBackdropsInheritOrOverrideWithoutFlatteningOnRoundTrip() throws Exception {
        var value = ShellAppearanceJson.parse("""
                {"version":6,"backdrop":{"opacity":0.6,"blurRadiusDp":24},"composition":{"panels":[
                  {"id":"inherited","components":[{"type":"start"}]},
                  {"id":"override","style":{"backdrop":{"opacity":0.15,"blurRadiusDp":64}},
                    "components":[{"type":"tasks"}]},
                  {"id":"opaque","style":{"backdrop":{}},"components":[{"type":"clock"}]}]}}
                """);
        assertNull(value.composition().panel("inherited").style().backdrop());
        assertEquals(new ShellAppearance.Backdrop(.6f, 24), value.backdrop());
        assertSame(value.backdrop(), value.panelBackdrop("inherited"));
        assertSame(value.backdrop(), value.panelBackdrop("missing"));
        assertSame(value.backdrop(), value.panelBackdrop(null));
        assertEquals(new ShellAppearance.Backdrop(.15f, 64), value.panelBackdrop("override"));
        assertEquals(ShellAppearance.Backdrop.defaults(), value.panelBackdrop("opaque"));

        var encoded = ShellAppearanceJson.encode(value);
        assertEquals(6, encoded.getInt("version"));
        assertEquals(.6, encoded.getJSONObject("backdrop").getDouble("opacity"), .000001);
        assertEquals(24, encoded.getJSONObject("backdrop").getInt("blurRadiusDp"));
        var panels = encoded.getJSONObject("composition").getJSONArray("panels");
        assertFalse(panels.getJSONObject(0).getJSONObject("style").has("backdrop"));
        assertTrue(panels.getJSONObject(1).getJSONObject("style").has("backdrop"));
        assertTrue(panels.getJSONObject(2).getJSONObject("style").has("backdrop"));
        for (int i = 0; i < panels.length(); i++) assertFalse(panels.getJSONObject(i).getJSONObject("style").has("opacity"));
        ShellAppearanceSchema.validate(encoded);
        var decoded = ShellAppearanceJson.parse(encoded.toString());
        assertEquals(value, decoded);
        var changed = decoded.withBackdrop(new ShellAppearance.Backdrop(.8f, 16));
        assertEquals(changed.backdrop(), changed.panelBackdrop("inherited"));
        assertEquals(value.panelBackdrop("override"), changed.panelBackdrop("override"));
        assertEquals(value.panelBackdrop("opaque"), changed.panelBackdrop("opaque"));
    }

    @Test public void partialBackdropObjectsUseOpaqueUnblurredDefaults() throws Exception {
        assertEquals(ShellAppearance.Backdrop.defaults(), ShellAppearanceJson.parse("{\"backdrop\":{}}").backdrop());
        assertEquals(new ShellAppearance.Backdrop(.5f, 0),
                ShellAppearanceJson.parse("{\"backdrop\":{\"opacity\":0.5}}").backdrop());
        assertEquals(new ShellAppearance.Backdrop(1, 32),
                ShellAppearanceJson.parse("{\"backdrop\":{\"blurRadiusDp\":32}}").backdrop());
        var value = ShellAppearanceJson.parse("""
                {"backdrop":{"opacity":0.6,"blurRadiusDp":24},"composition":{"panels":[
                  {"id":"opacity","style":{"backdrop":{"opacity":0.5}},"components":[{"type":"start"}]},
                  {"id":"blur","style":{"backdrop":{"blurRadiusDp":32}},"components":[{"type":"clock"}]}]}}
                """);
        assertEquals(new ShellAppearance.Backdrop(.5f, 0), value.panelBackdrop("opacity"));
        assertEquals(new ShellAppearance.Backdrop(1, 32), value.panelBackdrop("blur"));
    }

    @Test public void backdropJsonValidationIdentifiesRootAndPanelBoundaries() {
        for (String location : new String[] {"root", "panel"}) {
            for (String[] invalid : new String[][] {
                    {"null", ""}, {"[]", ""}, {"1", ""},
                    {"{\"opacity\":0.149}", "/opacity"}, {"{\"opacity\":1.001}", "/opacity"},
                    {"{\"opacity\":\"0.5\"}", "/opacity"}, {"{\"opacity\":null}", "/opacity"},
                    {"{\"blurRadiusDp\":-1}", "/blurRadiusDp"}, {"{\"blurRadiusDp\":65}", "/blurRadiusDp"},
                    {"{\"blurRadiusDp\":1.5}", "/blurRadiusDp"}, {"{\"blurRadiusDp\":4294967296}", "/blurRadiusDp"},
                    {"{\"blurRadiusDp\":\"24\"}", "/blurRadiusDp"}, {"{\"blurRadiusDp\":null}", "/blurRadiusDp"},
                    {"{\"blur\":12}", "/blur"}}) {
                String json = location.equals("root") ? "{\"backdrop\":" + invalid[0] + "}"
                        : "{\"composition\":{\"panels\":[{\"id\":\"main\",\"components\":[{\"type\":\"start\"}],"
                                + "\"style\":{\"backdrop\":" + invalid[0] + "}}]}}";
                String path = (location.equals("root") ? "" : "/composition/panels/0/style") + "/backdrop" + invalid[1];
                var error = assertThrows(IllegalArgumentException.class, () -> ShellAppearanceJson.parse(json));
                assertTrue(error.getMessage(), error.getMessage().startsWith(path + ":"));
            }
        }
    }

    @Test public void rejectsInvalidOrExecutableFieldsAtomically() {
        for (String invalid : new String[] {"{\"command\":\"id\"}", "{\"version\":1}",
                "{\"version\":3}",
                "{\"colors\":{\"text\":\"#00ffffff\"}}", "{\"colors\":{\"transparent\":\"#ffffff\"}}",
                "{\"colors\":{\"unknown\":\"#ffffff\"}}", "{\"typography\":{\"scale\":8}}",
                "{\"typography\":{\"font\":\"/sdcard/font.ttf\"}}", "{\"taskbar\":{\"opacity\":0}}",
                "{\"composition\":{\"taskbar\":[]}}", "{\"shape\":{\"borderDp\":null}}",
                "{\"preset\":null}", "{\"typography\":{\"font\":null}}",
                "{\"colors\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}",
                "{} trailing", " ".repeat(32769)}) {
            assertThrows(invalid, Exception.class, () -> ShellAppearanceJson.parse(invalid));
        }
    }

    @Test public void invalidPanelStylesAreRejectedAtTheirCurrentJsonBoundary() {
        for (String invalid : new String[] {"\"opacity\":0.5", "\"maxLengthDp\":100000000",
                "\"sideGapDp\":1.5", "\"reserveSpace\":\"true\"", "\"edgeGapDp\":-1",
                "\"thicknessDp\":1", "\"length\":\"wide\"", "\"bottomGapDp\":12", "\"width\":\"fill\""}) {
            final var json = "{\"composition\":{\"panels\":[{\"id\":\"main\",\"components\":[{\"type\":\"start\"}],\"style\":{" + invalid + "}}]}}";
            assertThrows(invalid, Exception.class, () -> ShellAppearanceJson.parse(json));
        }
    }

    @Test public void multiplePanelEdgesAndStylesRoundTripWithoutFlattening() throws Exception {
        final var panels = List.of(
                ShellLayoutTestSupport.panel("main", ShellPanel.Edge.LEFT, ShellAppearance.PanelStyle.floating(), ShellComposition.Kind.START),
                ShellLayoutTestSupport.panel("status", ShellPanel.Edge.TOP, ShellAppearance.PanelStyle.defaults(), ShellComposition.Kind.CLOCK));
        final var appearance = ShellAppearance.defaults().withComposition(new ShellComposition(panels, ShellComposition.Start.defaults()));
        assertEquals(appearance, ShellAppearanceJson.parse(ShellAppearanceJson.encode(appearance).toString()));
    }

    private static ShellAppearance floating(ShellAppearance appearance) {
        final var composition = appearance.composition();
        return appearance.withComposition(new ShellComposition(List.of(
                composition.panels().get(0).withStyle(ShellAppearance.PanelStyle.floating())), composition.start()));
    }
}

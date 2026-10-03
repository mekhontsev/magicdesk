package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public final class ShellDockEffectTest {
    @Test public void feedbackIsSymmetricBoundedAndContinuousAcrossStableSlots() {
        var effect = new ShellDockEffect(1.5f, 8, 1.5f);
        assertFalse(ShellDockEffect.NONE.enabled());
        assertTrue(effect.enabled());
        assertEquals(1, effect.influence(0, 48), .0001);
        assertEquals(.75, effect.influence(24, 48), .0001);
        assertEquals(.25, effect.influence(48, 48), .0001);
        assertEquals(0, effect.influence(72, 48), .0001);
        assertEquals(0, effect.influence(10000, 48), .0001);
        for (int i = 0; i < 100; i++) assertEquals(effect.influence(i, 48), effect.influence(-i, 48), 0);
        assertEquals(20, effect.overflow(48, 1));
        assertEquals(40, effect.overflow(96, 2));
        assertEquals(0, ShellDockEffect.NONE.overflow(48, 3));
    }

    @Test public void invalidParametersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ShellDockEffect(Float.NaN, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ShellDockEffect(2.1f, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ShellDockEffect(1, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ShellDockEffect(1, 0, 0));
    }

    @Test public void themeRoundTripAndWorkAreaAreIndependentOfFeedback() throws Exception {
        var plain = ShellAppearance.defaults();
        var json = ShellAppearanceJson.encode(plain);
        var panels = json.getJSONObject("composition").getJSONArray("panels");
        panels.getJSONObject(0).getJSONObject("style").put("hover",
                new org.json.JSONObject().put("scale", 1.8).put("liftDp", 12).put("radius", 2));
        var decorated = ShellAppearanceJson.parse(json.toString());
        assertEquals(new ShellDockEffect(1.8f, 12, 2), decorated.composition().panels().get(0).style().hover());
        assertEquals(decorated, ShellAppearanceJson.parse(ShellAppearanceJson.encode(decorated).toString()));
        for (var edge : ShellPanel.Edge.values()) {
            var panel = plain.composition().panels().get(0);
            var normal = new ShellPanel(panel.id(), edge, panel.style(), panel.components());
            var dock = new ShellPanel(panel.id(), edge, decorated.composition().panels().get(0).style(), panel.components());
            var geometry = PanelGeometry.resolve(normal, 1, 1920, 1080, 64, 900, 500);
            var layout = new DesktopShellLayout();
            layout.update(new DesktopViewport(0, 0, 1920, 1080, 0, 0, 0, 0), List.of(geometry), false);
            var workArea = layout.snapshot().workArea();
            var paint = layout.taskbar().paint();
            layout.update(new DesktopViewport(0, 0, 1920, 1080, 0, 0, 0, 0),
                    List.of(PanelGeometry.resolve(dock, 1, 1920, 1080, 64, 900, 500)), false);
            assertEquals(workArea, layout.snapshot().workArea());
            assertEquals(paint, layout.taskbar().paint());
            var frame = PanelGeometry.expanded(paint, new ShellBounds(0, 0, 1920, 1080), 32);
            assertTrue(frame.left() >= 0 && frame.top() >= 0 && frame.right() <= 1920 && frame.bottom() <= 1080);
            assertTrue(frame.left() <= paint.left() && frame.right() >= paint.right());
        }
    }
}

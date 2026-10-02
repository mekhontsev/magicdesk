package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ShellPanelMetricsTest {
    @Test public void automaticMetricsShareOnePanelContentContract() {
        var normal = ShellPanelMetrics.resolve(ShellAppearance.PanelStyle.defaults(), false);
        var compact = ShellPanelMetrics.resolve(ShellAppearance.PanelStyle.defaults(), true);
        assertEquals(64, normal.thickness()); assertEquals(48, normal.itemExtent()); assertEquals(34, normal.iconSize());
        assertEquals(52, compact.thickness()); assertEquals(44, compact.itemExtent());
    }
    @Test public void explicitThicknessScalesContentsAndKeepsThemInsidePadding() throws Exception {
        for (int thickness = 40; thickness <= 160; thickness++) {
            var style = new ShellAppearance.PanelStyle(ShellAppearance.Width.FILL, ShellAppearance.Alignment.CENTER,
                    4096, 0, 0, thickness, 16, 0, null, true);
            var metrics = ShellPanelMetrics.resolve(style, false);
            assertEquals(thickness, metrics.thickness());
            assertEquals(thickness, metrics.itemExtent() + 2 * metrics.padding());
            assertTrue(metrics.itemExtent() >= 32 && metrics.iconSize() <= metrics.itemExtent());
        }
    }
}

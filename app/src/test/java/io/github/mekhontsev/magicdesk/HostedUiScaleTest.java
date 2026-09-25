package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class HostedUiScaleTest {
    @Test public void manualAdjustmentIsFractionalAndSeparateFromAutomaticGeometryBudget() {
        assertEquals(1.95, HostedUiScale.adjust(1.3, 150), 0.00001);
        assertEquals(0.5, HostedUiScale.adjust(1, 50), 0);
        assertEquals(16, HostedUiScale.adjust(8, 200), 0);
        assertEquals(2, HostedUiScale.adjust(2, 100), 0);
        assertEquals(0.25, HostedUiScale.adjust(0.1, 50), 0);
        assertEquals(16, HostedUiScale.adjust(100, 200), 0);
        for (int percent : new int[]{50, 100, 125, 150, 200})
            assertEquals(Math.round(96 * HostedUiScale.adjust(1.3, percent)), X11Density.resolve(1.3, percent));
        assertThrows(IllegalArgumentException.class, () -> HostedUiScale.adjust(Double.NaN, 100));
        assertThrows(IllegalArgumentException.class, () -> HostedUiScale.adjust(0, 100));
        assertThrows(IllegalArgumentException.class, () -> HostedUiScale.adjust(1, 49));
        assertThrows(IllegalArgumentException.class, () -> HostedUiScale.adjust(1, 201));
    }

    @Test public void phonePreservesUsefulLogicalSpaceInEitherOrientation() {
        assertEquals(1216 / 600f, HostedUiScale.resolve(520, 1216, 2498), 0.00001f);
        assertEquals(1216 / 600f, HostedUiScale.resolve(520, 2498, 1216), 0.00001f);
        assertEquals(1.8f, HostedUiScale.resolve(480, 1080, 2200), 0.00001f);
    }

    @Test public void densityChoosesScaleWithinGeometryBudget() {
        assertEquals(1, HostedUiScale.resolve(160, 3840, 2160), 0);
        assertEquals(2, HostedUiScale.resolve(320, 3840, 2160), 0);
        assertEquals(3.25f, HostedUiScale.resolve(520, 3840, 2160), 0);
        assertEquals(1.3f, HostedUiScale.resolve(208, 1541, 797), 0.00001f);
        assertEquals(1.3f, HostedUiScale.resolve(208, 1920, 1080), 0.00001f);
        assertEquals(239 / 160f, HostedUiScale.resolve(239, 1600, 1200), 0.00001f);
        assertEquals(1.5f, HostedUiScale.resolve(240, 1600, 1200), 0);
        assertEquals(1599 / 800f, HostedUiScale.resolve(520, 1599, 1200), 0.00001f);
        assertEquals(1199 / 600f, HostedUiScale.resolve(520, 1600, 1199), 0.00001f);
        assertEquals(2, HostedUiScale.resolve(520, 1600, 1200), 0);
    }

    @Test public void neverRequestsSubunitOrUnboundedAutomaticScale() {
        assertEquals(1, HostedUiScale.resolve(520, 1, 1), 0);
        assertEquals(1, HostedUiScale.resolve(1, 3840, 2160), 0);
        assertEquals(8, HostedUiScale.resolve(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE), 0);
        assertThrows(IllegalArgumentException.class, () -> HostedUiScale.resolve(0, 800, 600));
        assertThrows(IllegalArgumentException.class, () -> HostedUiScale.resolve(160, 0, 600));
        assertThrows(IllegalArgumentException.class, () -> HostedUiScale.resolve(160, 800, -1));
    }

    @Test public void androidOfferExcludesImeAndUsesCurrentWindowNotClientSize() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", "static "
                + RuntimeSourceFixture.nestedClass("HostedUiScale", "HostedUiScale") + """
            static class Context {
                boolean ui = true;
                final Resources resources = new Resources();
                final WindowManager windows = new WindowManager();
                boolean isUiContext() { return ui; }
                Resources getResources() { return resources; }
                <T> T getSystemService(Class<T> type) {
                    check(ui, "background launch must not request a visual service");
                    return type.cast(windows);
                }
            }
            static class Resources {
                final Configuration configuration = new Configuration();
                final DisplayMetrics display = new DisplayMetrics();
                Configuration getConfiguration() { return configuration; }
                DisplayMetrics getDisplayMetrics() { return display; }
            }
            static class Configuration { int densityDpi = 520; }
            static class DisplayMetrics { int widthPixels = 1216, heightPixels = 2688; }
            static class WindowManager {
                final Metrics metrics = new Metrics();
                Metrics getCurrentWindowMetrics() { return metrics; }
            }
            static class Metrics {
                Rect bounds = new Rect(1216, 2688);
                final WindowInsets insets = new WindowInsets();
                Rect getBounds() { return bounds; }
                WindowInsets getWindowInsets() { return insets; }
            }
            record Rect(int width, int height) { }
            static class Insets { int left, right, top = 125, bottom = 65; }
            static class WindowInsets {
                int imeHeight;
                final Insets stable = new Insets();
                static class Type {
                    static int systemBars() { return 1; }
                    static int displayCutout() { return 2; }
                }
                Insets getInsetsIgnoringVisibility(int types) {
                    check(types == 3, "only stable bars and cutout reserve scale space");
                    return stable;
                }
            }
            public static void verify() {
                var context = new Context();
                check(HostedUiScale.resolve(context) == 1216 / 600f, "phone offer");
                context.windows.metrics.insets.imeHeight = 1200;
                context.resources.display.heightPixels = 1298;
                check(HostedUiScale.resolve(context) == 1216 / 600f, "IME cannot select a new scale");
                context.windows.metrics.bounds = new Rect(1000, 1500);
                check(HostedUiScale.resolve(context) == 1310 / 800f, "real window resize updates scale");
                context.windows.metrics.bounds = new Rect(1216, 2688);
                check(HostedUiScale.resolve(context) == 1216 / 600f, "restored window offer");
                context.ui = false;
                context.resources.display.heightPixels = 2688;
                check(HostedUiScale.resolve(context) == 1216 / 600f, "launch without an Android host");
            }
            """);
    }
}

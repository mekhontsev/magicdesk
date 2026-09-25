package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class HostedUiScaleTest {
    @Test public void phonePreservesUsefulLogicalSpaceInEitherOrientation() {
        assertEquals(2, HostedUiScale.resolve(520, 1216, 2498));
        assertEquals(2, HostedUiScale.resolve(520, 2498, 1216));
        assertEquals(1, HostedUiScale.resolve(480, 1080, 2200));
    }

    @Test public void densityChoosesScaleWithinGeometryBudget() {
        assertEquals(1, HostedUiScale.resolve(160, 3840, 2160));
        assertEquals(2, HostedUiScale.resolve(320, 3840, 2160));
        assertEquals(3, HostedUiScale.resolve(520, 3840, 2160));
        assertEquals(1, HostedUiScale.resolve(239, 1600, 1200));
        assertEquals(2, HostedUiScale.resolve(240, 1600, 1200));
        assertEquals(1, HostedUiScale.resolve(520, 1599, 1200));
        assertEquals(1, HostedUiScale.resolve(520, 1600, 1199));
        assertEquals(2, HostedUiScale.resolve(520, 1600, 1200));
    }

    @Test public void neverRequestsSubunitOrUnboundedAutomaticScale() {
        assertEquals(1, HostedUiScale.resolve(520, 1, 1));
        assertEquals(1, HostedUiScale.resolve(1, 3840, 2160));
        assertEquals(8, HostedUiScale.resolve(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE));
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
                check(HostedUiScale.resolve(context) == 2, "phone offer");
                context.windows.metrics.insets.imeHeight = 1200;
                context.resources.display.heightPixels = 1298;
                check(HostedUiScale.resolve(context) == 2, "IME cannot select a new scale");
                context.windows.metrics.bounds = new Rect(1000, 1500);
                check(HostedUiScale.resolve(context) == 1, "real window resize updates scale");
                context.windows.metrics.bounds = new Rect(1216, 2688);
                check(HostedUiScale.resolve(context) == 2, "restored window offer");
                context.ui = false;
                context.resources.display.heightPixels = 2688;
                check(HostedUiScale.resolve(context) == 2, "launch without an Android host");
            }
            """);
    }
}

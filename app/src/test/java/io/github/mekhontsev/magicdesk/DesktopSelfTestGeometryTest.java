package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;
import android.view.Surface;

import org.junit.Test;

public final class DesktopSelfTestGeometryTest {
    @Test
    public void derivesWindowsFromArbitraryWorkArea() {
        final DesktopSelfTestGeometry geometry = new DesktopSelfTestGeometry(
                rect(0, 0, 1920, 1200),
                rect(0, 0, 1920, 1144),
                240);

        assertRect(geometry.primaryWindow(), 154, 114, 960, 801);
        assertRect(geometry.browserWindow(), 269, 160, 1651, 1007);
        assertRect(geometry.captionRenderSample(geometry.primaryWindow()),
                557, 124, 959, 164);
        assertRect(geometry.leftWindow(), 77, 103, 922, 961);
        assertRect(geometry.rightWindow(), 998, 103, 1843, 961);
        assertTrue(geometry.scaleFrom160Dpi(82) == 123);
    }

    @Test
    public void evaluatesSnapAgainstOffsetWorkArea() {
        final DesktopSelfTestGeometry geometry = new DesktopSelfTestGeometry(
                rect(0, 0, 1200, 2400),
                rect(0, 80, 1200, 2300),
                320);

        assertTrue(geometry.isSnapped(
                rect(0, 80, 600, 2300), true));
        assertTrue(geometry.isSnapped(
                rect(600, 80, 1200, 2300), false));
        assertFalse(geometry.isSnapped(
                rect(250, 200, 950, 1800), true));
    }

    @Test
    public void reusesWindowManagerMinimumSizeAcrossPlacements() {
        final DesktopSelfTestGeometry geometry = new DesktopSelfTestGeometry(
                rect(0, 0, 1216, 2688),
                rect(0, 125, 1216, 2454),
                520).withObservedWindow(rect(97, 358, 812, 1755));

        assertRect(geometry.primaryWindow(), 97, 358, 812, 1755);
        assertRect(geometry.leftWindow(), 49, 335, 764, 2081);
        assertRect(geometry.rightWindow(), 452, 335, 1167, 2081);
        assertTrue(geometry.containsWindow(rect(97, 358, 812, 1755)));
        assertFalse(geometry.containsWindow(rect(-1, 358, 812, 1755)));
    }

    @Test
    public void refreshesViewportWithoutLosingObservedMinimumSize() {
        final DesktopSelfTestGeometry geometry = new DesktopSelfTestGeometry(
                rect(0, 0, 1216, 2688),
                rect(0, 0, 1216, 2454),
                520).withObservedWindow(rect(97, 245, 812, 1718));

        final DesktopSelfTestGeometry refreshed = geometry.withViewport(
                rect(0, 0, 1216, 2688),
                rect(0, 125, 1216, 2389));

        assertRect(refreshed.workArea, 0, 125, 1216, 2389);
        final Rect window = refreshed.captionControlsWindow(false);
        assertTrue(window.right - window.left >= 715);
        assertTrue(window.bottom - window.top >= 1473);
    }

    @Test
    public void refreshesRotationUsedForNaturalInputFrames() {
        final DesktopSelfTestGeometry geometry = new DesktopSelfTestGeometry(
                rect(0, 0, 1216, 2688),
                rect(0, 125, 1216, 2454),
                520).withInputViewport(
                        rect(0, 0, 2688, 1216),
                        Surface.ROTATION_90);

        assertRect(geometry.captionControlsWindow(false),
                49, 335, 1154, 2081);
        assertRect(geometry.inputFrame(new TaskInputWindowParser.Frame(
                751, 49, 881, 1154)),
                49, 335, 1154, 465);
    }

    @Test
    public void widensPhoneWindowForNativeCaptionControls() {
        final DesktopSelfTestGeometry geometry = new DesktopSelfTestGeometry(
                rect(0, 0, 1216, 2688),
                rect(0, 125, 1216, 2454),
                520).withObservedWindow(rect(97, 358, 812, 1755));

        assertRect(geometry.captionControlsWindow(false),
                49, 335, 1154, 2081);
        assertRect(geometry.captionControlsWindow(true),
                62, 335, 1167, 2081);
    }

    @Test
    public void keepsAlreadyWideDesktopWindowUnchanged() {
        final DesktopSelfTestGeometry geometry = new DesktopSelfTestGeometry(
                rect(0, 0, 1920, 1200),
                rect(0, 0, 1920, 1144),
                160);

        assertRect(geometry.captionControlsWindow(false),
                77, 103, 922, 961);
        assertRect(geometry.captionControlsWindow(true),
                998, 103, 1843, 961);
    }

    @Test
    public void acceptsNativeSnapConstrainedByPhoneMinimumWidth() {
        final DesktopSelfTestGeometry geometry = new DesktopSelfTestGeometry(
                rect(0, 0, 1216, 2688),
                rect(0, 125, 1216, 2454),
                520).withObservedWindow(rect(97, 358, 812, 1755));

        assertTrue(geometry.isNativeSideBySide(
                rect(0, 125, 715, 2623),
                rect(608, 125, 1323, 2623)));
        assertFalse(geometry.isNativeSideBySide(
                rect(0, 125, 715, 2623),
                rect(760, 125, 1475, 2623)));
    }

    @Test
    public void mapsNaturalInputFramesIntoRotatedDisplaySpace() {
        final TaskInputWindowParser.Frame landscapeInput =
                new TaskInputWindowParser.Frame(879, 215, 1009, 1344);
        final DesktopSelfTestGeometry clockwise = new DesktopSelfTestGeometry(
                rect(0, 0, 2688, 1216),
                rect(0, 125, 2688, 943),
                520,
                Surface.ROTATION_90);
        final DesktopSelfTestGeometry upsideDown =
                new DesktopSelfTestGeometry(
                        rect(0, 0, 1216, 2688),
                        rect(0, 125, 1216, 2454),
                        520,
                        Surface.ROTATION_180);
        final DesktopSelfTestGeometry counterClockwise =
                new DesktopSelfTestGeometry(
                        rect(0, 0, 2688, 1216),
                        rect(0, 125, 2688, 943),
                        520,
                        Surface.ROTATION_270);

        assertRect(clockwise.inputFrame(landscapeInput),
                215, 207, 1344, 337);
        assertRect(upsideDown.inputFrame(
                        new TaskInputWindowParser.Frame(206, 2337, 1001, 2561)),
                215, 127, 1010, 351);
        assertRect(counterClockwise.inputFrame(
                        new TaskInputWindowParser.Frame(207, 1344, 337, 2473)),
                215, 207, 1344, 337);
    }

    @Test
    public void sharesParsedTaskBoundsAcrossSelfTestSuites() {
        final TaskStackParser.Bounds parsed =
                new TaskStackParser.Bounds(10, 20, 300, 400);

        assertTrue(DesktopSelfTestGeometry.matches(
                parsed, rect(10, 20, 300, 400)));
        assertFalse(DesktopSelfTestGeometry.matches(
                parsed, rect(10, 20, 301, 400)));
        assertRect(DesktopSelfTestGeometry.toRect(parsed),
                10, 20, 300, 400);
        assertNull(DesktopSelfTestGeometry.toRect(null));
        assertEquals("[10,20][300,400]",
                DesktopSelfTestGeometry.format(parsed));
    }

    private static Rect rect(
            final int left,
            final int top,
            final int right,
            final int bottom) {
        final Rect bounds = new Rect();
        bounds.left = left;
        bounds.top = top;
        bounds.right = right;
        bounds.bottom = bottom;
        return bounds;
    }

    private static void assertRect(
            final Rect actual,
            final int left,
            final int top,
            final int right,
            final int bottom) {
        assertTrue(actual.left == left);
        assertTrue(actual.top == top);
        assertTrue(actual.right == right);
        assertTrue(actual.bottom == bottom);
    }
}

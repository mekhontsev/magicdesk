package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.graphics.Rect;

import org.junit.Test;

public final class NativeWindowBoundsControllerTest {
    private static final Rect DISPLAY = rect(0, 0, 1920, 1080);
    private static final Rect WORK_AREA = rect(0, 0, 1920, 1016);

    @Test
    public void nativeLeftSnapUsesTaskbarWorkArea() {
        assertBounds(
                NativeWindowBoundsController.correctNativeCaptionSnapBounds(
                        rect(0, 0, 960, 1080),
                        DISPLAY,
                        WORK_AREA),
                0, 0, 960, 1016);
    }

    @Test
    public void nativeRightSnapUsesTaskbarWorkArea() {
        assertBounds(
                NativeWindowBoundsController.correctNativeCaptionSnapBounds(
                        rect(960, 0, 1920, 1080),
                        DISPLAY,
                        WORK_AREA),
                960, 0, 1920, 1016);
    }

    @Test
    public void existingWorkAreaSnapNeedsNoCorrection() {
        assertNull(
                NativeWindowBoundsController.correctNativeCaptionSnapBounds(
                        rect(0, 0, 960, 1016),
                        DISPLAY,
                        WORK_AREA));
    }

    @Test
    public void arbitraryResizeIsNotTreatedAsNativeSnap() {
        assertNull(
                NativeWindowBoundsController.correctNativeCaptionSnapBounds(
                        rect(0, 80, 960, 1080),
                        DISPLAY,
                        WORK_AREA));
        assertNull(
                NativeWindowBoundsController.correctNativeCaptionSnapBounds(
                        rect(100, 0, 1000, 1080),
                        DISPLAY,
                        WORK_AREA));
    }

    private static void assertBounds(
            final Rect actual,
            final int left,
            final int top,
            final int right,
            final int bottom) {
        assertEquals(left, actual.left);
        assertEquals(top, actual.top);
        assertEquals(right, actual.right);
        assertEquals(bottom, actual.bottom);
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
}

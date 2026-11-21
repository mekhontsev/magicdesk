package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import org.junit.Test;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.*;

public final class TaskWindowGeometryTest {
    @Test public void scalesSizeButNotTheDisplayOrigin() {
        Rect logical = rect(901, 144, 2341, 2210);
        Rect visible = TaskWindowGeometry.displayBounds(logical, 0.4114f);
        assertRect(visible, 901, 144, 1493, 994);
        assertRect(logical, 901, 144, 2341, 2210);
    }

    @Test public void snapAndRestoreRoundTripInDisplayPixels() {
        for (float scale : new float[] {0.4114f, 0.79f, 1f, 1.25f}) {
            Rect requested = rect(0, 0, 1280, 1376);
            Rect logical = TaskWindowGeometry.logicalBounds(requested, scale);
            Rect observed = TaskWindowGeometry.displayBounds(logical, scale);
            assertTrue(Math.abs(observed.right - requested.right) <= 1);
            assertTrue(Math.abs(observed.bottom - requested.bottom) <= 1);
        }
    }

    @Test public void offscreenOriginsAreNotMultiplied() {
        assertRect(TaskWindowGeometry.displayBounds(rect(-400, 30, 800, 870), .5f),
                -400, 30, 200, 450);
        assertRect(TaskWindowGeometry.logicalBounds(rect(-400, 30, 200, 450), .5f),
                -400, 30, 800, 870);
    }

    @Test public void rejectsUnknownAndOverflowingGeometry() {
        for (float scale : new float[] {0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertFalse(TaskWindowGeometry.validScale(scale));
            assertThrows(IllegalArgumentException.class,
                    () -> TaskWindowGeometry.displayBounds(rect(0, 0, 100, 100), scale));
            assertThrows(IllegalArgumentException.class,
                    () -> TaskWindowGeometry.logicalBounds(rect(0, 0, 100, 100), scale));
        }
        assertThrows(IllegalArgumentException.class,
                () -> TaskWindowGeometry.logicalBounds(rect(100, 0, 200, 100), Float.MIN_VALUE));
        assertThrows(IllegalArgumentException.class,
                () -> TaskWindowGeometry.displayBounds(rect(Integer.MAX_VALUE - 10, 0,
                        Integer.MAX_VALUE, 100), 2f));
    }

    @Test public void typedSnapshotPublishesVisibleBoundsAndRetainsLogicalEvidence() {
        FrameworkTaskSnapshot task = snapshot(.5f);
        assertRect(task.bounds, 30, 40, 630, 440);
        assertRect(task.logicalBounds, 30, 40, 1230, 840);
        assertEquals(.5f, task.windowScale, 0);
        task = snapshot(Float.NaN);
        assertRect(task.bounds, 0, 0, 0, 0);
        assertRect(task.logicalBounds, 30, 40, 1230, 840);
        assertTrue(Float.isNaN(task.windowScale));
    }

    private static FrameworkTaskSnapshot snapshot(float scale) {
        return new FrameworkTaskSnapshot(null, 42, 42, 7, 1, 5, 1,
                null, null, "sample/.Activity", "sample/.Activity", "sample", "sample",
                10000, "sample", rect(30, 40, 1230, 840), true, true, null, null, 0, scale);
    }

    private static Rect rect(int l, int t, int r, int b) {
        Rect result = new Rect();
        result.left=l; result.top=t; result.right=r; result.bottom=b;
        return result;
    }
    private static void assertRect(Rect value, int l, int t, int r, int b) {
        assertEquals(l, value.left); assertEquals(t, value.top);
        assertEquals(r, value.right); assertEquals(b, value.bottom);
    }

    @Test public void validatesEntireBatchAndAcceptsOffscreenCoordinates() {
        Rect bounds = DesktopWindowLayoutController.rect(new WindowLayoutPolicy.Bounds(-200, 10, 900, 800));
        TaskWindowingCommand.validateGeometry(3, new int[] {10, 20}, new Rect[] {bounds, bounds});
        assertThrows(IllegalArgumentException.class, () -> TaskWindowingCommand.validateGeometry(
                3, new int[] {10, 10}, new Rect[] {bounds, bounds}));
        assertThrows(IllegalArgumentException.class, () -> TaskWindowingCommand.validateGeometry(
                3, new int[] {10, 20}, new Rect[] {bounds, null}));
        assertThrows(IllegalArgumentException.class, () -> TaskWindowingCommand.validateGeometry(
                3, new int[] {10}, new Rect[] {}));
    }
}

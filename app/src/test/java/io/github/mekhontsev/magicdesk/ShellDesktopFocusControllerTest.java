package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;

import java.util.Arrays;

public final class ShellDesktopFocusControllerTest {
    @Test
    public void refreshesMissingOrStaleLiveInputFocus() {
        assertTrue(ShellDesktopFocusController.requiresInputFocusRefresh(
                42, -1, false));
        assertTrue(ShellDesktopFocusController.requiresInputFocusRefresh(
                42, 41, true));
    }

    @Test
    public void ignoresCurrentOrDeadInputFocus() {
        assertFalse(ShellDesktopFocusController.requiresInputFocusRefresh(
                42, 42, true));
        assertFalse(ShellDesktopFocusController.requiresInputFocusRefresh(
                42, 41, false));
    }

    @Test
    public void missingFreeformWindowRepairsItsRootButFullscreenKeepsPlane() {
        assertTrue(ShellDesktopFocusController
                .requiresParentReorderForMissingWindow(
                        FrameworkTaskSnapshot.WINDOWING_MODE_FREEFORM));
        assertFalse(ShellDesktopFocusController
                .requiresParentReorderForMissingWindow(
                        FrameworkTaskSnapshot.WINDOWING_MODE_FULLSCREEN));
    }

    @Test
    public void confirmsVisibleOrganizerChildWithoutAssumingPlaneOrder() {
        final FrameworkTaskSnapshot front = snapshot(42, true, false);
        final FrameworkTaskSnapshot covered = snapshot(41, true, false);

        assertTrue(ShellDesktopFocusController.isFocusConfirmationReady(
                42, Arrays.asList(front, covered)));
        assertTrue(ShellDesktopFocusController.isFocusConfirmationReady(
                41, Arrays.asList(front, covered)));
        assertFalse(ShellDesktopFocusController.isFocusConfirmationReady(
                40, Arrays.asList(front, covered)));
        assertFalse(ShellDesktopFocusController.isFocusConfirmationReady(
                41, Arrays.asList(front, snapshot(41, false, true))));
    }

    private static FrameworkTaskSnapshot snapshot(
            final int taskId,
            final boolean visible,
            final boolean focused) {
        return new FrameworkTaskSnapshot(
                new Object(),
                taskId,
                taskId,
                4,
                0,
                FrameworkTaskSnapshot.WINDOWING_MODE_FULLSCREEN,
                1,
                null,
                null,
                "example/.Window",
                "example/.Window",
                "example",
                "example",
                10000,
                "example",
                new Rect(0, 0, 100, 100),
                visible,
                focused,
                null);
    }
}

package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;

import java.util.Arrays;

public final class ShellDesktopFocusControllerTest {
    @Test
    public void homeTargetUsesImmediateFocusRepairPath() {
        assertTrue(ShellDesktopFocusController.isDesktopHostTarget(
                2,
                BuildConfig.APPLICATION_ID,
                BuildConfig.APPLICATION_ID + ".DesktopActivity"));
        assertTrue(ShellDesktopFocusController.isDesktopHostTarget(
                2,
                BuildConfig.APPLICATION_ID,
                BuildConfig.APPLICATION_ID + ".PhoneDesktopHome"));
        assertFalse(ShellDesktopFocusController.isDesktopHostTarget(
                1,
                BuildConfig.APPLICATION_ID,
                BuildConfig.APPLICATION_ID + ".DesktopActivity"));
        assertFalse(ShellDesktopFocusController.isDesktopHostTarget(
                2,
                "example.launcher",
                "example.launcher.Home"));
    }

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
        final FrameworkTaskSnapshot phoneHome = snapshot(
                43,
                FrameworkTaskSnapshot.ACTIVITY_TYPE_HOME,
                BuildConfig.APPLICATION_ID + "/.PhoneDesktopHome",
                true,
                true);

        assertTrue(ShellDesktopFocusController.isFocusConfirmationReady(
                42, Arrays.asList(front, covered)));
        assertTrue(ShellDesktopFocusController.isFocusConfirmationReady(
                41, Arrays.asList(front, covered)));
        assertFalse(ShellDesktopFocusController.isFocusConfirmationReady(
                40, Arrays.asList(front, covered)));
        assertFalse(ShellDesktopFocusController.isFocusConfirmationReady(
                41, Arrays.asList(front, snapshot(41, false, true))));
        assertTrue(ShellDesktopFocusController.isFocusConfirmationReady(
                43, Arrays.asList(front, phoneHome)));
    }

    private static FrameworkTaskSnapshot snapshot(
            final int taskId,
            final boolean visible,
            final boolean focused) {
        return snapshot(taskId, 1, "example/.Window", visible, focused);
    }

    private static FrameworkTaskSnapshot snapshot(
            final int taskId,
            final int activityType,
            final String componentName,
            final boolean visible,
            final boolean focused) {
        final String packageName = componentName.substring(
                0, componentName.indexOf('/'));
        return new FrameworkTaskSnapshot(
                new Object(),
                taskId,
                taskId,
                4,
                0,
                FrameworkTaskSnapshot.WINDOWING_MODE_FULLSCREEN,
                activityType,
                null,
                null,
                componentName,
                componentName,
                packageName,
                packageName,
                10000,
                packageName,
                new Rect(0, 0, 100, 100),
                visible,
                focused,
                null);
    }
}

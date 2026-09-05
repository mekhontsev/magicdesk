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
                BuildConfig.APPLICATION_ID + ".PhoneDesktopHomeActivity"));
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
                50, 50, 42, -1, false));
        assertTrue(ShellDesktopFocusController.requiresInputFocusRefresh(
                50, 50, 42, 41, true));
    }

    @Test
    public void ignoresCurrentOrDeadInputFocus() {
        assertFalse(ShellDesktopFocusController.requiresInputFocusRefresh(
                50, 50, 42, 42, true));
        assertFalse(ShellDesktopFocusController.requiresInputFocusRefresh(
                50, 50, 42, 41, false));
    }

    @Test
    public void phoneNavigationIsNotMissingDesktopInput() {
        assertFalse(ShellDesktopFocusController.requiresInputFocusRefresh(
                50, 0, 42, -1, false));
        assertFalse(ShellDesktopFocusController.requiresInputFocusRefresh(
                50, 0, 42, 41, true));
        assertFalse(ShellDesktopFocusController.requiresInputFocusRefresh(
                50, -1, 42, -1, false));
        assertFalse(ShellDesktopFocusController.requiresInputFocusRefresh(
                -1, -1, 42, -1, false));
    }

    @Test
    public void repairsOnlyTheActiveDisplayOnEveryTarget() {
        assertTrue(ShellDesktopFocusController.requiresInputFocusRefresh(
                0, 0, 42, -1, false));
        assertFalse(ShellDesktopFocusController.requiresInputFocusRefresh(
                0, 50, 42, -1, false));
        assertFalse(ShellDesktopFocusController.requiresInputFocusRefresh(
                50, 51, 42, -1, false));
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
                BuildConfig.APPLICATION_ID + "/.PhoneDesktopHomeActivity",
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
        final FrameworkTaskSnapshot panelHost = snapshot(
                44,
                1,
                BuildConfig.APPLICATION_ID + "/.DesktopChromeActivity",
                true,
                true);
        assertFalse(ShellDesktopFocusController.isFocusConfirmationReady(
                44, Arrays.asList(front, panelHost)));
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

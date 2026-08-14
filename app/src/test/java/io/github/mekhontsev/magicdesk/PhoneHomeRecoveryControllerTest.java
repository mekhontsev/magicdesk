package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public final class PhoneHomeRecoveryControllerTest {
    private static final String SECONDARY_HOME =
            "com.zte.mifavor.launcher/"
                    + "com.android.launcher3.secondarydisplay.SecondaryDisplayLauncher";
    private static final String PRIMARY_HOME =
            "com.zte.mifavor.launcher/"
                    + "com.android.launcher3.uioverrides.QuickstepLauncher";
    private static final String MAGICDESK_DESKTOP =
            "io.github.mekhontsev.magicdesk/"
                    + "io.github.mekhontsev.magicdesk.DesktopActivity";
    private static final String SYSTEM_DESKTOP_WALLPAPER =
            "com.android.systemui/"
                    + "com.android.wm.shell.desktopmode.DesktopWallpaperActivity";
    private static final PhoneHomeComponents HOME =
            PhoneHomeComponents.forTests(PRIMARY_HOME, SECONDARY_HOME);

    @Test
    public void detectsVisibleSecondaryHomeOnPhone() {
        assertTrue(PhoneHomeRecoveryController.needsPrimaryHomeRestore(
                Collections.singletonList(task(SECONDARY_HOME, true, true)),
                false,
                HOME));
    }

    @Test
    public void primaryHomeCommandUsesResolvedComponent() {
        assertTrue(PhoneHomeRecoveryController.primaryHomeCommand(HOME)
                .endsWith(" -n " + PRIMARY_HOME));
        assertFalse(PhoneHomeRecoveryController.primaryHomeCommand(
                PhoneHomeComponents.forTests(""))
                .contains(" -n "));
    }

    @Test
    public void activeConsoleRecoveryOnlyRepairsSecondaryHome() {
        assertFalse(PhoneHomeRecoveryController.shouldRestoreStrandedDesktop(
                true,
                true));
    }

    @Test
    public void consoleExitRecoveryAlsoRepairsStrandedDesktop() {
        assertTrue(PhoneHomeRecoveryController.shouldRestoreStrandedDesktop(
                false,
                true));
    }

    @Test
    public void anyTaskRemovalFailureKeepsRecoveryPending() {
        assertTrue(PhoneHomeRecoveryController.allTaskCleanupSucceeded(
                true, true, true));
        assertFalse(PhoneHomeRecoveryController.allTaskCleanupSucceeded(
                false, true, true));
        assertFalse(PhoneHomeRecoveryController.allTaskCleanupSucceeded(
                true, false, true));
        assertFalse(PhoneHomeRecoveryController.allTaskCleanupSucceeded(
                true, true, false));
    }

    @Test
    public void ignoresInvisibleOrNonHomeSecondaryTask() {
        assertFalse(PhoneHomeRecoveryController.needsPrimaryHomeRestore(
                Arrays.asList(
                        task(SECONDARY_HOME, false, true),
                        task(SECONDARY_HOME, true, false)),
                true,
                HOME));
    }

    @Test
    public void ignoresPrimaryPhoneHome() {
        assertFalse(PhoneHomeRecoveryController.needsPrimaryHomeRestore(
                Collections.singletonList(task(PRIMARY_HOME, true, true)),
                true,
                HOME));
    }

    @Test
    public void ignoresSecondaryBaseTaskWhenPrimaryHomeIsOnTop() {
        final TaskRepository.TaskEntry secondaryBase =
                task(SECONDARY_HOME, true, true, PRIMARY_HOME);
        assertFalse(PhoneHomeRecoveryController.needsPrimaryHomeRestore(
                Collections.singletonList(secondaryBase),
                true,
                HOME));
        assertFalse(PhoneHomeRecoveryController
                .isRemovableSecondaryPhoneHomeTask(
                secondaryBase, HOME));
    }

    @Test
    public void primaryBaseTaskWithSecondaryOnTopIsRestoredNotRemoved() {
        final TaskRepository.TaskEntry mixedTask =
                task(PRIMARY_HOME, true, true, SECONDARY_HOME);
        assertTrue(PhoneHomeRecoveryController.needsPrimaryHomeRestore(
                Collections.singletonList(mixedTask),
                true,
                HOME));
        assertFalse(PhoneHomeRecoveryController
                .isRemovableSecondaryPhoneHomeTask(mixedTask, HOME));
    }

    @Test
    public void detectsHiddenSecondaryHomeForCleanup() {
        assertTrue(PhoneHomeRecoveryController
                .isRemovableSecondaryPhoneHomeTask(
                task(SECONDARY_HOME, false, true), HOME));
        assertFalse(PhoneHomeRecoveryController
                .isRemovableSecondaryPhoneHomeTask(
                task(SECONDARY_HOME, true, false), HOME));
        assertFalse(PhoneHomeRecoveryController
                .isRemovableSecondaryPhoneHomeTask(
                task(PRIMARY_HOME, false, true), HOME));
    }

    @Test
    public void detectsDesktopActivityStrandedAfterDisplayRemoval() {
        final TaskRepository.TaskEntry task =
                task(MAGICDESK_DESKTOP, true, false, MAGICDESK_DESKTOP);
        assertTrue(PhoneHomeRecoveryController.needsPrimaryHomeRestore(
                Collections.singletonList(task),
                true,
                HOME));
        assertFalse(PhoneHomeRecoveryController.needsPrimaryHomeRestore(
                Collections.singletonList(task),
                false,
                HOME));
        assertTrue(PhoneHomeRecoveryController.isStrandedDesktopTask(
                task, false));
        assertFalse(PhoneHomeRecoveryController.isStrandedDesktopTask(
                task, true));
        assertFalse(PhoneHomeRecoveryController
                .hasVisiblePhoneTaskAfterCleanup(
                        Collections.singletonList(task), false, HOME, false));
        assertTrue(PhoneHomeRecoveryController
                .hasVisiblePhoneTaskAfterCleanup(
                        Collections.singletonList(task), true, HOME, false));
    }

    @Test
    public void removesSystemDesktopWallpaperOnlyDuringDesktopExitRecovery() {
        final TaskRepository.TaskEntry wallpaper = task(
                SYSTEM_DESKTOP_WALLPAPER,
                true,
                false,
                SYSTEM_DESKTOP_WALLPAPER);
        assertTrue(PhoneHomeRecoveryController
                .isStrandedSystemDesktopWallpaperTask(wallpaper, true));
        assertFalse(PhoneHomeRecoveryController
                .isStrandedSystemDesktopWallpaperTask(wallpaper, false));
        assertFalse(PhoneHomeRecoveryController
                .hasVisiblePhoneTaskAfterCleanup(
                        Collections.singletonList(wallpaper),
                        false,
                        HOME,
                        true));
        assertTrue(PhoneHomeRecoveryController
                .hasVisiblePhoneTaskAfterCleanup(
                        Collections.singletonList(wallpaper),
                        false,
                        HOME,
                        false));
    }

    @Test
    public void visiblePhoneTaskPredictionExcludesRemovedSecondaryHome() {
        assertFalse(PhoneHomeRecoveryController
                .hasVisiblePhoneTaskAfterCleanup(
                        Collections.singletonList(
                                task(SECONDARY_HOME, true, true)),
                        false,
                        HOME,
                        false));
        assertTrue(PhoneHomeRecoveryController
                .hasVisiblePhoneTaskAfterCleanup(
                        Collections.singletonList(
                                task(PRIMARY_HOME, true, true)),
                        false,
                        HOME,
                        false));
    }

    private static TaskRepository.TaskEntry task(
            final String component,
            final boolean visible,
            final boolean home) {
        return new TaskRepository.TaskEntry(
                1,
                2,
                0,
                "com.zte.mifavor.launcher",
                component,
                component,
                "fullscreen",
                new Rect(0, 0, 1216, 2688),
                home,
                visible,
                false);
    }

    private static TaskRepository.TaskEntry task(
            final String component,
            final boolean visible,
            final boolean home,
            final String topActivity) {
        return new TaskRepository.TaskEntry(
                1,
                2,
                0,
                component.substring(0, component.indexOf('/')),
                component,
                topActivity,
                "fullscreen",
                new Rect(0, 0, 1216, 2688),
                home,
                visible,
                false);
    }
}

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

    @Test
    public void detectsVisibleSecondaryHomeOnPhone() {
        assertTrue(PhoneHomeRecoveryController.needsPrimaryHomeRestore(
                Collections.singletonList(task(SECONDARY_HOME, true, true)),
                false));
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
    public void ignoresInvisibleOrNonHomeSecondaryTask() {
        assertFalse(PhoneHomeRecoveryController.needsPrimaryHomeRestore(
                Arrays.asList(
                        task(SECONDARY_HOME, false, true),
                        task(SECONDARY_HOME, true, false)),
                true));
    }

    @Test
    public void ignoresPrimaryPhoneHome() {
        assertFalse(PhoneHomeRecoveryController.needsPrimaryHomeRestore(
                Collections.singletonList(task(PRIMARY_HOME, true, true)),
                true));
    }

    @Test
    public void ignoresSecondaryBaseTaskWhenPrimaryHomeIsOnTop() {
        assertFalse(PhoneHomeRecoveryController.needsPrimaryHomeRestore(
                Collections.singletonList(
                        task(SECONDARY_HOME, true, true, PRIMARY_HOME)),
                true));
    }

    @Test
    public void detectsDesktopActivityStrandedAfterDisplayRemoval() {
        final TaskRepository.TaskEntry task =
                task(MAGICDESK_DESKTOP, true, false, MAGICDESK_DESKTOP);
        assertTrue(PhoneHomeRecoveryController.needsPrimaryHomeRestore(
                Collections.singletonList(task),
                true));
        assertFalse(PhoneHomeRecoveryController.needsPrimaryHomeRestore(
                Collections.singletonList(task),
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

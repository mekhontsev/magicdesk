package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;

import java.util.Set;

public final class ShellTaskLauncherTest {
    private static final int DISPLAY_ID = 9;

    @Test
    public void matchingStandardTaskSatisfiesLaunchContract() {
        assertTrue(ShellTaskLauncher.launchTopologyViolation(
                task(DISPLAY_ID, 5,
                        FrameworkTaskSnapshot.ACTIVITY_TYPE_STANDARD),
                DISPLAY_ID,
                5).isEmpty());
    }

    @Test
    public void launchContractIncludesExpectedActivityIdentity() {
        final FrameworkTaskSnapshot task = task(
                DISPLAY_ID,
                FrameworkTaskSnapshot.WINDOWING_MODE_FREEFORM,
                FrameworkTaskSnapshot.ACTIVITY_TYPE_STANDARD);
        assertTrue(ShellTaskLauncher.launchContractViolation(
                task,
                LaunchActivityIdentity.packageScoped(
                        "com.example.app", null),
                DISPLAY_ID,
                FrameworkTaskSnapshot.WINDOWING_MODE_FREEFORM).isEmpty());
        assertTrue(ShellTaskLauncher.launchContractViolation(
                task,
                LaunchActivityIdentity.packageScoped(
                        "com.example.other", null),
                DISPLAY_ID,
                FrameworkTaskSnapshot.WINDOWING_MODE_FREEFORM)
                .contains("identity"));
    }

    @Test
    public void launchContractRejectsWrongTopology() {
        assertTrue(ShellTaskLauncher.launchTopologyViolation(
                task(DISPLAY_ID + 1, 5, 1),
                DISPLAY_ID,
                5).contains("entered display"));
        assertTrue(ShellTaskLauncher.launchTopologyViolation(
                task(DISPLAY_ID, 5, 2),
                DISPLAY_ID,
                5).contains("activityType=2"));
        assertTrue(ShellTaskLauncher.launchTopologyViolation(
                task(DISPLAY_ID, 1, 1),
                DISPLAY_ID,
                5).contains("windowingMode=1"));
    }

    @Test
    public void rollbackRequiresFreshTaskCreationEvidence() {
        assertTrue(ShellTaskLauncher.shouldRollbackTask(
                31, Set.of(10, 20), Set.of(31)));
        assertFalse(ShellTaskLauncher.shouldRollbackTask(
                20, Set.of(10, 20), Set.of(20)));
        assertFalse(ShellTaskLauncher.shouldRollbackTask(
                31, Set.of(10, 20), Set.of(32)));
    }

    private static FrameworkTaskSnapshot task(
            final int displayId,
            final int windowingMode,
            final int activityType) {
        final String packageName = "com.example.app";
        final String flattened = packageName + "/.MainActivity";
        return new FrameworkTaskSnapshot(
                new Object(),
                31,
                31,
                displayId,
                1,
                windowingMode,
                activityType,
                null,
                null,
                flattened,
                flattened,
                packageName,
                packageName,
                10000,
                packageName,
                new Rect(0, 0, 1000, 700),
                true,
                true,
                null);
    }
}

package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopSelfTestSteps.usefulMessage;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.POLL_MILLIS;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.STEP_TIMEOUT_MILLIS;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.findTaskById;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.findTaskOnAnyDisplay;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.waitForTask;

import android.content.ComponentName;
import android.content.Intent;
import android.os.SystemClock;
import android.view.Display;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

/** Verifies runtime cleanup after an owned simulated display disappears. */
final class DesktopSelfTestDisplayRemovalSuite {
    private static final String FULLSCREEN_AREA_NAME =
            "MagicDesk fullscreen stack";

    private DesktopSelfTestDisplayRemovalSuite() {
    }

    static void run(
            final DesktopSelfTestResult result,
            final int displayId,
            final SimulatedDisplayLease lease) {
        final Set<Integer> fixtureTaskIds = new LinkedHashSet<>();
        try {
            if (lease == null) {
                throw new IOException("simulated display lease is unavailable");
            }
            // This suite owns its removal fixture. Earlier window tests are
            // free to finish every task they create, including through Back.
            launchFullscreenFixture(displayId);
            final String stack = ShellAccess.run(
                    "/system/bin/cmd activity stack list");
            for (final TaskStackParser.Entry task
                    : TaskStackParser.parse(stack)) {
                if (task.displayId == displayId
                        && DesktopSelfTestComponents.isFixtureTask(task)) {
                    if (!"fullscreen".equals(task.windowingMode)) {
                        throw new IOException("fixture task " + task.taskId
                                + " was not fullscreen before display removal");
                    }
                    fixtureTaskIds.add(Integer.valueOf(task.taskId));
                }
            }
            if (fixtureTaskIds.isEmpty()) {
                throw new IOException(
                        "no live fullscreen fixture on display " + displayId);
            }
            final WindowTransitionHealthDiagnostics.IdleResult idle =
                    WindowTransitionHealthDiagnostics.awaitDisplayIdle(
                            MagicDeskApplication.applicationContext(),
                            displayId,
                            STEP_TIMEOUT_MILLIS);
            if (!idle.idle) {
                throw new IOException(
                        "window transitions did not finish on display "
                                + displayId + ": " + idle.detail);
            }
            lease.close();
            waitForDisplayRemoval(displayId);
            result.add(DesktopSelfTestResult.State.PASS,
                    "DISPLAY-REMOVAL-001",
                    "Remove a live simulated desktop display",
                    "display=" + displayId + ", tasks=" + fixtureTaskIds);
        } catch (Exception error) {
            result.add(DesktopSelfTestResult.State.FAIL,
                    "DISPLAY-REMOVAL-001",
                    "Remove a live simulated desktop display",
                    usefulMessage(error));
            addSkipped(result, "simulated display removal failed");
            return;
        }

        try {
            result.add(DesktopSelfTestResult.State.PASS,
                    "DISPLAY-REMOVAL-002",
                    "Release desktop runtime after display removal",
                    waitForRuntimeRelease(displayId));
        } catch (Exception error) {
            result.add(DesktopSelfTestResult.State.FAIL,
                    "DISPLAY-REMOVAL-002",
                    "Release desktop runtime after display removal",
                    usefulMessage(error));
        }

        try {
            result.add(DesktopSelfTestResult.State.PASS,
                    "DISPLAY-REMOVAL-003",
                    "Keep migrated phone tasks out of freeform",
                    waitForSafeTaskMigration(displayId, fixtureTaskIds));
        } catch (Exception error) {
            result.add(DesktopSelfTestResult.State.FAIL,
                    "DISPLAY-REMOVAL-003",
                    "Keep migrated phone tasks out of freeform",
                    usefulMessage(error));
        }
    }

    static void addNotTested(
            final DesktopSelfTestResult result,
            final String reason) {
        result.add(DesktopSelfTestResult.State.NOT_TESTED,
                "DISPLAY-REMOVAL-001",
                "Remove a live simulated desktop display",
                reason);
        addSkipped(result, reason);
    }

    private static void addSkipped(
            final DesktopSelfTestResult result,
            final String reason) {
        result.add(DesktopSelfTestResult.State.NOT_TESTED,
                "DISPLAY-REMOVAL-002",
                "Release desktop runtime after display removal",
                reason);
        result.add(DesktopSelfTestResult.State.NOT_TESTED,
                "DISPLAY-REMOVAL-003",
                "Keep migrated phone tasks out of freeform",
                reason);
    }

    private static void launchFullscreenFixture(final int displayId)
            throws IOException {
        final String token = "display-removal-"
                + Long.toHexString(System.nanoTime());
        final Intent intent = TaskDisplayAreaLaunchCommand
                .createSelfTestIntent(
                        displayId,
                        token,
                        false,
                        DesktopSelfTestFixtureAppearance.PRIMARY)
                .setAction(Intent.ACTION_VIEW);
        final ComponentName component = intent.getComponent();
        try (DesktopTaskLaunchProbe probe =
                     DesktopTaskLaunchProbe.open(-1, component)) {
            FullscreenAppLauncher.launch(intent, displayId);
            final DesktopTaskLaunchProbe.Observation observation =
                    probe.awaitObservation();
            if (observation.displayId != displayId) {
                throw new IOException(
                        "removal fixture launched on display "
                                + observation.displayId);
            }
            waitForTask(
                    displayId,
                    DesktopSelfTestComponents.FIXTURE_CLASS,
                    task -> task.taskId == observation.taskId
                            && task.visible
                            && "fullscreen".equals(task.windowingMode));
        }
    }

    private static void waitForDisplayRemoval(final int displayId)
            throws IOException {
        final long deadline = SystemClock.uptimeMillis()
                + STEP_TIMEOUT_MILLIS;
        do {
            if (!ConsoleDisplayController.displayExists(displayId)) {
                return;
            }
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("display " + displayId + " remained available");
    }

    private static String waitForRuntimeRelease(final int displayId)
            throws IOException {
        final long deadline = SystemClock.uptimeMillis()
                + STEP_TIMEOUT_MILLIS;
        int activeDisplay = DesktopRuntimeBridge.getActiveDesktopDisplayId();
        DesktopDisplayTarget target =
                DesktopRuntimeBridge.getDesktopTarget(displayId);
        TaskStackParser.Entry desktop = null;
        boolean fullscreenAreaPresent = true;
        do {
            final String stack = ShellAccess.run(
                    "/system/bin/cmd activity stack list");
            activeDisplay = DesktopRuntimeBridge.getActiveDesktopDisplayId();
            target = DesktopRuntimeBridge.getDesktopTarget(displayId);
            desktop = findTaskOnAnyDisplay(
                    stack, DesktopSelfTestComponents.DESKTOP_CLASS);
            fullscreenAreaPresent = hasFullscreenTaskArea();
            if (activeDisplay != displayId
                    && target == null
                    && desktop == null
                    && !fullscreenAreaPresent) {
                return "display=" + displayId
                        + ", runtime=inactive, task-area=removed";
            }
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("runtime cleanup incomplete: active="
                + activeDisplay + ", target=" + target
                + ", desktop="
                + (desktop == null ? "absent" : desktop.taskId)
                + ", taskArea=" + fullscreenAreaPresent);
    }

    private static boolean hasFullscreenTaskArea() throws IOException {
        final String matches = ShellAccess.run(
                "/system/bin/dumpsys activity containers"
                        + " | /system/bin/toybox grep -F '"
                        + FULLSCREEN_AREA_NAME + "' || true");
        return !matches.trim().isEmpty();
    }

    private static String waitForSafeTaskMigration(
            final int removedDisplayId,
            final Set<Integer> taskIds) throws IOException {
        final long deadline = SystemClock.uptimeMillis()
                + STEP_TIMEOUT_MILLIS;
        String lastState = "unavailable";
        do {
            final String stack = ShellAccess.run(
                    "/system/bin/cmd activity stack list");
            boolean settled = true;
            int phoneFullscreen = 0;
            final StringBuilder state = new StringBuilder();
            for (final Integer taskId : taskIds) {
                final TaskStackParser.Entry task = findTaskById(
                        stack, taskId.intValue());
                if (task == null) {
                    state.append(taskId).append("=removed,");
                    continue;
                }
                state.append(taskId).append("=display")
                        .append(task.displayId).append('/')
                        .append(task.windowingMode).append(',');
                if (task.displayId == removedDisplayId
                        || task.displayId != Display.DEFAULT_DISPLAY
                        || !"fullscreen".equals(task.windowingMode)) {
                    settled = false;
                } else {
                    phoneFullscreen++;
                }
            }
            for (final TaskStackParser.Entry task
                    : TaskStackParser.parse(stack)) {
                if (task.displayId == Display.DEFAULT_DISPLAY
                        && DesktopSelfTestComponents.isFixtureTask(task)
                        && !"fullscreen".equals(task.windowingMode)) {
                    settled = false;
                    state.append("phone-fixture=")
                            .append(task.taskId).append('/')
                            .append(task.windowingMode).append(',');
                }
            }
            lastState = state.length() == 0
                    ? "all removed" : state.toString();
            if (settled) {
                return lastState + " phoneFullscreen=" + phoneFullscreen;
            }
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("unsafe migrated task state: " + lastState);
    }
}

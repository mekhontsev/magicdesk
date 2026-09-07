package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopSelfTestSteps.usefulMessage;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.POLL_MILLIS;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.STEP_TIMEOUT_MILLIS;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.findDesktopTaskOnAnyDisplay;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.findTaskOnAnyDisplay;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.waitForTask;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.Display;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Restores all state owned by a desktop self-test run. */
final class DesktopSelfTestCleanup {
    // Includes the coordinator's input, task, host and display teardown stages.
    private static final long CLOSE_TIMEOUT_SECONDS = 60;

    private DesktopSelfTestCleanup() {
    }

    static void run(
            final DesktopSelfTestResult result,
            final DesktopSelfTestTarget target,
            final int displayId,
            final SimulatedDisplayLease lease) {
        final StringBuilder detail = new StringBuilder();
        final Set<Integer> phoneFixtureTaskIds = new LinkedHashSet<>();
        final Set<Integer> ownedFixtureTaskIds = new LinkedHashSet<>();
        boolean clean = true;
        boolean sessionClosed = displayId < Display.DEFAULT_DISPLAY;
        // Display loss has its own production destination: the phone panel.
        // Determine it from lifecycle state, never from whichever window is visible.
        final boolean displayAlreadyRemoved = displayId > Display.DEFAULT_DISPLAY
                && !ExternalDisplayController.displayExists(displayId);
        // Close fixtures while their production task/plane owner is still alive.
        // Removing them after HOME presentation can let SystemUI replace HOME.
        try {
            requireShell();
            ownedFixtureTaskIds.addAll(captureFixtureTaskIds());
            removeFixtureTasks(phoneFixtureTaskIds, ownedFixtureTaskIds);
            waitForTaskAbsent(DesktopSelfTestComponents.FIXTURE_CLASS);
            waitForTaskAbsent(DesktopSelfTestComponents.BROWSER_FIXTURE_CLASS);
        } catch (IOException error) {
            clean = false;
            detail.append("fixture removal: ")
                    .append(usefulMessage(error)).append("; ");
        }
        if (displayId >= Display.DEFAULT_DISPLAY) {
            try {
                closeDesktopSessionAndWait(displayId);
                sessionClosed = true;
                requireShell();
                waitForDesktopTaskAbsent();
                if (target == DesktopSelfTestTarget.PHONE) {
                    waitForLocalDesktopCleanup();
                }
            } catch (IOException error) {
                clean = false;
                detail.append("desktop task quiescence: ")
                        .append(usefulMessage(error)).append("; ");
            }
        }
        if (displayId >= Display.DEFAULT_DISPLAY
                && (target == DesktopSelfTestTarget.PHONE
                        || target == DesktopSelfTestTarget.SIMULATED)) {
            try {
                requireShell();
                if (target == DesktopSelfTestTarget.PHONE) {
                    waitForNoLiveDesktopTasks(displayId);
                } else {
                    waitForDesktopRepositoryEmpty(displayId);
                }
            } catch (IOException error) {
                clean = false;
                detail.append("desktop repository cleanup: ")
                        .append(usefulMessage(error)).append("; ");
            }
        }
        if (lease != null) {
            try {
                if (!sessionClosed) {
                    throw new IOException("desktop session close did not complete");
                }
                if (displayId > Display.DEFAULT_DISPLAY
                        && !WindowTransitionHealthDiagnostics
                                .awaitDisplayIdle(
                                        MagicDeskApplication
                                                .applicationContext(),
                                        displayId,
                                        STEP_TIMEOUT_MILLIS)
                                .idle) {
                    throw new IOException(
                            "window transitions remained active on display "
                                    + displayId);
                }
                lease.close();
            } catch (IOException error) {
                clean = false;
                detail.append("display lease: ")
                        .append(usefulMessage(error)).append("; ");
            }
        }
        if (target == DesktopSelfTestTarget.SIMULATED
                && lease != null
                && displayId > Display.DEFAULT_DISPLAY) {
            final long deadline = SystemClock.uptimeMillis()
                    + STEP_TIMEOUT_MILLIS;
            boolean removed = false;
            do {
                if (!ExternalDisplayController.displayExists(displayId)) {
                    removed = true;
                    break;
                }
                BoundedStateAwaiter.pause(
                        BoundedStateAwaiter.Reason.DISPLAY_STATE,
                        POLL_MILLIS);
            } while (SystemClock.uptimeMillis() < deadline);
            if (!removed) {
                clean = false;
                detail.append("display ").append(displayId)
                        .append(" remained; ");
            }
            if (ShellAccess.isReady()) {
                try {
                    waitForDesktopRepositoryEmpty(displayId);
                } catch (IOException error) {
                    clean = false;
                    detail.append("desktop repository cleanup: ")
                            .append(usefulMessage(error)).append("; ");
                }
            } else {
                clean = false;
                detail.append("desktop repository verification unavailable; ");
            }
        }
        if (ShellAccess.isReady()) {
            try {
                if (target == DesktopSelfTestTarget.PHONE) {
                    for (final Integer taskId : phoneFixtureTaskIds) {
                        waitForTaskAbsentFromDesktopRepository(
                                Display.DEFAULT_DISPLAY,
                                taskId.intValue());
                    }
                }
            } catch (IOException error) {
                clean = false;
                detail.append("fixture repository verification: ")
                        .append(usefulMessage(error)).append("; ");
            }
            if (target == DesktopSelfTestTarget.SIMULATED
                    && lease != null) {
                try {
                    final String configured = ShellAccess.run(
                            "/system/bin/settings get global "
                                    + SimulatedDisplayLease.SETTING).trim();
                    if (!configured.isEmpty() && !"null".equals(configured)) {
                        clean = false;
                        detail.append(SimulatedDisplayLease.SETTING).append('=')
                                .append(configured).append("; ");
                    }
                } catch (IOException error) {
                    clean = false;
                    detail.append("setting verification: ")
                            .append(usefulMessage(error)).append("; ");
                }
            }
        } else {
            clean = false;
            detail.append("shell unavailable for final cleanup verification; ");
        }
        result.add(clean ? DesktopSelfTestResult.State.PASS
                        : DesktopSelfTestResult.State.FAIL,
                "CLEANUP-001", "Restore self-test environment",
                clean ? "target=" + target + ", complete" : detail.toString());
        if (displayId >= Display.DEFAULT_DISPLAY) {
            verifyPhoneDestination(result, displayAlreadyRemoved);
        }
    }

    static void removeFixtureTasks() throws IOException {
        requireShell();
        removeFixtureTasks(null, captureFixtureTaskIds());
    }

    private static void closeFixture(final TaskRepository.TaskEntry task)
            throws IOException {
        final CountDownLatch completion = new CountDownLatch(1);
        final TaskRepository.ActionResult[] action =
                new TaskRepository.ActionResult[1];
        MagicDeskRuntime.closeTask(task, result -> {
            action[0] = result;
            completion.countDown();
        });
        try {
            if (!completion.await(
                    STEP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new IOException(
                        "fixture task close timed out: " + task.taskId);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException(
                    "fixture task close interrupted: " + task.taskId, error);
        }
        if (action[0] == null || !action[0].success) {
            throw new IOException("fixture task close failed: " + task.taskId
                    + (action[0] == null || action[0].message.isEmpty()
                            ? "" : ": " + action[0].message));
        }
        requireShell();
        DesktopSelfTestTasks.waitForTaskAbsent(task.taskId);
    }

    private static Set<Integer> captureFixtureTaskIds() throws IOException {
        final TaskRepository.Snapshot snapshot = TaskRepository.loadAllNow();
        if (!snapshot.available) {
            throw new IOException(snapshot.error);
        }
        final Set<Integer> taskIds = new LinkedHashSet<>();
        for (final TaskRepository.TaskEntry task : snapshot.tasks) {
            if (DesktopSelfTestComponents.isFixtureTask(task)) {
                taskIds.add(Integer.valueOf(task.taskId));
            }
        }
        return taskIds;
    }

    private static void removeFixtureTasks(
            final Set<Integer> phoneFixtureTaskIds,
            final Set<Integer> ownedTaskIds) throws IOException {
        final TaskRepository.Snapshot snapshot = TaskRepository.loadAllNow();
        if (!snapshot.available) {
            throw new IOException(snapshot.error);
        }
        for (final TaskRepository.TaskEntry task : snapshot.tasks) {
            if (!ownedTaskIds.contains(Integer.valueOf(task.taskId))
                    || !DesktopSelfTestComponents.isFixtureTask(task)) {
                continue;
            }
            if (phoneFixtureTaskIds != null
                    && task.displayId == Display.DEFAULT_DISPLAY) {
                phoneFixtureTaskIds.add(Integer.valueOf(task.taskId));
            }
            if (!DesktopRuntimeBridge.isLocalDesktopActiveOrStarting()
                    && requiresPhoneDesktopExitBeforeRemoval(
                    task.displayId, task.windowingMode,
                    PlatformDrivers.current().windowing()
                            .requiresPhoneTaskRecovery())) {
                // Removing a phone freeform task directly leaves its ID in
                // Nubia's DesktopRepository. Leave the desk before removal.
                ShellAccess.run(
                        TaskRepository
                                .createClientPreservingFullscreenTransitionCommand(
                                        task.displayId, task.taskId));
                waitForTask(
                        task.displayId,
                        fixtureClass(task),
                        entry -> entry.taskId == task.taskId
                                && "fullscreen".equals(
                                        entry.windowingMode));
                waitForTaskAbsentFromDesktopRepository(
                        task.displayId, task.taskId);
            }
            closeFixture(task);
            if (task.displayId == Display.DEFAULT_DISPLAY) {
                waitForTaskAbsentFromDesktopRepository(
                        task.displayId, task.taskId);
            }
        }
    }

    private static String fixtureClass(
            final TaskRepository.TaskEntry task) {
        return DesktopSelfTestTasks.hasClass(
                task.componentName,
                DesktopSelfTestComponents.BROWSER_FIXTURE_CLASS)
                || DesktopSelfTestTasks.hasClass(
                        task.topActivityName,
                        DesktopSelfTestComponents.BROWSER_FIXTURE_CLASS)
                ? DesktopSelfTestComponents.BROWSER_FIXTURE_CLASS
                : DesktopSelfTestComponents.FIXTURE_CLASS;
    }

    static boolean requiresPhoneDesktopExitBeforeRemoval(
            final TaskStackParser.Entry task,
            final boolean requiresPhoneTaskRecovery) {
        return requiresPhoneTaskRecovery
                && task != null
                && requiresPhoneDesktopExitBeforeRemoval(
                        task.displayId, task.windowingMode, true);
    }

    private static boolean requiresPhoneDesktopExitBeforeRemoval(
            final int displayId,
            final String windowingMode,
            final boolean requiresPhoneTaskRecovery) {
        return requiresPhoneTaskRecovery && displayId == Display.DEFAULT_DISPLAY
                && "freeform".equals(windowingMode);
    }

    private static void requireShell() throws IOException {
        if (!ShellAccess.isReady()) {
            throw new IOException("shell unavailable for required cleanup verification");
        }
    }

    private static void closeDesktopSessionAndWait(final int displayId) throws IOException {
        final DesktopHomeRoleLease.State home = DesktopHomeRoleLease.snapshot();
        final DesktopDisplayTarget active = DesktopRuntimeBridge.getDesktopTarget(displayId);
        if (home != null && home.displayId != displayId) {
            throw new IOException("HOME lease belongs to display " + home.displayId);
        }
        final DesktopDisplayTarget target = active != null ? active
                : home != null ? home.target() : null;
        if (target == null) {
            // The display-removal suite may already have completed production teardown.
            return;
        }
        final CountDownLatch closed = new CountDownLatch(1);
        final boolean[] success = {false};
        DesktopOperations.closeDesktop(target, DesktopCloseMode.HOME, value -> {
            success[0] = value;
            closed.countDown();
        });
        try {
            if (!closed.await(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("desktop session close timed out");
            }
            if (!success[0]) {
                throw new IOException("production desktop close failed");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("desktop session close interrupted", error);
        }
    }

    private static void verifyPhoneDestination(
            final DesktopSelfTestResult result, final boolean displayRemoved) {
        final String code = "CLEANUP-HOME-001";
        final String title = "Restore Home role and phone destination after cleanup";
        try {
            requireShell();
            if (DesktopHomeRoleLease.snapshot() != null
                    || DesktopRuntimeBridge.isLocalDesktopActiveOrStarting()) {
                throw new IOException("desktop or HOME lease remained active");
            }
            final AndroidActivityResolution home = ShellAccess.resolveActivity(
                    new Intent(Intent.ACTION_MAIN)
                            .addCategory(Intent.CATEGORY_HOME)
                            .addCategory(Intent.CATEGORY_DEFAULT));
            if (!displayRemoved && home.state == AndroidActivityResolution.RESOLVER) {
                result.add(DesktopSelfTestResult.State.NOT_TESTED, code, title,
                        "Android requires the user to select a Home app");
                return;
            }
            if (home.state == AndroidActivityResolution.NONE
                    || home.component != null && BuildConfig.APPLICATION_ID.equals(
                            home.component.getPackageName())) {
                throw new IOException("restored HOME resolution is " + home.component);
            }
            final ComponentName destination = displayRemoved
                    ? new ComponentName(BuildConfig.APPLICATION_ID,
                            ControlActivity.class.getName())
                    : home.component;
            waitForPhoneDestination(destination, !displayRemoved);
            result.add(DesktopSelfTestResult.State.PASS, code, title,
                    "display=0 destination=" + (displayRemoved ? "control-panel" : "home")
                            + " component=" + destination.flattenToShortString());
        } catch (IOException error) {
            result.add(DesktopSelfTestResult.State.FAIL, code, title,
                    usefulMessage(error));
        }
    }

    private static void waitForPhoneDestination(
            final ComponentName expected, final boolean home)
            throws IOException {
        final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
        String last = "none";
        do {
            // TaskRepository.active ranks applications and deliberately excludes HOME.
            // This assertion needs Android's actual focus, including launcher tasks.
            final FrameworkTaskSnapshot[] tasks =
                    ShellAccess.readTaskSnapshots(Display.DEFAULT_DISPLAY, 200);
            for (final FrameworkTaskSnapshot task : tasks) {
                if (task.focused && task.visible) {
                    last = task.topActivityName;
                    if (isExpectedPhoneDestination(task, expected, home)) {
                        return;
                    }
                }
            }
            BoundedStateAwaiter.pause(
                    BoundedStateAwaiter.Reason.TASK_VISIBILITY, POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("expected phone destination="
                + expected.flattenToShortString() + "; foreground=" + last);
    }

    static boolean isExpectedPhoneDestination(
            final FrameworkTaskSnapshot task, final ComponentName expected,
            final boolean home) {
        return task.displayId == Display.DEFAULT_DISPLAY
                && task.isHome() == home && task.visible && task.focused
                && expected.equals(task.topComponent);
    }

    private static void waitForLocalDesktopCleanup() throws IOException {
        final Context context = MagicDeskApplication.applicationContext();
        final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
        do {
            if (!LocalDesktopSessionState.isCleanupPending(context)) {
                return;
            }
            BoundedStateAwaiter.pause(
                    BoundedStateAwaiter.Reason.TASK_HIERARCHY,
                    POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("phone desktop cleanup did not complete");
    }

    private static void waitForTaskAbsent(final String className)
            throws IOException {
        final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
        do {
            final TaskStackParser.Entry task = findTaskOnAnyDisplay(
                    ShellAccess.run("/system/bin/cmd activity stack list"),
                    className);
            if (task == null) {
                return;
            }
            BoundedStateAwaiter.pause(
                    BoundedStateAwaiter.Reason.TASK_VISIBILITY,
                    POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("task " + className + " remained after close");
    }

    private static void waitForDesktopTaskAbsent() throws IOException {
        final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
        do {
            final TaskStackParser.Entry task = findDesktopTaskOnAnyDisplay(
                    ShellAccess.run("/system/bin/cmd activity stack list"));
            if (task == null) {
                return;
            }
            BoundedStateAwaiter.pause(
                    BoundedStateAwaiter.Reason.TASK_VISIBILITY,
                    POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("desktop task remained after close");
    }

    private static void waitForDesktopRepositoryEmpty(
            final int displayId) throws IOException {
        final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
        Set<Integer> retainedTasks = Collections.emptySet();
        do {
            final String repository = ShellAccess.run(
                    PhoneDesktopTaskRecovery.repositoryDumpCommand());
            retainedTasks = SystemUiDesktopRepositoryParser.parseTaskIds(
                    repository, displayId);
            if (retainedTasks.isEmpty()) {
                return;
            }
            BoundedStateAwaiter.pause(
                    BoundedStateAwaiter.Reason.TASK_HIERARCHY,
                    POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("SystemUI retained tasks for display "
                + displayId + ": " + retainedTasks);
    }

    private static void waitForNoLiveDesktopTasks(
            final int displayId) throws IOException {
        final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
        Set<Integer> retainedTasks = Collections.emptySet();
        do {
            retainedTasks = new LinkedHashSet<>(
                    SystemUiDesktopRepositoryParser.parseTaskIds(
                            ShellAccess.run(
                                    PhoneDesktopTaskRecovery
                                            .repositoryDumpCommand()),
                            displayId));
            final Set<Integer> liveFreeformTasks = new LinkedHashSet<>();
            for (final TaskStackParser.Entry task : TaskStackParser.parse(
                    ShellAccess.run(
                            "/system/bin/cmd activity stack list"))) {
                if (task.displayId == displayId
                        && "freeform".equals(task.windowingMode)) {
                    liveFreeformTasks.add(Integer.valueOf(task.taskId));
                }
            }
            retainedTasks.retainAll(liveFreeformTasks);
            if (retainedTasks.isEmpty()) {
                return;
            }
            BoundedStateAwaiter.pause(
                    BoundedStateAwaiter.Reason.TASK_HIERARCHY,
                    POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("SystemUI retained live desktop tasks for display "
                + displayId + ": " + retainedTasks);
    }

    private static void waitForTaskAbsentFromDesktopRepository(
            final int displayId,
            final int taskId) throws IOException {
        final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
        do {
            final Set<Integer> taskIds =
                    SystemUiDesktopRepositoryParser.parseTaskIds(
                            ShellAccess.run(
                                    PhoneDesktopTaskRecovery
                                            .repositoryDumpCommand()),
                            displayId);
            if (!taskIds.contains(Integer.valueOf(taskId))) {
                return;
            }
            BoundedStateAwaiter.pause(
                    BoundedStateAwaiter.Reason.TASK_HIERARCHY,
                    POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("SystemUI retained task " + taskId
                + " for display " + displayId);
    }
}

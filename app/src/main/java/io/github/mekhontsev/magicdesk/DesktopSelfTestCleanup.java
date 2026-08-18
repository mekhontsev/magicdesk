package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopSelfTestSteps.usefulMessage;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.POLL_MILLIS;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.STEP_TIMEOUT_MILLIS;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.findTask;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.findTaskOnAnyDisplay;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.waitForTask;

import android.content.Context;
import android.os.SystemClock;
import android.view.Display;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Restores all state owned by a desktop self-test run. */
final class DesktopSelfTestCleanup {
    private DesktopSelfTestCleanup() {
    }

    static void run(
            final DesktopSelfTestResult result,
            final DesktopSelfTestTarget target,
            final int displayId,
            final SimulatedDisplayLease lease,
            final boolean restoreExternalMirror) {
        final StringBuilder detail = new StringBuilder();
        boolean clean = true;
        if (ShellAccess.isReady()) {
            try {
                removeFixtureTasks();
                waitForTaskAbsent(DesktopSelfTestComponents.FIXTURE_CLASS);
                waitForTaskAbsent(
                        DesktopSelfTestComponents.BROWSER_FIXTURE_CLASS);
            } catch (IOException error) {
                clean = false;
                detail.append("fixture removal: ")
                        .append(usefulMessage(error)).append("; ");
            }
        }
        if (displayId >= Display.DEFAULT_DISPLAY) {
            final DesktopDisplayTarget displayTarget =
                    DesktopRuntimeBridge.getDesktopTarget(displayId);
            if (displayTarget != null
                    && DesktopDisplayDrivers.forTarget(displayTarget)
                            .features().phoneTouchpad) {
                PhoneTouchpadController.release(displayId);
            }
            DesktopRuntimeBridge.closeDesktopSession(displayId);
            if (ShellAccess.isReady()) {
                try {
                    waitForTaskAbsent(DesktopSelfTestComponents.DESKTOP_CLASS);
                    if (target == DesktopSelfTestTarget.PHONE) {
                        waitForLocalDesktopCleanup();
                    }
                } catch (IOException error) {
                    clean = false;
                    detail.append("desktop task quiescence: ")
                            .append(usefulMessage(error)).append("; ");
                }
            }
        }
        if (ShellAccess.isReady()
                && displayId >= Display.DEFAULT_DISPLAY
                && (target == DesktopSelfTestTarget.PHONE
                        || target == DesktopSelfTestTarget.SIMULATED)) {
            try {
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
        if (target == DesktopSelfTestTarget.EXTERNAL
                && restoreExternalMirror) {
            try {
                restoreMirrorMode();
            } catch (IOException error) {
                clean = false;
                detail.append("mirror restore: ")
                        .append(usefulMessage(error)).append("; ");
            }
        }
        if (lease != null) {
            try {
                lease.close();
            } catch (IOException error) {
                clean = false;
                detail.append("display lease: ")
                        .append(usefulMessage(error)).append("; ");
            }
        }
        if (target == DesktopSelfTestTarget.SIMULATED
                && displayId > Display.DEFAULT_DISPLAY) {
            final long deadline = SystemClock.uptimeMillis()
                    + STEP_TIMEOUT_MILLIS;
            boolean removed = false;
            do {
                if (!ConsoleDisplayController.displayExists(displayId)) {
                    removed = true;
                    break;
                }
                SystemClock.sleep(POLL_MILLIS);
            } while (SystemClock.uptimeMillis() < deadline);
            if (!removed) {
                clean = false;
                detail.append("display ").append(displayId)
                        .append(" remained; ");
            }
            if (ShellAccess.isReady()) {
                final String secondaryHomeClass = PhoneHomeComponents.resolve(
                        MagicDeskApplication.applicationContext())
                        .firstSecondaryClassName();
                if (!secondaryHomeClass.isEmpty()) {
                    try {
                        waitForTaskAbsentOnDisplay(
                                Display.DEFAULT_DISPLAY,
                                secondaryHomeClass);
                    } catch (IOException error) {
                        clean = false;
                        detail.append("phone launcher cleanup: ")
                                .append(usefulMessage(error)).append("; ");
                    }
                }
                try {
                    waitForDesktopRepositoryEmpty(displayId);
                } catch (IOException error) {
                    clean = false;
                    detail.append("desktop repository cleanup: ")
                            .append(usefulMessage(error)).append("; ");
                }
            }
        }
        if (ShellAccess.isReady()) {
            try {
                removeFixtureTasks();
            } catch (IOException error) {
                clean = false;
                detail.append("stale fixture cleanup: ")
                        .append(usefulMessage(error)).append("; ");
            }
            if (target == DesktopSelfTestTarget.SIMULATED) {
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
        }
        result.add(clean ? DesktopSelfTestResult.State.PASS
                        : DesktopSelfTestResult.State.FAIL,
                "CLEANUP-001", "Restore self-test environment",
                clean ? "target=" + target + ", complete" : detail.toString());
    }

    static void removeFixtureTasks() throws IOException {
        final String stack = ShellAccess.run(
                "/system/bin/cmd activity stack list");
        for (final TaskStackParser.Entry task : TaskStackParser.parse(stack)) {
            if (!DesktopSelfTestComponents.isFixtureTask(task)) {
                continue;
            }
            if (requiresPhoneDesktopExitBeforeRemoval(
                    task,
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
            try {
                ShellAccess.run(AppProcessCommand.run(
                        "io.github.mekhontsev.magicdesk.TaskControlCommand",
                        "remove " + task.taskId));
            } catch (IOException error) {
                if (taskExists(task.taskId)) {
                    throw error;
                }
            }
            if (task.displayId == Display.DEFAULT_DISPLAY) {
                waitForTaskAbsentFromDesktopRepository(
                        task.displayId, task.taskId);
            }
        }
    }

    private static String fixtureClass(
            final TaskStackParser.Entry task) {
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
                && task.displayId == Display.DEFAULT_DISPLAY
                && "freeform".equals(task.windowingMode);
    }

    private static boolean taskExists(final int taskId) throws IOException {
        final String stack = ShellAccess.run(
                "/system/bin/cmd activity stack list");
        for (final TaskStackParser.Entry task : TaskStackParser.parse(stack)) {
            if (task.taskId == taskId) {
                return true;
            }
        }
        return false;
    }

    private static void waitForLocalDesktopCleanup() throws IOException {
        final Context context = MagicDeskApplication.applicationContext();
        final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
        do {
            if (!LocalDesktopSessionState.isCleanupPending(context)) {
                return;
            }
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("phone desktop cleanup did not complete");
    }

    private static void restoreMirrorMode() throws IOException {
        final CountDownLatch complete = new CountDownLatch(1);
        final AtomicBoolean success = new AtomicBoolean();
        ConsoleModeSwitcher.switchToMirror(restored -> {
            success.set(restored);
            complete.countDown();
        });
        try {
            if (!complete.await(
                    STEP_TIMEOUT_MILLIS * 2L, TimeUnit.MILLISECONDS)) {
                throw new IOException("mirror restore timed out");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("mirror restore interrupted", error);
        }
        if (!success.get()) {
            throw new IOException("mirror restore failed");
        }
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
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("task " + className + " remained after close");
    }

    private static void waitForTaskAbsentOnDisplay(
            final int displayId,
            final String className) throws IOException {
        final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
        do {
            final TaskStackParser.Entry task = findTask(
                    ShellAccess.run("/system/bin/cmd activity stack list"),
                    displayId,
                    className);
            if (task == null) {
                return;
            }
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("task " + className
                + " remained on display " + displayId + " after close");
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
            SystemClock.sleep(POLL_MILLIS);
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
            SystemClock.sleep(POLL_MILLIS);
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
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("SystemUI retained task " + taskId
                + " for display " + displayId);
    }
}

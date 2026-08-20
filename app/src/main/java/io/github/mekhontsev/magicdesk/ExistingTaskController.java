package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class ExistingTaskController {
    private static final String TAG = "MagicDeskTaskReuse";
    private static final String CMD = "/system/bin/cmd";
    private static final String MODE_FULLSCREEN = "fullscreen";
    private static final String MODE_FREEFORM = "freeform";
    private static final long TASK_APPEAR_TIMEOUT_MILLIS = 6000;
    private static final long TASK_STATE_TIMEOUT_MILLIS = 6000;
    private static final long TASK_STATE_POLL_MILLIS = 100;

    private ExistingTaskController() {
    }

    static ReuseResult reuseIfExists(final AppLaunchTarget target,
            final int targetDisplayId,
            final boolean targetFreeform) throws IOException {
        return reuseIfExists(target, targetDisplayId, targetFreeform,
                null, false, false, false, null, null);
    }

    static ReuseResult normalizeLaunchedFullscreen(
            final AppLaunchTarget target,
            final int targetDisplayId) throws IOException {
        TaskInfo task = waitForBestTask(
                target, targetDisplayId, false);
        if (task == null) {
            throw new IOException(
                    "launched task not found for " + target.packageName);
        }

        Log.i(TAG, "normalize launched fullscreen package="
                + target.packageName
                + " task=" + task.taskId
                + " display=" + task.displayId
                + " mode=" + task.windowingMode
                + " targetDisplay=" + targetDisplayId);
        if (task.displayId != targetDisplayId) {
            final String command = TaskFullscreenMoveCommand.createMoveCommand(
                    task.taskId,
                    task.rootTaskId,
                    task.displayId,
                    targetDisplayId);
            runCommand(command);
            waitForTaskDisplay(task.taskId, targetDisplayId);
            final TaskInfo movedTask = findTask(task.taskId);
            if (movedTask == null) {
                throw new IOException(
                        "moved task " + task.taskId + " is unavailable");
            }
            task = movedTask;
        } else {
            setFullscreen(task, targetDisplayId);
        }
        bringTaskStackToFrontBestEffort(task, null);
        return ReuseResult.reused(task.taskId, task.packageName);
    }

    static ReuseResult reuseNativeDesktopIfExists(
            final AppLaunchTarget target,
            final int targetDisplayId, final int[] preservedTopFirstTaskIds,
            final boolean waitForTask,
            final boolean explicitWindowed,
            final Rect targetBounds,
            final WindowedTaskLaunchLease launchLease) throws IOException {
        return reuseIfExists(target, targetDisplayId, true,
                preservedTopFirstTaskIds, true, waitForTask,
                explicitWindowed, targetBounds, launchLease);
    }

    static ReuseResult reuseFreeformIfExists(
            final AppLaunchTarget target,
            final int targetDisplayId, final int[] preservedTopFirstTaskIds,
            final boolean waitForTask,
            final boolean explicitWindowed,
            final Rect targetBounds,
            final WindowedTaskLaunchLease launchLease) throws IOException {
        return reuseIfExists(target, targetDisplayId, true,
                preservedTopFirstTaskIds, false, waitForTask,
                explicitWindowed, targetBounds, launchLease);
    }

    static boolean taskExists(final String packageName, final int targetDisplayId)
            throws IOException {
        return findBestTask(
                AppLaunchTarget.packageDefault(packageName),
                targetDisplayId,
                true) != null;
    }

    static void waitForNativeDesktopTask(final int taskId, final int displayId)
            throws IOException {
        waitForTaskState(taskId, displayId, MODE_FREEFORM);
    }

    static void confirmLaunchedWindow(
            final int taskId,
            final int displayId,
            final int[] preservedTopFirstTaskIds) throws IOException {
        waitForTaskState(taskId, displayId, MODE_FREEFORM);
        final TaskInfo task = findTask(taskId);
        if (task == null) {
            throw new IOException("launched task " + taskId
                    + " is unavailable");
        }
        setCaptionInsetExcluded(taskId, displayId, false);
        bringTaskStackToFrontBestEffort(task, preservedTopFirstTaskIds);
    }

    private static ReuseResult reuseIfExists(final AppLaunchTarget target,
            final int targetDisplayId, final boolean targetFreeform,
            final int[] preservedTopFirstTaskIds, final boolean nativeDesktop,
            final boolean waitForTask,
            final boolean explicitWindowed,
            final Rect targetBounds,
            final WindowedTaskLaunchLease outerLaunchLease) throws IOException {
        TaskInfo task = waitForTask
                ? waitForBestTask(target, targetDisplayId, targetFreeform)
                : findBestTask(target, targetDisplayId, targetFreeform);
        if (task == null) {
            Log.i(TAG, "no existing task package=" + target.packageName);
            return ReuseResult.notFound();
        }

        final WindowedTaskLaunchLease launchLease =
                outerLaunchLease == null
                        ? WindowedTaskLaunchLease.acquire()
                        : outerLaunchLease;
        try {
            if (targetFreeform && waitForTask && explicitWindowed) {
                launchLease.protectStartupTask(task.taskId);
            } else if (targetFreeform) {
                launchLease.noteFreeformTask(task.taskId);
            }
            Log.i(TAG, "found package=" + target.packageName
                    + " rootTask=" + task.rootTaskId
                    + " task=" + task.taskId
                    + " display=" + task.displayId
                    + " mode=" + task.windowingMode
                    + " targetDisplay=" + targetDisplayId
                    + " targetFreeform=" + targetFreeform
                    + " nativeDesktop=" + nativeDesktop);
            if (nativeDesktop) {
                NativeDesktopController.requireAvailable();
            }
            boolean taskIsFreeform =
                    MODE_FREEFORM.equals(task.windowingMode);
            boolean taskIsFullscreen =
                    MODE_FULLSCREEN.equals(task.windowingMode);
            boolean movedAsFreeform = false;
            boolean movedDisplay = false;
            final boolean sessionTaskArea =
                    DesktopDisplayDrivers.activeTaskAreaPolicy(
                            targetDisplayId)
                            == DesktopTaskAreaPolicy.SESSION;
            if (sessionTaskArea) {
                final int sourceDisplayId = task.displayId;
                if (targetFreeform) {
                    final Rect bounds = resolveTargetBounds(
                            targetDisplayId, targetBounds);
                    MagicDeskRuntime.placeTaskInDesktopArea(
                            task.taskId,
                            sourceDisplayId,
                            targetDisplayId,
                            bounds);
                    movedAsFreeform = true;
                } else {
                    MagicDeskRuntime.placeFullscreenTaskInDesktopArea(
                            task.taskId,
                            sourceDisplayId,
                            targetDisplayId);
                }
                movedDisplay = sourceDisplayId != targetDisplayId;
                final TaskInfo movedTask = findTask(task.taskId);
                if (movedTask == null) {
                    throw new IOException(
                            "placed task " + task.taskId
                                    + " is unavailable");
                }
                task = movedTask;
                taskIsFreeform = MODE_FREEFORM.equals(task.windowingMode);
                taskIsFullscreen = MODE_FULLSCREEN.equals(task.windowingMode);
            } else if (task.displayId != targetDisplayId) {
                final String command;
                final DesktopDisplayDriver targetDriver =
                        DesktopDisplayDrivers.forActiveDisplay(
                                targetDisplayId);
                if (targetFreeform) {
                    final Rect bounds = resolveTargetBounds(
                            targetDisplayId, targetBounds);
                    command = targetDriver.features().rootTaskTransfer
                            ? TaskDisplayAreaLaunchCommand
                                    .createRootTaskMoveCommand(
                                            task.taskId,
                                            task.rootTaskId,
                                            task.displayId,
                                            targetDisplayId,
                                            bounds)
                            : TaskDisplayAreaLaunchCommand.createMoveCommand(
                                    task.taskId,
                                    task.displayId,
                                    targetDisplayId,
                                    bounds);
                    movedAsFreeform = true;
                } else {
                    command = TaskFullscreenMoveCommand.createMoveCommand(
                            task.taskId,
                            task.rootTaskId,
                            task.displayId,
                            targetDisplayId);
                }
                Log.i(TAG, "move display: " + command);
                final String output = runCommand(command);
                if (movedAsFreeform
                        && !output.contains(
                                "task-freeform-move=" + task.taskId)) {
                    throw new IOException(output.trim());
                }
                waitForTaskDisplay(task.taskId, targetDisplayId);
                movedDisplay = true;
                final TaskInfo movedTask = findTask(task.taskId);
                if (movedTask == null) {
                    throw new IOException(
                            "moved task " + task.taskId
                                    + " is unavailable");
                }
                task = movedTask;
                taskIsFreeform =
                        MODE_FREEFORM.equals(task.windowingMode);
                taskIsFullscreen =
                        MODE_FULLSCREEN.equals(task.windowingMode);
            }

            if (nativeDesktop) {
                if (!taskIsFreeform && !movedAsFreeform) {
                    NativeDesktopController.moveTaskToDesktop(task.taskId);
                    waitForTaskState(task.taskId, targetDisplayId, MODE_FREEFORM);
                }
                if (targetBounds != null
                        && !movedAsFreeform
                        && (!taskIsFreeform || movedDisplay)) {
                    setBounds(targetDisplayId, task.taskId, targetBounds);
                }
                setCaptionInsetExcluded(task.taskId, targetDisplayId, false);
            } else if (targetFreeform
                    && taskIsFullscreen
                    && !movedAsFreeform) {
                Log.i(TAG, "convert fullscreen to freeform task=" + task.taskId);
                setFreeform(task.taskId, targetDisplayId, targetBounds);
                waitForTaskState(task.taskId, targetDisplayId, MODE_FREEFORM);
            } else if (!targetFreeform && taskIsFreeform) {
                Log.i(TAG, "convert freeform to fullscreen task=" + task.taskId);
                setFullscreen(task, targetDisplayId);
            } else {
                setCaptionInsetExcluded(task.taskId, targetDisplayId,
                        !targetFreeform);
            }

            bringTaskStackToFrontBestEffort(task, preservedTopFirstTaskIds);
            return ReuseResult.reused(task.taskId, task.packageName);
        } finally {
            if (outerLaunchLease == null) {
                launchLease.close();
            }
        }
    }

    private static TaskInfo waitForBestTask(final AppLaunchTarget target,
            final int targetDisplayId, final boolean targetFreeform) throws IOException {
        final long deadline = SystemClock.uptimeMillis() + TASK_APPEAR_TIMEOUT_MILLIS;
        TaskInfo task;
        do {
            task = findBestTask(target, targetDisplayId, targetFreeform);
            if (task != null && task.visible) {
                return task;
            }
            SystemClock.sleep(TASK_STATE_POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        return null;
    }

    private static void waitForTaskDisplay(final int taskId, final int displayId)
            throws IOException {
        final long deadline = SystemClock.uptimeMillis() + TASK_STATE_TIMEOUT_MILLIS;
        TaskInfo task;
        do {
            task = findTask(taskId);
            if (task != null && task.displayId == displayId) {
                return;
            }
            SystemClock.sleep(TASK_STATE_POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("task " + taskId
                + " did not move to display " + displayId);
    }

    private static void waitForTaskState(final int taskId, final int displayId,
            final String windowingMode) throws IOException {
        final long deadline = SystemClock.uptimeMillis() + TASK_STATE_TIMEOUT_MILLIS;
        TaskInfo task;
        do {
            task = findTask(taskId);
            if (task != null
                    && task.displayId == displayId
                    && windowingMode.equals(task.windowingMode)) {
                return;
            }
            SystemClock.sleep(TASK_STATE_POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("task " + taskId
                + " did not enter " + windowingMode
                + " mode on display " + displayId);
    }

    private static void bringTaskStackToFrontBestEffort(final TaskInfo task,
            final int[] preservedTopFirstTaskIds) {
        try {
            final Set<Integer> orderedTaskIds = new LinkedHashSet<>();
            if (preservedTopFirstTaskIds != null) {
                for (int index = preservedTopFirstTaskIds.length - 1; index >= 0; index--) {
                    final int taskId = preservedTopFirstTaskIds[index];
                    if (taskId >= 0 && taskId != task.taskId) {
                        orderedTaskIds.add(Integer.valueOf(taskId));
                    }
                }
            }
            orderedTaskIds.add(Integer.valueOf(task.taskId));
            runCommand(TaskFocusCommands.createShellCommand(
                    task.displayId, orderedTaskIds));
        } catch (IOException ignored) {
            Log.w(TAG, "bring task stack to front failed package=" + task.packageName
                    + " task=" + task.taskId, ignored);
            // Reuse already succeeded; do not fall back to a relaunch just because focus failed.
        }
    }

    private static void setFreeform(
            final int taskId,
            final int displayId,
            final Rect targetBounds)
            throws IOException {
        final Rect bounds = resolveTargetBounds(displayId, targetBounds);
        runCommand(TaskRepository.createFreeformTransitionCommand(
                displayId, taskId, bounds));
    }

    private static Rect resolveTargetBounds(
            final int displayId,
            final Rect targetBounds) throws IOException {
        return targetBounds == null || targetBounds.isEmpty()
                ? FloatingWindowController.getDefaultWindowBounds(displayId)
                : new Rect(targetBounds);
    }

    private static void setBounds(
            final int displayId,
            final int taskId,
            final Rect bounds) throws IOException {
        runCommand(TaskRepository.createBoundsTransactionCommand(
                displayId, taskId, bounds));
    }

    private static void setFullscreen(final TaskInfo task, final int displayId)
            throws IOException {
        runCommand(TaskRepository.createFullscreenTransitionCommand(
                displayId, task.taskId));
    }

    private static void setCaptionInsetExcluded(final int taskId, final int displayId,
            final boolean excluded) throws IOException {
        runCommand(TaskRepository.createCaptionInsetsCommand(
                displayId, taskId, excluded));
    }

    private static TaskInfo findBestTask(
            final AppLaunchTarget target,
            final int targetDisplayId,
            final boolean targetFreeform) throws IOException {
        final List<TaskInfo> tasks = findTasks(target);
        if (tasks.isEmpty()) {
            return null;
        }

        for (final TaskInfo task : tasks) {
            if (task.displayId == targetDisplayId && matchesWindowingMode(task, targetFreeform)) {
                return task;
            }
        }
        for (final TaskInfo task : tasks) {
            if (task.displayId == targetDisplayId) {
                return task;
            }
        }
        for (final TaskInfo task : tasks) {
            if (matchesWindowingMode(task, targetFreeform)) {
                return task;
            }
        }
        return tasks.get(0);
    }

    private static List<TaskInfo> findTasks(final AppLaunchTarget target)
            throws IOException {
        final String output = runCommand(CMD + " activity stack list");
        final List<TaskInfo> result = new ArrayList<>();
        for (final TaskStackParser.Entry task :
                TaskStackParser.parse(output)) {
            if (target.matchesTask(
                    task.packageName,
                    task.componentName,
                    task.topActivityName)) {
                result.add(new TaskInfo(
                        task.rootTaskId,
                        task.taskId,
                        task.displayId,
                        task.windowingMode,
                        task.packageName,
                        task.visible));
            }
        }
        return result;
    }

    private static TaskInfo findTask(final int taskId) throws IOException {
        final String output = runCommand(CMD + " activity stack list");
        for (final TaskStackParser.Entry task :
                TaskStackParser.parse(output)) {
            if (task.taskId == taskId) {
                return new TaskInfo(
                        task.rootTaskId,
                        task.taskId,
                        task.displayId,
                        task.windowingMode,
                        task.packageName,
                        task.visible);
            }
        }
        return null;
    }

    private static boolean matchesWindowingMode(final TaskInfo task,
            final boolean targetFreeform) {
        return targetFreeform
                ? MODE_FREEFORM.equals(task.windowingMode)
                : MODE_FULLSCREEN.equals(task.windowingMode);
    }

    private static String runCommand(final String command) throws IOException {
        return ShellAccess.run(command);
    }

    static final class ReuseResult {
        final boolean found;
        final int taskId;
        final String packageName;

        private ReuseResult(
                final boolean found,
                final int taskId,
                final String packageName) {
            this.found = found;
            this.taskId = taskId;
            this.packageName = packageName;
        }

        static ReuseResult reused(
                final int taskId,
                final String packageName) {
            return new ReuseResult(true, taskId, packageName);
        }

        static ReuseResult notFound() {
            return new ReuseResult(false, -1, null);
        }
    }

    private static final class TaskInfo {
        final int rootTaskId;
        final int taskId;
        final int displayId;
        final String windowingMode;
        final String packageName;
        final boolean visible;

        TaskInfo(final int rootTaskId, final int taskId, final int displayId,
                final String windowingMode, final String packageName,
                final boolean visible) {
            this.rootTaskId = rootTaskId;
            this.taskId = taskId;
            this.displayId = displayId;
            this.windowingMode = windowingMode;
            this.packageName = packageName;
            this.visible = visible;
        }
    }
}

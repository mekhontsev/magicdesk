package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

final class ExistingTaskController {
    private static final String TAG = "MagicDeskTaskReuse";
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
        final int originalDisplayId = task.displayId;

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
            if (task.displayId != targetDisplayId) {
                final Rect bounds = targetFreeform
                        ? resolveTargetBounds(targetDisplayId, targetBounds)
                        : null;
                final DesktopTaskTransfer.Mode transferMode = targetFreeform
                        ? DesktopTaskTransfer.Mode.FREEFORM
                        : DesktopTaskTransfer.Mode.FULLSCREEN;
                final String output = DesktopTaskTransfer.move(
                        task.taskId,
                        task.rootTaskId,
                        task.displayId,
                        targetDisplayId,
                        transferMode,
                        bounds);
                movedAsFreeform = targetFreeform;
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
            }

            return ReuseResult.reused(
                    task.taskId, task.packageName, originalDisplayId);
        } finally {
            if (outerLaunchLease == null) {
                launchLease.close();
            }
        }
    }

    private static TaskInfo waitForBestTask(final AppLaunchTarget target,
            final int targetDisplayId, final boolean targetFreeform) throws IOException {
        return BoundedStateAwaiter.awaitIo(
                BoundedStateAwaiter.Reason.TASK_APPEARANCE,
                TASK_APPEAR_TIMEOUT_MILLIS,
                TASK_STATE_POLL_MILLIS,
                () -> findBestTask(
                        target, targetDisplayId, targetFreeform),
                task -> task != null && task.visible);
    }

    private static void waitForTaskDisplay(final int taskId, final int displayId)
            throws IOException {
        final TaskInfo task = BoundedStateAwaiter.awaitIo(
                BoundedStateAwaiter.Reason.TASK_DISPLAY,
                TASK_STATE_TIMEOUT_MILLIS,
                TASK_STATE_POLL_MILLIS,
                () -> findTask(taskId),
                current -> current != null
                        && current.displayId == displayId);
        if (task != null && task.displayId == displayId) {
            return;
        }
        throw new IOException("task " + taskId
                + " did not move to display " + displayId);
    }

    private static void waitForTaskState(final int taskId, final int displayId,
            final String windowingMode) throws IOException {
        final TaskInfo task = BoundedStateAwaiter.awaitIo(
                BoundedStateAwaiter.Reason.TASK_WINDOWING_MODE,
                TASK_STATE_TIMEOUT_MILLIS,
                TASK_STATE_POLL_MILLIS,
                () -> findTask(taskId),
                current -> current != null
                        && current.displayId == displayId
                        && windowingMode.equals(current.windowingMode));
        if (task != null
                && task.displayId == displayId
                && windowingMode.equals(task.windowingMode)) {
            return;
        }
        throw new IOException("task " + taskId
                + " did not enter " + windowingMode
                + " mode on display " + displayId);
    }

    private static void setFreeform(
            final int taskId,
            final int displayId,
            final Rect targetBounds)
            throws IOException {
        final Rect bounds = resolveTargetBounds(displayId, targetBounds);
        if (!MagicDeskRuntime.attachWindowedTask(
                displayId, taskId, bounds)) {
            throw new IOException(
                    "could not attach task " + taskId
                            + " as a desktop window on display " + displayId);
        }
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
        ensureOffMainThread();
        final List<TaskInfo> result = new ArrayList<>();
        for (final FrameworkTaskSnapshot task :
                ShellAccess.readTaskSnapshots(-1, 200)) {
            if (target.matchesTask(
                    task.packageName,
                    task.componentName,
                    task.topActivityName)) {
                result.add(new TaskInfo(
                        task.rootTaskId,
                        task.taskId,
                        task.displayId,
                        task.windowingModeName(),
                        task.packageName,
                        task.visible));
            }
        }
        return result;
    }

    private static TaskInfo findTask(final int taskId) throws IOException {
        ensureOffMainThread();
        for (final FrameworkTaskSnapshot task :
                ShellAccess.readTaskSnapshots(-1, 200)) {
            if (task.taskId == taskId) {
                return new TaskInfo(
                        task.rootTaskId,
                        task.taskId,
                        task.displayId,
                        task.windowingModeName(),
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
        ensureOffMainThread();
        return ShellAccess.run(command);
    }

    private static void ensureOffMainThread() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException(
                    "task reuse commands must not run on the main thread");
        }
    }

    static final class ReuseResult {
        final boolean found;
        final int taskId;
        final String packageName;
        final int originalDisplayId;

        private ReuseResult(
                final boolean found,
                final int taskId,
                final String packageName,
                final int originalDisplayId) {
            this.found = found;
            this.taskId = taskId;
            this.packageName = packageName;
            this.originalDisplayId = originalDisplayId;
        }

        static ReuseResult reused(
                final int taskId,
                final String packageName,
                final int originalDisplayId) {
            return new ReuseResult(
                    true, taskId, packageName, originalDisplayId);
        }

        static ReuseResult notFound() {
            return new ReuseResult(false, -1, null, -1);
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

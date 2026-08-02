package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class ExistingTaskController {
    private static final String TAG = "MagicDeskTaskReuse";
    private static final String CMD = "/system/bin/cmd";
    private static final String TASK_CONTROL_COMMAND =
            "io.github.mekhontsev.magicdesk.TaskControlCommand";
    private static final String MODE_FULLSCREEN = "fullscreen";
    private static final String MODE_FREEFORM = "freeform";
    private static final long TASK_APPEAR_TIMEOUT_MILLIS = 6000;
    private static final long TASK_STATE_TIMEOUT_MILLIS = 6000;
    private static final long TASK_STATE_POLL_MILLIS = 100;

    private ExistingTaskController() {
    }

    static ReuseResult reuseIfExists(final String packageName, final int targetDisplayId,
            final boolean targetFreeform,
            final boolean preserveFullscreenClient) throws IOException {
        return reuseIfExists(packageName, targetDisplayId, targetFreeform,
                null, false, false, preserveFullscreenClient);
    }

    static ReuseResult normalizeLaunchedFullscreen(
            final String packageName,
            final int targetDisplayId,
            final boolean preserveFullscreenClient) throws IOException {
        final TaskInfo task = waitForBestTask(
                packageName, targetDisplayId, false);
        if (task == null) {
            throw new IOException(
                    "launched task not found for " + packageName);
        }

        Log.i(TAG, "normalize launched fullscreen package=" + packageName
                + " task=" + task.taskId
                + " display=" + task.displayId
                + " mode=" + task.windowingMode
                + " targetDisplay=" + targetDisplayId);
        if (task.displayId != targetDisplayId) {
            final String command = CMD + " activity display move-stack "
                    + task.rootTaskId + " " + targetDisplayId;
            runCommand(command);
            waitForTaskDisplay(task.taskId, targetDisplayId);
        }
        setFullscreen(task, targetDisplayId, preserveFullscreenClient);
        bringTaskStackToFrontBestEffort(task, null);
        return ReuseResult.reused(task.packageName);
    }

    static ReuseResult reuseNativeDesktopIfExists(final String packageName,
            final int targetDisplayId, final int[] preservedTopFirstTaskIds,
            final boolean waitForTask) throws IOException {
        return reuseIfExists(packageName, targetDisplayId, true,
                preservedTopFirstTaskIds, true, waitForTask, false);
    }

    static ReuseResult reuseFreeformIfExists(final String packageName,
            final int targetDisplayId, final int[] preservedTopFirstTaskIds,
            final boolean waitForTask) throws IOException {
        return reuseIfExists(packageName, targetDisplayId, true,
                preservedTopFirstTaskIds, false, waitForTask, false);
    }

    static boolean taskExists(final String packageName, final int targetDisplayId)
            throws IOException {
        return findBestTask(packageName, targetDisplayId, true) != null;
    }

    static void waitForNativeDesktopTask(final int taskId, final int displayId)
            throws IOException {
        waitForTaskState(taskId, displayId, MODE_FREEFORM);
    }

    static void prepareFreeformLaunchSource(
            final int taskId,
            final int displayId,
            final Rect bounds) throws IOException {
        if (taskId < 0 || displayId < 0 || bounds == null || bounds.isEmpty()) {
            throw new IOException("invalid freeform launch source");
        }
        runCommand(TaskRepository.createFreeformTransitionCommand(
                displayId, taskId, bounds));
        waitForTaskState(taskId, displayId, MODE_FREEFORM);
    }

    static void focusFreeformLaunchSource(
            final int taskId,
            final int displayId) throws IOException {
        final TaskInfo task = findTask(taskId);
        if (task == null
                || task.displayId != displayId
                || !MODE_FREEFORM.equals(task.windowingMode)) {
            throw new IOException("freeform launch source unavailable");
        }
        runCommand(TaskFocusCommands.createShellCommand(
                Collections.singletonList(Integer.valueOf(taskId))));
    }

    private static ReuseResult reuseIfExists(final String packageName,
            final int targetDisplayId, final boolean targetFreeform,
            final int[] preservedTopFirstTaskIds, final boolean nativeDesktop,
            final boolean waitForTask,
            final boolean preserveFullscreenClient) throws IOException {
        final TaskInfo task = waitForTask
                ? waitForBestTask(packageName, targetDisplayId, targetFreeform)
                : findBestTask(packageName, targetDisplayId, targetFreeform);
        if (task == null) {
            Log.i(TAG, "no existing task package=" + packageName);
            return ReuseResult.notFound();
        }

        Log.i(TAG, "found package=" + packageName
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
        final boolean restoreTouchpad =
                nativeDesktop && ConsoleModeSwitcher.isTouchpadVisible();
        if (restoreTouchpad) {
            DesktopTaskController.expectTouchpadDisplacement();
        }
        final boolean taskIsFreeform = MODE_FREEFORM.equals(task.windowingMode);
        final boolean taskIsFullscreen = MODE_FULLSCREEN.equals(task.windowingMode);
        if (targetFreeform) {
            DesktopTaskController.noteManualFreeformTransition(task.taskId);
        }
        try {
            if (task.displayId != targetDisplayId) {
                final String command = CMD + " activity display move-stack "
                        + task.rootTaskId + " " + targetDisplayId;
                Log.i(TAG, "move display: " + command);
                runCommand(command);
                waitForTaskDisplay(task.taskId, targetDisplayId);
            }

            if (nativeDesktop) {
                if (!taskIsFreeform) {
                    NativeDesktopController.moveTaskToDesktop(task.taskId);
                    waitForTaskState(task.taskId, targetDisplayId, MODE_FREEFORM);
                }
                setCaptionInsetExcluded(task.taskId, targetDisplayId, false);
            } else if (targetFreeform && taskIsFullscreen) {
                Log.i(TAG, "convert fullscreen to freeform task=" + task.taskId);
                setFreeform(task.taskId, targetDisplayId);
                waitForTaskState(task.taskId, targetDisplayId, MODE_FREEFORM);
            } else if (!targetFreeform && taskIsFreeform) {
                Log.i(TAG, "convert freeform to fullscreen task=" + task.taskId);
                setFullscreen(task, targetDisplayId, preserveFullscreenClient);
            } else {
                setCaptionInsetExcluded(task.taskId, targetDisplayId,
                        !targetFreeform);
            }

            bringTaskStackToFrontBestEffort(task, preservedTopFirstTaskIds);
        } finally {
            if (restoreTouchpad) {
                DesktopTaskController.finishTouchpadPreservation();
                ConsoleModeSwitcher.restoreTouchpadIfMissing();
            }
        }
        return ReuseResult.reused(task.packageName);
    }

    private static TaskInfo waitForBestTask(final String packageName,
            final int targetDisplayId, final boolean targetFreeform) throws IOException {
        final long deadline = SystemClock.uptimeMillis() + TASK_APPEAR_TIMEOUT_MILLIS;
        TaskInfo task;
        do {
            task = findBestTask(packageName, targetDisplayId, targetFreeform);
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
            runCommand(TaskFocusCommands.createShellCommand(orderedTaskIds));
        } catch (IOException ignored) {
            Log.w(TAG, "bring task stack to front failed package=" + task.packageName
                    + " task=" + task.taskId, ignored);
            // Reuse already succeeded; do not fall back to a relaunch just because focus failed.
        }
    }

    private static void setFreeform(final int taskId, final int displayId)
            throws IOException {
        final Rect bounds = FloatingWindowController.getDefaultWindowBounds(displayId);
        runCommand(TaskRepository.createFreeformTransitionCommand(
                displayId, taskId, bounds));
    }

    private static void setFullscreen(final TaskInfo task, final int displayId,
            final boolean preserveClient)
            throws IOException {
        runCommand(preserveClient
                ? TaskRepository.createClientPreservingFullscreenTransitionCommand(
                        displayId, task.taskId)
                : TaskRepository.createFullscreenTransitionCommand(
                        displayId, task.taskId));
    }

    private static void setCaptionInsetExcluded(final int taskId, final int displayId,
            final boolean excluded) throws IOException {
        runCommand(TaskRepository.createCaptionInsetsCommand(
                displayId, taskId, excluded));
    }

    private static String createAppProcessCommand(final String className,
            final String arguments) {
        return AppProcessCommand.run(className, arguments);
    }

    private static TaskInfo findBestTask(final String packageName, final int targetDisplayId,
            final boolean targetFreeform) throws IOException {
        final List<TaskInfo> tasks = findTasks(packageName);
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

    private static List<TaskInfo> findTasks(final String packageName) throws IOException {
        final String output = runCommand(CMD + " activity stack list");
        final List<TaskInfo> result = new ArrayList<>();
        for (final TaskStackParser.Entry task :
                TaskStackParser.parse(output)) {
            if (packageName.equals(task.packageName)) {
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

    private static String shellQuote(final String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String runCommand(final String command) throws IOException {
        return ShellAccess.run(command);
    }

    static final class ReuseResult {
        final boolean found;
        final String packageName;

        private ReuseResult(final boolean found, final String packageName) {
            this.found = found;
            this.packageName = packageName;
        }

        static ReuseResult reused(final String packageName) {
            return new ReuseResult(true, packageName);
        }

        static ReuseResult notFound() {
            return new ReuseResult(false, null);
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

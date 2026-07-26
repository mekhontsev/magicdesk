package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ExistingTaskController {
    private static final String TAG = "MagicDeskTaskReuse";
    private static final String CMD = "/system/bin/cmd";
    private static final String PACKAGE_NAME = "io.github.mekhontsev.magicdesk";
    private static final String TASK_CONTROL_COMMAND =
            "io.github.mekhontsev.magicdesk.TaskControlCommand";
    private static final Pattern ROOT_TASK_PATTERN =
            Pattern.compile("RootTask id=(\\d+) .* displayId=(\\d+)");
    private static final Pattern WINDOWING_MODE_PATTERN =
            Pattern.compile("mWindowingMode=([^\\s}]+)");
    private static final Pattern TASK_PATTERN =
            Pattern.compile("taskId=(\\d+): ([^\\s]+)");
    private static final String VISIBLE_TASK_MARKER = " visible=true";
    private static final String MODE_FULLSCREEN = "fullscreen";
    private static final String MODE_FREEFORM = "freeform";
    private static final long TASK_APPEAR_TIMEOUT_MILLIS = 6000;
    private static final long TASK_STATE_TIMEOUT_MILLIS = 6000;
    private static final long TASK_STATE_POLL_MILLIS = 100;

    private ExistingTaskController() {
    }

    static ReuseResult reuseIfExists(final String packageName, final int targetDisplayId,
            final boolean targetFreeform) throws IOException {
        return reuseIfExists(packageName, targetDisplayId, targetFreeform, null);
    }

    static ReuseResult reuseIfExists(final String packageName, final int targetDisplayId,
            final boolean targetFreeform, final int[] preservedTopFirstTaskIds)
            throws IOException {
        return reuseIfExists(packageName, targetDisplayId, targetFreeform,
                preservedTopFirstTaskIds, false, false);
    }

    static ReuseResult reuseNativeDesktopIfExists(final String packageName,
            final int targetDisplayId, final int[] preservedTopFirstTaskIds,
            final boolean waitForTask) throws IOException {
        return reuseIfExists(packageName, targetDisplayId, true,
                preservedTopFirstTaskIds, true, waitForTask);
    }

    static boolean taskExists(final String packageName, final int targetDisplayId)
            throws IOException {
        return findBestTask(packageName, targetDisplayId, true) != null;
    }

    static void startActivityAsRoot(final ComponentName component, final int displayId)
            throws IOException {
        if (component == null || displayId < 0) {
            throw new IOException("invalid root activity launch");
        }
        final String output = runRootCommand("/system/bin/am start -W --display " + displayId
                + " -n " + shellQuote(component.flattenToShortString())).trim();
        if (output.startsWith("Error:")
                || output.contains("Exception occurred while executing")) {
            throw new IOException(output);
        }
        Log.i(TAG, "root activity started component="
                + component.flattenToShortString() + " display=" + displayId);
    }

    static void waitForNativeDesktopTask(final int taskId, final int displayId)
            throws IOException {
        waitForTaskState(taskId, displayId, MODE_FREEFORM);
    }

    private static ReuseResult reuseIfExists(final String packageName,
            final int targetDisplayId, final boolean targetFreeform,
            final int[] preservedTopFirstTaskIds, final boolean nativeDesktop,
            final boolean waitForTask) throws IOException {
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
        if (task.displayId != targetDisplayId) {
            final String command = CMD + " activity display move-stack " + task.rootTaskId
                    + " " + targetDisplayId;
            Log.i(TAG, "move display: " + command);
            runRootCommand(command);
            waitForTaskDisplay(task.taskId, targetDisplayId);
        }

        final boolean taskIsFreeform = MODE_FREEFORM.equals(task.windowingMode);
        final boolean taskIsFullscreen = MODE_FULLSCREEN.equals(task.windowingMode);
        if (nativeDesktop) {
            NativeDesktopController.moveTaskToDesktop(task.taskId);
            waitForTaskState(task.taskId, targetDisplayId, MODE_FREEFORM);
            setCaptionInsetExcluded(task.taskId, targetDisplayId, false);
        } else if (targetFreeform && taskIsFullscreen) {
            Log.i(TAG, "convert fullscreen to freeform task=" + task.taskId);
            setFreeform(task.taskId, targetDisplayId);
        } else if (!targetFreeform && taskIsFreeform) {
            Log.i(TAG, "convert freeform to fullscreen task=" + task.taskId);
            setFullscreen(task, targetDisplayId);
        } else {
            setCaptionInsetExcluded(task.taskId, targetDisplayId, !targetFreeform);
        }

        bringTaskStackToFrontBestEffort(task, preservedTopFirstTaskIds);
        if (restoreTouchpad) {
            ConsoleModeSwitcher.restoreTouchpadIfMissing();
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
                + " did not enter native desktop mode on display " + displayId);
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
            final StringBuilder arguments = new StringBuilder("focus-stack");
            for (final Integer taskId : orderedTaskIds) {
                arguments.append(' ').append(taskId.intValue());
            }
            runRootCommand(createAppProcessCommand(TASK_CONTROL_COMMAND,
                    arguments.toString()));
        } catch (IOException ignored) {
            Log.w(TAG, "bring task stack to front failed package=" + task.packageName
                    + " task=" + task.taskId, ignored);
            // Reuse already succeeded; do not fall back to a relaunch just because focus failed.
        }
    }

    private static void setFreeform(final int taskId, final int displayId)
            throws IOException {
        final Rect bounds = FloatingWindowController.getDefaultWindowBounds(displayId);
        runRootCommand(TaskRepository.createFreeformTransitionCommand(
                displayId, taskId, bounds));
    }

    private static void setFullscreen(final TaskInfo task, final int displayId)
            throws IOException {
        runRootCommand(TaskRepository.createFullscreenTransitionCommand(
                displayId, task.taskId));
    }

    private static void setCaptionInsetExcluded(final int taskId, final int displayId,
            final boolean excluded) throws IOException {
        runRootCommand(TaskRepository.createCaptionInsetsCommand(
                displayId, taskId, excluded));
    }

    private static String createAppProcessCommand(final String className,
            final String arguments) {
        return "APK=$(/system/bin/pm path " + PACKAGE_NAME
                + " | /system/bin/cut -d: -f2- | /system/bin/head -n 1); "
                + "CLASSPATH=\"$APK\" /system/bin/app_process / "
                + className + " " + arguments;
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
        final String output = runRootCommand(CMD + " activity stack list");
        final String[] lines = output.split("\\r?\\n");
        final List<TaskInfo> result = new ArrayList<>();
        int rootTaskId = -1;
        int displayId = -1;
        String windowingMode = null;
        for (final String line : lines) {
            final Matcher rootMatcher = ROOT_TASK_PATTERN.matcher(line);
            if (rootMatcher.find()) {
                rootTaskId = Integer.parseInt(rootMatcher.group(1));
                displayId = Integer.parseInt(rootMatcher.group(2));
                windowingMode = null;
                continue;
            }

            if (rootTaskId >= 0 && windowingMode == null) {
                final Matcher modeMatcher = WINDOWING_MODE_PATTERN.matcher(line);
                if (modeMatcher.find()) {
                    windowingMode = modeMatcher.group(1);
                }
            }

            final Matcher taskMatcher = TASK_PATTERN.matcher(line);
            if (rootTaskId >= 0 && taskMatcher.find()
                    && line.contains(" topActivity=ComponentInfo{")) {
                final String componentName = taskMatcher.group(2);
                final int slash = componentName.indexOf('/');
                final String taskPackage = slash <= 0
                        ? componentName : componentName.substring(0, slash);
                if (!packageName.equals(taskPackage)) {
                    continue;
                }
                result.add(new TaskInfo(rootTaskId, Integer.parseInt(taskMatcher.group(1)),
                        displayId, windowingMode, packageName,
                        line.contains(VISIBLE_TASK_MARKER)));
            }
        }
        return result;
    }

    private static TaskInfo findTask(final int taskId) throws IOException {
        final String output = runRootCommand(CMD + " activity stack list");
        final String[] lines = output.split("\\r?\\n");
        int rootTaskId = -1;
        int displayId = -1;
        String windowingMode = null;
        for (final String line : lines) {
            final Matcher rootMatcher = ROOT_TASK_PATTERN.matcher(line);
            if (rootMatcher.find()) {
                rootTaskId = Integer.parseInt(rootMatcher.group(1));
                displayId = Integer.parseInt(rootMatcher.group(2));
                windowingMode = null;
                continue;
            }
            if (rootTaskId >= 0 && windowingMode == null) {
                final Matcher modeMatcher = WINDOWING_MODE_PATTERN.matcher(line);
                if (modeMatcher.find()) {
                    windowingMode = modeMatcher.group(1);
                }
            }
            final Matcher taskMatcher = TASK_PATTERN.matcher(line);
            if (rootTaskId < 0 || !taskMatcher.find()
                    || Integer.parseInt(taskMatcher.group(1)) != taskId
                    || !line.contains(" topActivity=ComponentInfo{")) {
                continue;
            }
            final String componentName = taskMatcher.group(2);
            final int slash = componentName.indexOf('/');
            final String packageName = slash <= 0
                    ? componentName : componentName.substring(0, slash);
            return new TaskInfo(rootTaskId, taskId, displayId, windowingMode,
                    packageName, line.contains(VISIBLE_TASK_MARKER));
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

    private static String runRootCommand(final String command) throws IOException {
        final Process process = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start();
        final StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        try {
            final int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("root command failed " + exitCode + ": "
                        + output.toString().trim());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("root command interrupted", e);
        } finally {
            process.destroy();
        }
        return output.toString();
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

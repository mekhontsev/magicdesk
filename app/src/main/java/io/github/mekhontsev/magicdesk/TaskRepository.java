package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class TaskRepository {
    private static final String TAG = "MagicDeskTasks";
    private static final String CMD = "/system/bin/cmd";
    private static final String AM = "/system/bin/am";
    private static final String TASK_CONTROL_COMMAND =
            "io.github.mekhontsev.magicdesk.TaskControlCommand";
    private static final String TASK_FULLSCREEN_TRANSITION_COMMAND =
            "io.github.mekhontsev.magicdesk.TaskFullscreenTransitionCommand";
    private static final String TASK_CLIENT_PRESERVING_FULLSCREEN_TRANSITION_COMMAND =
            "io.github.mekhontsev.magicdesk.TaskClientPreservingFullscreenTransitionCommand";
    private static final String TASK_CAPTION_INSETS_COMMAND =
            "io.github.mekhontsev.magicdesk.TaskCaptionInsetsCommand";
    private static final String TASK_WINDOWING_COMMAND =
            "io.github.mekhontsev.magicdesk.TaskWindowingCommand";
    private static final Pattern ROOT_TASK_PATTERN =
            Pattern.compile("RootTask id=(\\d+).* displayId=(\\d+)");
    private static final Pattern WINDOWING_MODE_PATTERN =
            Pattern.compile("mWindowingMode=([^\\s}]+)");
    private static final Pattern ACTIVITY_TYPE_PATTERN =
            Pattern.compile("mActivityType=([^\\s}]+)");
    private static final Pattern TOP_ACTIVITY_PATTERN =
            Pattern.compile("topActivity=ComponentInfo\\{([^}]+)\\}");
    private static final Pattern BOUNDS_PATTERN = Pattern.compile(
            "bounds=\\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\]");

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(final Runnable runnable) {
                    final Thread thread = new Thread(runnable, "MagicDeskTasks");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private TaskRepository() {
    }

    interface SnapshotCallback {
        void onLoaded(Snapshot snapshot);
    }

    interface ActionCallback {
        void onComplete(ActionResult result);
    }

    static void load(final int displayId, final SnapshotCallback callback) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                final CommandResult command = runRootCommand(CMD + " activity stack list");
                final List<TaskEntry> tasks = command.success
                        ? parseTasks(command.output, displayId)
                        : Collections.<TaskEntry>emptyList();
                final List<TaskEntry> phoneTasks = command.success && displayId != 0
                        ? parseTasks(command.output, 0)
                        : Collections.<TaskEntry>emptyList();
                if (callback != null) {
                    callback.onLoaded(new Snapshot(
                            tasks, phoneTasks, command.success, command.output));
                }
            }
        });
    }

    static void bringToFront(final TaskEntry task, final ActionCallback callback) {
        if (!isUsableTask(task)) {
            complete(callback, false, "invalid task");
            return;
        }
        runAction(createTaskFocusCommand(task.taskId), callback);
    }

    static void bringStackToFront(final List<TaskEntry> topFirstTasks,
            final TaskEntry topTask, final ActionCallback callback) {
        final Set<Integer> orderedTaskIds = new LinkedHashSet<>();
        if (topFirstTasks != null) {
            for (int index = topFirstTasks.size() - 1; index >= 0; index--) {
                final TaskEntry task = topFirstTasks.get(index);
                if (isRestorableTask(task)) {
                    orderedTaskIds.add(Integer.valueOf(task.taskId));
                }
            }
        }
        if (isRestorableTask(topTask)) {
            orderedTaskIds.remove(Integer.valueOf(topTask.taskId));
            orderedTaskIds.add(Integer.valueOf(topTask.taskId));
        }
        if (orderedTaskIds.isEmpty()) {
            complete(callback, true, "no tasks");
            return;
        }

        if (RuntimeAccess.allowsShizukuCommands()) {
            runAction(
                    TaskFocusCommands.createShellCommand(orderedTaskIds),
                    callback);
            return;
        }

        final StringBuilder arguments = new StringBuilder("focus-stack");
        for (final Integer taskId : orderedTaskIds) {
            arguments.append(' ').append(taskId.intValue());
        }
        runAction(createTaskControlCommand(arguments.toString()), callback);
    }

    static void restoreFreeformStack(final int displayId,
            final List<TaskEntry> savedTopFirstTasks, final ActionCallback callback) {
        if (displayId < 0 || savedTopFirstTasks == null || savedTopFirstTasks.isEmpty()) {
            complete(callback, true, "no saved windows");
            return;
        }
        final List<TaskEntry> savedTasks = new ArrayList<>(savedTopFirstTasks);
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                final CommandResult stackResult = runRootCommand(CMD + " activity stack list");
                if (!stackResult.success) {
                    complete(callback, false, stackResult.output.trim());
                    return;
                }

                final List<TaskEntry> currentTasks = parseTasks(stackResult.output, displayId);
                final List<TaskEntry> restoredTopFirst = new ArrayList<>();
                String failure = "";
                for (final TaskEntry savedTask : savedTasks) {
                    final TaskEntry currentTask = findMatchingTask(currentTasks, savedTask);
                    if (currentTask == null || !currentTask.isFreeform()
                            || savedTask.bounds.isEmpty()) {
                        continue;
                    }
                    if (!savedTask.bounds.equals(currentTask.bounds)) {
                        final Rect bounds = savedTask.bounds;
                        final CommandResult resizeResult = runRootCommand(
                                AM + " task resize " + currentTask.taskId
                                        + " " + bounds.left + " " + bounds.top
                                        + " " + bounds.right + " " + bounds.bottom);
                        if (!resizeResult.success) {
                            failure = resizeResult.output.trim();
                            Log.w(TAG, "failed to restore task=" + currentTask.taskId
                                    + " output=" + failure);
                            continue;
                        }
                    }
                    restoredTopFirst.add(currentTask);
                }

                if (restoredTopFirst.isEmpty()) {
                    complete(callback, failure.length() == 0,
                            failure.length() == 0 ? "no live windows" : failure);
                    return;
                }

                final StringBuilder arguments = new StringBuilder("restore-stack ")
                        .append(displayId);
                for (int index = restoredTopFirst.size() - 1; index >= 0; index--) {
                    arguments.append(' ').append(restoredTopFirst.get(index).taskId);
                }
                final CommandResult restoreResult = runRootCommand(
                        createTaskWindowingCommand(arguments.toString()));
                final boolean success = restoreResult.success && failure.length() == 0;
                complete(callback, success, success
                        ? "restored " + restoredTopFirst.size() + " windows"
                        : (restoreResult.success ? failure : restoreResult.output.trim()));
            }
        });
    }

    static void closeTask(final TaskEntry task, final ActionCallback callback) {
        if (!isUsableTask(task) || task.home
                || "io.github.mekhontsev.magicdesk".equals(task.packageName)) {
            complete(callback, false, "invalid task");
            return;
        }
        runAction(createTaskControlCommand("remove", task.taskId), callback);
    }

    static void minimizeTask(final TaskEntry task, final ActionCallback callback) {
        if (!isUsableTask(task) || !task.isFreeform()) {
            complete(callback, false, "invalid task");
            return;
        }
        runAction(createTaskWindowingCommand(
                "minimize " + task.displayId + " " + task.taskId), callback);
    }

    static void restoreTask(final TaskEntry task, final ActionCallback callback) {
        if (!isUsableTask(task) || !task.isFreeform()) {
            complete(callback, false, "invalid task");
            return;
        }
        runAction(createTaskWindowingCommand(
                "restore " + task.displayId + " " + task.taskId), callback);
    }

    static void setFullscreen(final TaskEntry task, final ActionCallback callback) {
        if (!isUsableTask(task)) {
            complete(callback, false, "invalid task");
            return;
        }
        runAction(createFullscreenTransitionCommand(task.displayId, task.taskId),
                callback);
    }

    static void setAppRequestedFullscreen(
            final TaskEntry task, final ActionCallback callback) {
        if (!isUsableTask(task)) {
            complete(callback, false, "invalid task");
            return;
        }
        runAction(createClientPreservingFullscreenTransitionCommand(
                task.displayId, task.taskId), callback);
    }

    static void setFreeform(final TaskEntry task, final Rect bounds,
            final ActionCallback callback) {
        if (!isUsableTask(task) || bounds == null || bounds.isEmpty()) {
            complete(callback, false, "invalid task bounds");
            return;
        }
        runAction(createFreeformTransitionCommand(task.displayId, task.taskId, bounds),
                callback);
    }

    static void resizeTaskBounds(final TaskEntry task, final Rect bounds,
            final ActionCallback callback) {
        if (!isUsableTask(task) || bounds == null || bounds.isEmpty()) {
            complete(callback, false, "invalid task bounds");
            return;
        }
        runAction(AM + " task resize " + task.taskId + " "
                + bounds.left + " " + bounds.top + " "
                + bounds.right + " " + bounds.bottom, callback);
    }

    static void sendBackToDisplay(final int displayId, final ActionCallback callback) {
        if (displayId < 0) {
            complete(callback, false, "invalid display");
            return;
        }
        runAction("/system/bin/input -d " + displayId
                + " keyevent KEYCODE_BACK", callback);
    }

    static void forceStop(final String packageName, final ActionCallback callback) {
        if (!isPackageNameSafe(packageName)
                || "io.github.mekhontsev.magicdesk".equals(packageName)) {
            complete(callback, false, "invalid package");
            return;
        }
        runAction(AM + " force-stop --user 0 " + packageName, callback);
    }

    private static void runAction(final String command, final ActionCallback callback) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                final CommandResult result = runRootCommand(command);
                if (callback != null) {
                    callback.onComplete(new ActionResult(
                            result.success, result.output.trim()));
                }
            }
        });
    }

    private static String createTaskControlCommand(final String action, final int taskId) {
        return createTaskControlCommand(action + " " + taskId);
    }

    private static String createTaskFocusCommand(final int taskId) {
        if (RuntimeAccess.allowsShizukuCommands()) {
            return TaskFocusCommands.createShellCommand(
                    Arrays.asList(Integer.valueOf(taskId)));
        }
        return createTaskControlCommand("focus", taskId);
    }

    private static String createTaskControlCommand(final String arguments) {
        return createAppProcessCommand(TASK_CONTROL_COMMAND, arguments);
    }

    private static String createAppProcessCommand(final String className,
            final String arguments) {
        return "APK=$(/system/bin/pm path io.github.mekhontsev.magicdesk "
                + "| /system/bin/cut -d: -f2- | /system/bin/head -n 1); "
                + "CLASSPATH=\"$APK\" /system/bin/app_process / "
                + className + " " + arguments;
    }

    private static String createTaskWindowingCommand(final String arguments) {
        return "APK=$(/system/bin/pm path io.github.mekhontsev.magicdesk "
                + "| /system/bin/cut -d: -f2- | /system/bin/head -n 1); "
                + "CLASSPATH=\"$APK\" /system/bin/app_process / "
                + TASK_WINDOWING_COMMAND + " " + arguments;
    }

    static String createFullscreenTransitionCommand(final int displayId,
            final int taskId) {
        return createAppProcessEnvironment()
                + createAppProcessInvocation(TASK_FULLSCREEN_TRANSITION_COMMAND,
                        displayId + " " + taskId);
    }

    static String createClientPreservingFullscreenTransitionCommand(
            final int displayId, final int taskId) {
        return createAppProcessEnvironment()
                + createAppProcessInvocation(
                        TASK_CLIENT_PRESERVING_FULLSCREEN_TRANSITION_COMMAND,
                        displayId + " " + taskId);
    }

    static String createFreeformTransitionCommand(final int displayId,
            final int taskId, final Rect bounds) {
        final String arguments = "freeform " + displayId + " " + taskId
                + " " + bounds.left + " " + bounds.top
                + " " + bounds.right + " " + bounds.bottom;
        return createAppProcessEnvironment()
                + createAppProcessInvocation(TASK_WINDOWING_COMMAND, arguments)
                + " && " + createAppProcessInvocation(TASK_CAPTION_INSETS_COMMAND,
                        displayId + " " + taskId + " include");
    }

    static String createCaptionInsetsCommand(final int displayId, final int taskId,
            final boolean excluded) {
        return createAppProcessEnvironment()
                + createAppProcessInvocation(TASK_CAPTION_INSETS_COMMAND,
                        displayId + " " + taskId + " "
                                + (excluded ? "exclude" : "include"));
    }

    private static String createAppProcessEnvironment() {
        return "APK=$(/system/bin/pm path io.github.mekhontsev.magicdesk "
                + "| /system/bin/cut -d: -f2- | /system/bin/head -n 1); "
                + "export CLASSPATH=\"$APK\"; ";
    }

    private static String createAppProcessInvocation(final String className,
            final String arguments) {
        return "/system/bin/app_process / " + className + " " + arguments;
    }

    private static void complete(final ActionCallback callback, final boolean success,
            final String message) {
        if (callback != null) {
            callback.onComplete(new ActionResult(success, message));
        }
    }

    private static boolean isUsableTask(final TaskEntry task) {
        return task != null && task.taskId >= 0 && task.rootTaskId >= 0;
    }

    private static boolean isRestorableTask(final TaskEntry task) {
        return isUsableTask(task) && !task.home
                && !"io.github.mekhontsev.magicdesk".equals(task.packageName);
    }

    private static TaskEntry findMatchingTask(final List<TaskEntry> currentTasks,
            final TaskEntry savedTask) {
        if (savedTask == null || currentTasks == null) {
            return null;
        }
        for (final TaskEntry currentTask : currentTasks) {
            if (currentTask.taskId == savedTask.taskId
                    && currentTask.packageName.equals(savedTask.packageName)
                    && isRestorableTask(currentTask)) {
                return currentTask;
            }
        }
        return null;
    }

    private static List<TaskEntry> parseTasks(final String output, final int targetDisplayId) {
        final List<TaskEntry> tasks = new ArrayList<>();
        int rootTaskId = -1;
        int displayId = -1;
        String windowingMode = null;
        String activityType = null;
        Rect rootBounds = null;
        boolean activeAssigned = false;

        final String[] lines = output.split("\\r?\\n");
        for (final String line : lines) {
            final Matcher rootMatcher = ROOT_TASK_PATTERN.matcher(line);
            if (rootMatcher.find()) {
                rootTaskId = parseInt(rootMatcher.group(1));
                displayId = parseInt(rootMatcher.group(2));
                windowingMode = null;
                activityType = null;
                rootBounds = parseBounds(line);
                continue;
            }

            if (rootTaskId < 0) {
                continue;
            }
            if (line.indexOf("configuration=") >= 0) {
                final Matcher modeMatcher = WINDOWING_MODE_PATTERN.matcher(line);
                if (modeMatcher.find()) {
                    windowingMode = modeMatcher.group(1);
                }
                final Matcher typeMatcher = ACTIVITY_TYPE_PATTERN.matcher(line);
                if (typeMatcher.find()) {
                    activityType = typeMatcher.group(1);
                }
                continue;
            }
            if (targetDisplayId >= 0 && displayId != targetDisplayId) {
                continue;
            }

            final String trimmed = line.trim();
            if (!trimmed.startsWith("taskId=")) {
                continue;
            }
            if (!trimmed.contains(" topActivity=ComponentInfo{")) {
                continue;
            }
            final int colon = trimmed.indexOf(':');
            if (colon < 0) {
                continue;
            }
            final int taskId = parseInt(trimmed.substring("taskId=".length(), colon));
            int componentStart = colon + 1;
            while (componentStart < trimmed.length()
                    && Character.isWhitespace(trimmed.charAt(componentStart))) {
                componentStart++;
            }
            int componentEnd = componentStart;
            while (componentEnd < trimmed.length()
                    && !Character.isWhitespace(trimmed.charAt(componentEnd))) {
                componentEnd++;
            }
            final String component = componentEnd > componentStart
                    ? trimmed.substring(componentStart, componentEnd) : null;
            if (component == null || "unknown".equals(component)
                    || component.indexOf('/') <= 0) {
                continue;
            }
            final String packageName = component.substring(0, component.indexOf('/'));
            if (!isPackageNameSafe(packageName)) {
                continue;
            }
            final Matcher topActivityMatcher = TOP_ACTIVITY_PATTERN.matcher(trimmed);
            final String topActivityName = topActivityMatcher.find()
                    ? topActivityMatcher.group(1) : component;
            final boolean visible = trimmed.contains(" visible=true");
            final boolean home = "home".equals(activityType);
            final boolean active = visible && !home && !activeAssigned;
            final Rect taskBounds = parseBounds(trimmed);
            if (active) {
                activeAssigned = true;
            }
            tasks.add(new TaskEntry(rootTaskId, taskId, displayId, packageName,
                    component, topActivityName, windowingMode,
                    taskBounds == null ? rootBounds : taskBounds,
                    home, visible, active));
        }
        return tasks;
    }

    private static int parseInt(final String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static Rect parseBounds(final String value) {
        final Matcher matcher = BOUNDS_PATTERN.matcher(value);
        if (!matcher.find()) {
            return null;
        }
        final Rect bounds = new Rect(parseInt(matcher.group(1)), parseInt(matcher.group(2)),
                parseInt(matcher.group(3)), parseInt(matcher.group(4)));
        return bounds.isEmpty() ? null : bounds;
    }

    private static boolean isPackageNameSafe(final String packageName) {
        if (packageName == null || packageName.length() == 0 || packageName.length() > 220) {
            return false;
        }
        for (int i = 0; i < packageName.length(); i++) {
            final char ch = packageName.charAt(i);
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9') || ch == '_' || ch == '.') {
                continue;
            }
            return false;
        }
        return packageName.indexOf('.') > 0 && packageName.indexOf("..") < 0;
    }

    private static CommandResult runRootCommand(final String command) {
        try {
            return new CommandResult(
                    true, PrivilegedCommandRunner.run(command));
        } catch (IOException e) {
            Log.d(TAG, "privileged command unavailable: " + command + ": "
                    + e.getMessage());
            return new CommandResult(
                    false, e.getMessage() == null ? "I/O error" : e.getMessage());
        }
    }

    static final class Snapshot {
        final List<TaskEntry> tasks;
        final List<TaskEntry> phoneTasks;
        final boolean rootAvailable;
        final String error;

        Snapshot(final List<TaskEntry> tasks, final boolean rootAvailable,
                final String error) {
            this(tasks, Collections.<TaskEntry>emptyList(), rootAvailable, error);
        }

        Snapshot(final List<TaskEntry> tasks, final List<TaskEntry> phoneTasks,
                final boolean rootAvailable, final String error) {
            this.tasks = Collections.unmodifiableList(new ArrayList<>(tasks));
            this.phoneTasks = Collections.unmodifiableList(new ArrayList<>(phoneTasks));
            this.rootAvailable = rootAvailable;
            this.error = rootAvailable ? "" : error;
        }
    }

    static final class TaskEntry {
        final int rootTaskId;
        final int taskId;
        final int displayId;
        final String packageName;
        final String componentName;
        final String topActivityName;
        final String windowingMode;
        final Rect bounds;
        final boolean home;
        final boolean visible;
        final boolean active;

        TaskEntry(final int rootTaskId, final int taskId, final int displayId,
                final String packageName, final String componentName,
                final String topActivityName, final String windowingMode,
                final Rect bounds, final boolean home, final boolean visible,
                final boolean active) {
            this.rootTaskId = rootTaskId;
            this.taskId = taskId;
            this.displayId = displayId;
            this.packageName = packageName;
            this.componentName = componentName;
            this.topActivityName = topActivityName;
            this.windowingMode = windowingMode;
            this.bounds = bounds == null ? new Rect() : new Rect(bounds);
            this.home = home;
            this.visible = visible;
            this.active = active;
        }

        boolean isFreeform() {
            return "freeform".equals(windowingMode);
        }

        boolean hasCrossPackageTopActivity() {
            if (topActivityName == null) {
                return false;
            }
            final int separator = topActivityName.indexOf('/');
            return separator > 0
                    && !packageName.equals(topActivityName.substring(0, separator));
        }
    }

    static final class ActionResult {
        final boolean success;
        final String message;

        ActionResult(final boolean success, final String message) {
            this.success = success;
            this.message = message;
        }
    }

    private static final class CommandResult {
        final boolean success;
        final String output;

        CommandResult(final boolean success, final String output) {
            this.success = success;
            this.output = output;
        }
    }
}

package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

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
    private static final String PHONE_FREEFORM_CLEANUP_COMMAND =
            "io.github.mekhontsev.magicdesk.PhoneFreeformCleanupCommand";
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
                final CommandResult command = runCommand(CMD + " activity stack list");
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
        runAction(TaskFocusCommands.createShellCommand(
                Collections.singletonList(task.taskId)), callback);
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

        runAction(
                TaskFocusCommands.createShellCommand(orderedTaskIds),
                callback);
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
                final CommandResult stackResult = runCommand(CMD + " activity stack list");
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
                        final CommandResult resizeResult = runCommand(
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
                final CommandResult restoreResult = runCommand(
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

    static void minimizeTask(final TaskEntry task, final TaskEntry focusTask,
            final ActionCallback callback) {
        if (!isUsableTask(task) || !task.isFreeform()
                || !isUsableTask(focusTask)
                || task.displayId != focusTask.displayId
                || task.taskId == focusTask.taskId) {
            complete(callback, false, "invalid task");
            return;
        }
        runAction(createTaskWindowingCommand(
                "minimize " + task.displayId + " " + task.taskId
                        + " " + focusTask.taskId), callback);
    }

    static void restoreTask(final TaskEntry task, final ActionCallback callback) {
        if (!isUsableTask(task) || !task.isFreeform()) {
            complete(callback, false, "invalid task");
            return;
        }
        runAction(createTaskWindowingCommand(
                "restore " + task.displayId + " " + task.taskId), callback);
    }

    static void configureDesktopHost(final TaskEntry task,
            final ActionCallback callback) {
        if (!isUsableTask(task)) {
            complete(callback, false, "invalid desktop host");
            return;
        }
        runAction(createTaskWindowingCommand(
                "desktop-host " + task.displayId + " " + task.taskId),
                callback);
    }

    static void normalizePhoneFreeformTasks(
            final ActionCallback callback) {
        if (!ShellAccess.isReady()) {
            complete(callback, true, "task cleanup unavailable");
            return;
        }
        runAction(
                AppProcessCommand.run(PHONE_FREEFORM_CLEANUP_COMMAND),
                callback);
    }

    static void setFullscreen(final TaskEntry task,
            final boolean preserveClient,
            final ActionCallback callback) {
        if (!isUsableTask(task)) {
            complete(callback, false, "invalid task");
            return;
        }
        runAction(preserveClient
                        ? createClientPreservingFullscreenTransitionCommand(
                                task.displayId, task.taskId)
                        : createFullscreenTransitionCommand(
                                task.displayId, task.taskId),
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

    static void moveTaskToDisplay(
            final TaskEntry task,
            final int targetDisplayId,
            final ActionCallback callback) {
        if (!isUsableTask(task) || targetDisplayId < 0
                || targetDisplayId == task.displayId) {
            complete(callback, false, "invalid target display");
            return;
        }
        runAction(
                CMD + " activity display move-stack "
                        + task.rootTaskId + " " + targetDisplayId,
                callback);
    }

    static void forceStop(final String packageName, final ActionCallback callback) {
        if (!PackageNameValidator.isSafe(packageName)
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
                final CommandResult result = runCommand(command);
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

    private static String createTaskControlCommand(final String arguments) {
        return AppProcessCommand.run(TASK_CONTROL_COMMAND, arguments);
    }

    private static String createTaskWindowingCommand(final String arguments) {
        return AppProcessCommand.run(
                TASK_WINDOWING_COMMAND, arguments);
    }

    static String createFullscreenTransitionCommand(final int displayId,
            final int taskId) {
        return AppProcessCommand.run(
                TASK_FULLSCREEN_TRANSITION_COMMAND,
                displayId + " " + taskId);
    }

    static String createClientPreservingFullscreenTransitionCommand(
            final int displayId, final int taskId) {
        return AppProcessCommand.run(
                TASK_CLIENT_PRESERVING_FULLSCREEN_TRANSITION_COMMAND,
                displayId + " " + taskId);
    }

    static String createFreeformTransitionCommand(final int displayId,
            final int taskId, final Rect bounds) {
        final String arguments = "freeform " + displayId + " " + taskId
                + " " + bounds.left + " " + bounds.top
                + " " + bounds.right + " " + bounds.bottom;
        return AppProcessCommand.run(TASK_WINDOWING_COMMAND, arguments);
    }

    static String createCaptionInsetsCommand(final int displayId, final int taskId,
            final boolean excluded) {
        return AppProcessCommand.run(
                TASK_CAPTION_INSETS_COMMAND,
                displayId + " " + taskId + " "
                        + (excluded ? "exclude" : "include"));
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
        boolean activeAssigned = false;
        for (final TaskStackParser.Entry parsed :
                TaskStackParser.parse(output)) {
            if (targetDisplayId >= 0
                    && parsed.displayId != targetDisplayId) {
                continue;
            }
            final boolean home = parsed.isHome();
            final boolean active =
                    parsed.visible && !home && !activeAssigned;
            if (active) {
                activeAssigned = true;
            }
            tasks.add(new TaskEntry(
                    parsed.rootTaskId,
                    parsed.taskId,
                    parsed.displayId,
                    parsed.packageName,
                    parsed.componentName,
                    parsed.topActivityName,
                    parsed.windowingMode,
                    new Rect(
                            parsed.bounds.left,
                            parsed.bounds.top,
                            parsed.bounds.right,
                            parsed.bounds.bottom),
                    home,
                    parsed.visible,
                    active));
        }
        return tasks;
    }

    private static CommandResult runCommand(final String command) {
        try {
            return new CommandResult(
                    true, ShellAccess.run(command));
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
        final boolean available;
        final String error;

        Snapshot(final List<TaskEntry> tasks, final boolean available,
                final String error) {
            this(tasks, Collections.<TaskEntry>emptyList(), available, error);
        }

        Snapshot(final List<TaskEntry> tasks, final List<TaskEntry> phoneTasks,
                final boolean available, final String error) {
            this.tasks = Collections.unmodifiableList(new ArrayList<>(tasks));
            this.phoneTasks = Collections.unmodifiableList(new ArrayList<>(phoneTasks));
            this.available = available;
            this.error = available ? "" : error;
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

        boolean isFullscreen() {
            return "fullscreen".equals(windowingMode);
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

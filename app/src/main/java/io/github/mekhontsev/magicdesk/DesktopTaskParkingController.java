package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.util.Log;
import android.view.Display;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Keeps live desktop tasks parked on the phone while desktop mode is closed. */
final class DesktopTaskParkingController {
    private static final String TAG = "MagicDeskTaskParking";
    private static final String RETURN_COMMAND =
            "io.github.mekhontsev.magicdesk.ConsoleTaskReturnCommand";

    private static final Object LOCK = new Object();
    private static final Map<Integer, ParkedTask> PARKED =
            new LinkedHashMap<>();
    private static DesktopDisplayTarget sPendingTarget;
    private static boolean sRestoreInProgress;

    private DesktopTaskParkingController() {
    }

    interface ResultCallback {
        void onComplete(boolean success);
    }

    static void park(
            final DesktopDisplayTarget source,
            final ResultCallback callback) {
        if (source == null
                || source.displayId <= Display.DEFAULT_DISPLAY) {
            complete(callback, true);
            return;
        }
        TaskCommandQueue.execute(() -> parkNow(source, callback));
    }

    static void restoreWhenReady(final DesktopDisplayTarget target) {
        if (target == null
                || target.displayId <= Display.DEFAULT_DISPLAY) {
            return;
        }
        synchronized (LOCK) {
            if (PARKED.isEmpty()) {
                return;
            }
            sPendingTarget = target;
        }
        restoreIfReady(target);
    }

    static void onDesktopHostReady(final int displayId) {
        final DesktopDisplayTarget target;
        synchronized (LOCK) {
            target = sPendingTarget != null
                            && sPendingTarget.displayId == displayId
                    ? sPendingTarget
                    : DesktopRuntimeBridge.getDesktopTarget(displayId);
        }
        restoreIfReady(target);
    }

    static void clear() {
        synchronized (LOCK) {
            PARKED.clear();
            sPendingTarget = null;
        }
    }

    private static void parkNow(
            final DesktopDisplayTarget source,
            final ResultCallback callback) {
        final TaskRepository.Snapshot snapshot =
                TaskRepository.loadNow(source.displayId);
        if (!snapshot.available) {
            recordFailure("Could not inspect desktop tasks", snapshot.error);
            complete(callback, false);
            return;
        }
        final Rect workArea;
        try {
            workArea = FloatingWindowController.getWorkAreaBounds(
                    source.displayId);
        } catch (IOException error) {
            recordFailure("Could not read desktop work area", error.getMessage());
            complete(callback, false);
            return;
        }
        final List<ParkedTask> candidates = captureTasks(
                snapshot.tasks, workArea);
        if (candidates.isEmpty()) {
            complete(callback, true);
            return;
        }

        final StringBuilder arguments = new StringBuilder("selected ")
                .append(source.displayId);
        for (final ParkedTask task : candidates) {
            arguments.append(' ').append(task.taskId);
        }
        final String output;
        try {
            output = ShellAccess.run(AppProcessCommand.run(
                    RETURN_COMMAND, arguments.toString()));
        } catch (IOException error) {
            recordFailure("Could not park desktop tasks", error.getMessage());
            complete(callback, false);
            return;
        }

        final Set<Integer> returnedTaskIds = parseReturnedTaskIds(output);
        synchronized (LOCK) {
            for (final ParkedTask task : candidates) {
                if (returnedTaskIds.contains(Integer.valueOf(task.taskId))) {
                    PARKED.remove(Integer.valueOf(task.taskId));
                    PARKED.put(Integer.valueOf(task.taskId), task);
                }
            }
            sPendingTarget = null;
        }
        final boolean success = returnedTaskIds.size() == candidates.size();
        if (!success) {
            recordFailure(
                    "Some desktop tasks could not be parked",
                    "display=" + source.displayId
                            + " expected=" + candidates.size()
                            + " parked=" + returnedTaskIds.size());
        }
        Log.i(TAG, "parked=" + returnedTaskIds.size()
                + " display=" + source.displayId);
        complete(callback, success);
    }

    private static void restoreIfReady(final DesktopDisplayTarget target) {
        if (target == null
                || target.displayId <= Display.DEFAULT_DISPLAY
                || !DesktopRuntimeBridge.isDesktopReadyOnDisplay(
                        target.displayId)) {
            return;
        }
        synchronized (LOCK) {
            if (PARKED.isEmpty() || sRestoreInProgress) {
                return;
            }
            sRestoreInProgress = true;
        }
        TaskCommandQueue.execute(() -> restoreNow(target));
    }

    private static void restoreNow(final DesktopDisplayTarget target) {
        final List<ParkedTask> saved;
        synchronized (LOCK) {
            saved = new ArrayList<>(PARKED.values());
        }
        final Set<Integer> completed = new HashSet<>();
        final List<Integer> restoredTaskIds = new ArrayList<>();
        try {
            final TaskRepository.Snapshot phone =
                    TaskRepository.loadNow(Display.DEFAULT_DISPLAY);
            final TaskRepository.Snapshot desktop =
                    TaskRepository.loadNow(target.displayId);
            if (!phone.available || !desktop.available) {
                throw new IOException(!phone.available
                        ? phone.error : desktop.error);
            }

            for (int index = saved.size() - 1; index >= 0; index--) {
                final ParkedTask parked = saved.get(index);
                final TaskRepository.TaskEntry alreadyRestored =
                        findLiveTask(desktop.tasks, parked);
                if (alreadyRestored != null) {
                    try {
                        restoreMode(alreadyRestored, parked, target);
                        completed.add(Integer.valueOf(parked.taskId));
                        restoredTaskIds.add(Integer.valueOf(parked.taskId));
                    } catch (IOException | RuntimeException error) {
                        Log.w(TAG,
                                "Could not finish restoring task="
                                        + parked.taskId,
                                error);
                    }
                    continue;
                }
                final TaskRepository.TaskEntry live =
                        findLiveTask(phone.tasks, parked);
                if (live == null) {
                    // The task was closed by Android or by the user. Its record
                    // expires here; a closed task is never launched again.
                    completed.add(Integer.valueOf(parked.taskId));
                    continue;
                }
                try {
                    moveToDesktop(live, parked, target);
                    completed.add(Integer.valueOf(parked.taskId));
                    restoredTaskIds.add(Integer.valueOf(parked.taskId));
                } catch (IOException | RuntimeException error) {
                    Log.w(TAG, "Could not restore task=" + parked.taskId, error);
                }
            }
            restoreStackState(target.displayId, saved, restoredTaskIds);
        } catch (IOException | RuntimeException error) {
            recordFailure("Could not restore parked desktop tasks",
                    error.getMessage());
        } finally {
            synchronized (LOCK) {
                for (final Integer taskId : completed) {
                    PARKED.remove(taskId);
                }
                if (sPendingTarget != null
                        && sPendingTarget.displayId == target.displayId) {
                    sPendingTarget = null;
                }
                sRestoreInProgress = false;
            }
            if (!restoredTaskIds.isEmpty()) {
                MagicDeskRuntime.refreshDesktopTasks();
            }
            Log.i(TAG, "restored=" + restoredTaskIds.size()
                    + " display=" + target.displayId);
        }
    }

    private static void moveToDesktop(
            final TaskRepository.TaskEntry live,
            final ParkedTask parked,
            final DesktopDisplayTarget target) throws IOException {
        final Rect bounds = FloatingWindowController.getWindowBounds(
                target.displayId, parked.bounds);
        final DesktopDisplayDriver driver =
                DesktopDisplayDrivers.forTarget(target);
        final String moveCommand = driver.features().rootTaskTransfer
                ? TaskDisplayAreaLaunchCommand.createPhysicalMoveCommand(
                        live.taskId,
                        live.rootTaskId,
                        Display.DEFAULT_DISPLAY,
                        target.displayId,
                        bounds)
                : TaskDisplayAreaLaunchCommand.createMoveCommand(
                        live.taskId,
                        Display.DEFAULT_DISPLAY,
                        target.displayId,
                        bounds);
        ShellAccess.run(moveCommand);
        restoreMode(live, parked, target);
    }

    private static void restoreMode(
            final TaskRepository.TaskEntry task,
            final ParkedTask parked,
            final DesktopDisplayTarget target) throws IOException {
        if (parked.fullscreen) {
            if (!task.isFullscreen() || task.displayId != target.displayId) {
                ShellAccess.run(
                        TaskRepository.createFullscreenTransitionCommand(
                                target.displayId, task.taskId));
            }
            return;
        }
        if (task.displayId == target.displayId && !task.isFreeform()) {
            final Rect bounds = FloatingWindowController.getWindowBounds(
                    target.displayId, parked.bounds);
            ShellAccess.run(TaskRepository.createFreeformTransitionCommand(
                    target.displayId, task.taskId, bounds));
        }
    }

    private static void restoreStackState(
            final int displayId,
            final List<ParkedTask> savedTopFirst,
            final List<Integer> restoredTaskIds) throws IOException {
        if (restoredTaskIds.isEmpty()) {
            return;
        }
        final Set<Integer> restored = new HashSet<>(restoredTaskIds);
        final List<Integer> visibleBottomFirst = new ArrayList<>();
        final List<Integer> hidden = new ArrayList<>();
        for (int index = savedTopFirst.size() - 1; index >= 0; index--) {
            final ParkedTask task = savedTopFirst.get(index);
            if (!restored.contains(Integer.valueOf(task.taskId))) {
                continue;
            }
            (task.visible ? visibleBottomFirst : hidden)
                    .add(Integer.valueOf(task.taskId));
        }
        final TaskRepository.Snapshot snapshot =
                TaskRepository.loadNow(displayId);
        if (!snapshot.available) {
            throw new IOException(snapshot.error);
        }
        final TaskRepository.TaskEntry desktopHost =
                findDesktopHost(snapshot.tasks);
        final int focusTaskId = visibleBottomFirst.isEmpty()
                ? desktopHost == null ? -1 : desktopHost.taskId
                : visibleBottomFirst.get(
                        visibleBottomFirst.size() - 1).intValue();
        if (focusTaskId >= 0) {
            for (final Integer taskId : hidden) {
                ShellAccess.run(AppProcessCommand.run(
                        TaskWindowingCommand.class.getName(),
                        "minimize " + displayId + " " + taskId
                                + " " + focusTaskId));
            }
        }
        if (!visibleBottomFirst.isEmpty()) {
            ShellAccess.run(TaskFocusCommands.createShellCommand(
                    displayId, visibleBottomFirst));
        } else if (desktopHost != null) {
            ShellAccess.run(TaskFocusCommands.createShellCommand(
                    displayId,
                    Collections.singletonList(
                            Integer.valueOf(desktopHost.taskId))));
        }
    }

    static List<ParkedTask> captureTasks(
            final List<TaskRepository.TaskEntry> tasks,
            final Rect workArea) {
        final List<ParkedTask> result = new ArrayList<>();
        if (tasks == null || workArea == null
                || workArea.right <= workArea.left
                || workArea.bottom <= workArea.top) {
            return result;
        }
        for (final TaskRepository.TaskEntry task : tasks) {
            if (!shouldParkTask(task)) {
                continue;
            }
            result.add(new ParkedTask(
                    task.taskId,
                    task.packageName,
                    !task.isFreeform(),
                    task.visible,
                    task.hasBounds()
                            ? RelativeWindowBounds.from(task.bounds, workArea)
                            : null));
        }
        return result;
    }

    static boolean shouldParkTask(final TaskRepository.TaskEntry task) {
        return DesktopManagedTaskPolicy.isManagedApplicationTask(task);
    }

    static TaskRepository.TaskEntry findLiveTask(
            final List<TaskRepository.TaskEntry> tasks,
            final ParkedTask parked) {
        if (tasks == null || parked == null) {
            return null;
        }
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task.taskId == parked.taskId
                    && parked.packageName.equals(task.packageName)
                    && DesktopManagedTaskPolicy.isManagedApplicationTask(task)) {
                return task;
            }
        }
        return null;
    }

    private static TaskRepository.TaskEntry findDesktopHost(
            final List<TaskRepository.TaskEntry> tasks) {
        if (tasks == null) {
            return null;
        }
        for (final TaskRepository.TaskEntry task : tasks) {
            if (DesktopTaskController.isDesktopHostTask(task)) {
                return task;
            }
        }
        return null;
    }

    private static Set<Integer> parseReturnedTaskIds(final String output) {
        final Set<Integer> result = new HashSet<>();
        if (output == null) {
            return result;
        }
        for (final String line : output.split("\\r?\\n")) {
            if (!line.startsWith("task-returned=")) {
                continue;
            }
            try {
                result.add(Integer.valueOf(Integer.parseInt(
                        line.substring("task-returned=".length()).trim())));
            } catch (NumberFormatException ignored) {
                // The summary count remains useful if a vendor changes output.
            }
        }
        return result;
    }

    private static void recordFailure(
            final String summary, final String detail) {
        Log.w(TAG, summary + ": " + detail);
        CompatibilityDiagnostics.record(
                "DISPLAY-TASKS-002",
                summary,
                detail == null ? "unknown error" : detail);
    }

    private static void complete(
            final ResultCallback callback, final boolean success) {
        if (callback != null) {
            callback.onComplete(success);
        }
    }

    static final class ParkedTask {
        final int taskId;
        final String packageName;
        final boolean fullscreen;
        final boolean visible;
        final RelativeWindowBounds bounds;

        ParkedTask(
                final int taskId,
                final String packageName,
                final boolean fullscreen,
                final boolean visible,
                final RelativeWindowBounds bounds) {
            this.taskId = taskId;
            this.packageName = packageName;
            this.fullscreen = fullscreen;
            this.visible = visible;
            this.bounds = bounds;
        }
    }
}

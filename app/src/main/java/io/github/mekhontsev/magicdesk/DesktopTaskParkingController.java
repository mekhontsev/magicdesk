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
final class DesktopTaskParkingController implements DesktopTaskParkingRuntime {
    private static final String TAG = "MagicDeskTaskParking";
    private static final String RETURN_COMMAND =
            "io.github.mekhontsev.magicdesk.DesktopTaskReturnCommand";

    private final Object mLock = new Object();
    private final Map<Integer, ParkedTask> mParked =
            new LinkedHashMap<>();
    private record Observation(List<ParkedTask> topFirst, boolean ownershipReady,
            Set<Integer> ownedTaskIds) { }
    private final Map<Integer, Observation> mObservations = new LinkedHashMap<>();
    private static final class Restore {
        final DesktopDisplayTarget target;
        final List<ParkedTask> tasks;
        boolean running;

        Restore(final DesktopDisplayTarget target, final List<ParkedTask> tasks) {
            this.target = target;
            this.tasks = tasks;
        }
    }

    private final Map<Integer, Restore> mRestores = new LinkedHashMap<>();
    private long mGeneration;

    DesktopTaskParkingController() {
    }

    boolean isWorkspacePrepared(final DesktopSessionSnapshot session) {
        synchronized (mLock) {
            return !session.policy().restoreWorkspace
                    || !mRestores.containsKey(session.activeWorkspaceDisplayId());
        }
    }

    void observe(
            final int displayId,
            final List<TaskRepository.TaskEntry> tasks,
            final Rect workArea,
            final boolean ownershipReady,
            final Set<Integer> ownedTaskIds) {
        if (displayId < Display.DEFAULT_DISPLAY
                || tasks == null
                || !hasArea(workArea)) {
            return;
        }
        final Set<Integer> ownership = copyTaskIds(ownedTaskIds);
        final boolean filterByOwnership = displayId == Display.DEFAULT_DISPLAY;
        final List<ParkedTask> observed = filterByOwnership
                && !ownershipReady
                        ? Collections.emptyList()
                        : captureTasks(
                                tasks,
                                workArea,
                                filterByOwnership ? ownership : null);
        final DesktopDisplayTarget pendingTarget;
        synchronized (mLock) {
            mObservations.put(displayId, new Observation(observed, ownershipReady, ownership));
            final Restore restore = mRestores.get(displayId);
            pendingTarget = restore == null ? null : restore.target;
        }
        if (pendingTarget != null) {
            restoreIfReady(pendingTarget);
        }
    }

    @Override
    public void park(
            final DesktopDisplayTarget source,
            final boolean remember,
            final ResultCallback callback) {
        if (source == null
                || source.workspaceDisplayId < Display.DEFAULT_DISPLAY) {
            complete(callback, false);
            return;
        }
        final long generation;
        synchronized (mLock) {
            generation = mGeneration;
        }
        TaskCommandQueue.execute(
                () -> parkNow(source, remember, callback, generation));
    }

    @Override
    public void preserve(final int displayId) {
        final int saved;
        synchronized (mLock) {
            final Observation observation = mObservations.get(displayId);
            if (observation == null || (displayId == Display.DEFAULT_DISPLAY
                            && !observation.ownershipReady)) {
                return;
            }
            cancelRestore(displayId);
            mergePreservedTasks(mParked, observation.topFirst, false);
            saved = observation.topFirst.size();
        }
        Log.i(TAG, "preserved=" + saved + " display=" + displayId);
    }

    void restoreWhenReady(final DesktopDisplayTarget target) {
        if (target == null
                || target.workspaceDisplayId < Display.DEFAULT_DISPLAY) {
            return;
        }
        if (!DesktopRuntimeBridge.getSessionSnapshot(target.workspaceDisplayId)
                .policy().restoreWorkspace) {
            return;
        }
        synchronized (mLock) {
            if (mParked.isEmpty() || mRestores.containsKey(target.workspaceDisplayId)) {
                return;
            }
            mRestores.put(target.workspaceDisplayId,
                    new Restore(target, new ArrayList<>(mParked.values())));
            mParked.clear();
        }
        restoreIfReady(target);
    }

    @Override
    public void onDesktopHostReady(final int displayId) {
        if (!DesktopRuntimeBridge.getSessionSnapshot(displayId)
                .policy().restoreWorkspace) {
            return;
        }
        final DesktopDisplayTarget target;
        synchronized (mLock) {
            final Restore restore = mRestores.get(displayId);
            target = restore == null ? null : restore.target;
        }
        restoreIfReady(target);
    }

    @Override
    public void clear() {
        synchronized (mLock) {
            mGeneration++;
            mParked.clear();
            mObservations.clear();
            mRestores.clear();
        }
    }

    private void parkNow(
            final DesktopDisplayTarget source,
            final boolean remember,
            final ResultCallback callback,
            final long generation) {
        final TaskRepository.Snapshot snapshot =
                TaskRepository.loadNow(source.workspaceDisplayId);
        if (!snapshot.available) {
            recordFailure("Could not inspect desktop tasks", snapshot.error);
            complete(callback, false);
            return;
        }
        final Rect workArea;
        try {
            workArea = FloatingWindowController.getWorkAreaBounds(
                    source.workspaceDisplayId);
        } catch (IOException error) {
            recordFailure("Could not read desktop work area", error.getMessage());
            complete(callback, false);
            return;
        }
        final boolean filterByOwnership =
                source.workspaceDisplayId == Display.DEFAULT_DISPLAY;
        final boolean ownershipReady;
        final Set<Integer> ownedTaskIds;
        synchronized (mLock) {
            final Observation observation = mObservations.get(source.workspaceDisplayId);
            ownershipReady = observation != null && observation.ownershipReady;
            ownedTaskIds = observation != null ? observation.ownedTaskIds : Collections.emptySet();
        }
        if (filterByOwnership && !ownershipReady) {
            recordFailure(
                    "Could not inspect desktop task ownership",
                    "display=" + source.workspaceDisplayId);
            complete(callback, false);
            return;
        }
        final List<ParkedTask> candidates = captureTasks(
                snapshot.tasks,
                workArea,
                filterByOwnership ? ownedTaskIds : null);
        observe(
                source.workspaceDisplayId,
                snapshot.tasks,
                workArea,
                ownershipReady,
                ownedTaskIds);
        if (candidates.isEmpty()) {
            complete(callback, true);
            return;
        }

        if (source.workspaceDisplayId == Display.DEFAULT_DISPLAY) {
            synchronized (mLock) {
                if (remember && generation == mGeneration) {
                    mergePreservedTasks(mParked, candidates, true);
                }
            }
            Log.i(TAG, "parked=" + candidates.size()
                    + " display=" + source.workspaceDisplayId);
            complete(callback, true);
            return;
        }

        final StringBuilder arguments = new StringBuilder("selected ")
                .append(source.workspaceDisplayId);
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
        synchronized (mLock) {
            if (remember && generation == mGeneration) {
                // Preserve the complete observed workspace even when Android
                // migrates one task only after the display disappears. The
                // restore path still requires the exact live task ID and
                // package, so a failed or closed task is never relaunched.
                mergePreservedTasks(mParked, candidates, true);
            }
        }
        final boolean success = returnedTaskIds.size() == candidates.size();
        if (!success) {
            recordFailure(
                    "Some desktop tasks could not be parked",
                    "display=" + source.workspaceDisplayId
                            + " expected=" + candidates.size()
                            + " parked=" + returnedTaskIds.size());
        }
        Log.i(TAG, "parked=" + returnedTaskIds.size()
                + " display=" + source.workspaceDisplayId);
        complete(callback, success);
    }

    private void restoreIfReady(final DesktopDisplayTarget target) {
        if (target == null
                || target.workspaceDisplayId < Display.DEFAULT_DISPLAY
                || !DesktopRuntimeBridge.isDesktopReadyOnDisplay(
                        target.workspaceDisplayId)
                || !MagicDeskRuntime.isTaskObserverReady(target.workspaceDisplayId)) {
            return;
        }
        final Restore restore;
        synchronized (mLock) {
            restore = mRestores.get(target.workspaceDisplayId);
            final Observation observation = mObservations.get(target.workspaceDisplayId);
            if (restore == null || restore.running
                    || observation == null || !observation.ownershipReady) {
                return;
            }
            restore.running = true;
        }
        TaskCommandQueue.execute(() -> restoreNow(restore));
    }

    private void restoreNow(final Restore restore) {
        final DesktopDisplayTarget target = restore.target;
        final List<ParkedTask> saved = restore.tasks;
        if (!isCurrentRestore(restore)) { return; }
        final Set<Integer> completed = new HashSet<>();
        final List<Integer> restoredTaskIds = new ArrayList<>();
        try {
            final TaskRepository.Snapshot phone =
                    TaskRepository.loadNow(Display.DEFAULT_DISPLAY);
            final TaskRepository.Snapshot desktop =
                    TaskRepository.loadNow(target.workspaceDisplayId);
            if (!phone.available || !desktop.available) {
                throw new IOException(!phone.available
                        ? phone.error : desktop.error);
            }

            for (int index = saved.size() - 1; index >= 0; index--) {
                if (!isCurrentRestore(restore)) {
                    return;
                }
                final ParkedTask parked = saved.get(index);
                final TaskRepository.TaskEntry alreadyRestored =
                        findLiveTask(desktop.tasks, parked);
                if (alreadyRestored != null) {
                    try {
                        restoreTask(alreadyRestored, parked, target);
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
                    restoreTask(live, parked, target);
                    completed.add(Integer.valueOf(parked.taskId));
                    restoredTaskIds.add(Integer.valueOf(parked.taskId));
                } catch (IOException | RuntimeException error) {
                    Log.w(TAG, "Could not restore task=" + parked.taskId, error);
                }
            }
            if (isCurrentRestore(restore)) {
                restoreFreeformLayout(target, saved, restoredTaskIds);
            }
        } catch (IOException | RuntimeException error) {
            recordFailure("Could not restore parked desktop tasks",
                    error.getMessage());
        }
        // Readiness follows the final workspace commit, not just the queued
        // request. Failure still completes preparation so input remains usable.
        if (!isCurrentRestore(restore)) {
            return;
        }
        try {
            restoreStackState(target.workspaceDisplayId, saved, restoredTaskIds,
                    () -> finishRestore(restore,
                            completed, restoredTaskIds.size()));
        } catch (IOException | RuntimeException error) {
            recordFailure("Could not restore desktop stack", error.getMessage());
            finishRestore(restore,
                    completed, restoredTaskIds.size());
        }
    }

    private void finishRestore(
            final Restore restore,
            final Set<Integer> completed,
            final int restoredCount) {
        final boolean current;
        synchronized (mLock) {
            current = mRestores.remove(restore.target.workspaceDisplayId, restore);
            if (current) {
                for (final ParkedTask task : restore.tasks) {
                    if (!completed.contains(task.taskId)) { mParked.putIfAbsent(task.taskId, task); }
                }
            }
        }
        if (current) { MagicDeskRuntime.refreshDesktopTasks(); }
        Log.i(TAG, "restored=" + restoredCount + " display=" + restore.target.workspaceDisplayId);
    }

    private boolean isCurrentRestore(final Restore restore) {
        synchronized (mLock) {
            return mRestores.get(restore.target.workspaceDisplayId) == restore;
        }
    }

    void retire(final int displayId) {
        synchronized (mLock) {
            cancelRestore(displayId);
            mObservations.remove(displayId);
        }
    }

    private void cancelRestore(final int displayId) {
        final Restore restore = mRestores.remove(displayId);
        if (restore != null) {
            for (final ParkedTask task : restore.tasks) { mParked.putIfAbsent(task.taskId, task); }
        }
    }

    private static void moveToDesktop(
            final TaskRepository.TaskEntry live,
            final ParkedTask parked,
            final DesktopDisplayTarget target) throws IOException {
        final Rect bounds = parked.fullscreen
                ? null
                : FloatingWindowController.getWindowBounds(
                        target.workspaceDisplayId, parked.bounds);
        final int densityDpi =
                DesktopTaskPresentationPolicy.resolveDensityDpi(
                        AppProfile.current(MagicDeskApplication.applicationContext())
                                .applicationForUser(parked.userId, parked.packageName), target.workspaceDisplayId);
        if (parked.fullscreen) {
            DesktopTaskTransfer.moveFullscreen(
                    live.taskId,
                    live.displayId,
                    target.workspaceDisplayId,
                    densityDpi);
        } else {
            DesktopTaskTransfer.moveFreeform(
                    live.taskId,
                    live.displayId,
                    target.workspaceDisplayId,
                    bounds,
                    densityDpi);
        }
    }

    private static void restoreTask(
            final TaskRepository.TaskEntry live,
            final ParkedTask parked,
            final DesktopDisplayTarget target) throws IOException {
        if (live.displayId != target.workspaceDisplayId) {
            moveToDesktop(live, parked, target);
        } else {
            restoreMode(live, parked, target);
        }
        if (parked.fullscreen
                && !MagicDeskRuntime.attachFullscreenTask(
                        target.workspaceDisplayId,
                        parked.taskId,
                        DesktopTaskPresentationPolicy.resolveDensityDpi(
                                AppProfile.current(MagicDeskApplication.applicationContext())
                                .applicationForUser(parked.userId, parked.packageName), target.workspaceDisplayId))) {
            throw new IOException(
                    "could not attach restored fullscreen task="
                            + parked.taskId);
        }
    }

    private static void restoreMode(
            final TaskRepository.TaskEntry task,
            final ParkedTask parked,
            final DesktopDisplayTarget target) throws IOException {
        if (parked.fullscreen) {
            // The topology owns the fullscreen mode and parent transition.
            return;
        }
        if (task.displayId == target.workspaceDisplayId && !task.isFreeform()) {
            final Rect bounds = FloatingWindowController.getWindowBounds(
                    target.workspaceDisplayId, parked.bounds);
            if (!MagicDeskRuntime.attachWindowedTask(
                    target.workspaceDisplayId,
                    task.taskId,
                    bounds,
                    DesktopTaskPresentationPolicy.resolveDensityDpi(
                            AppProfile.current(MagicDeskApplication.applicationContext())
                                .applicationForUser(parked.userId, parked.packageName), target.workspaceDisplayId))) {
                throw new IOException(
                        "could not attach restored windowed task="
                                + task.taskId);
            }
        }
    }

    private static void restoreStackState(
            final int displayId,
            final List<ParkedTask> savedTopFirst,
            final List<Integer> restoredTaskIds,
            final Runnable completion) throws IOException {
        if (restoredTaskIds.isEmpty()) {
            completion.run();
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
        final List<Integer> restoreOrder = !visibleBottomFirst.isEmpty()
                ? visibleBottomFirst
                : desktopHost == null
                        ? Collections.emptyList()
                        : Collections.singletonList(
                                Integer.valueOf(desktopHost.taskId));
        if (!restoreOrder.isEmpty()) {
            MagicDeskRuntime.restoreDesktopWorkspace(
                    displayId, restoreOrder, result -> {
                        completion.run();
                        if (!result.success) {
                            Log.w(TAG, "Could not restore desktop stack: "
                                    + result.message);
                        }
                    });
        } else {
            completion.run();
        }
    }

    private static void restoreFreeformLayout(
            final DesktopDisplayTarget target,
            final List<ParkedTask> savedTopFirst,
            final List<Integer> restoredTaskIds) throws IOException {
        if (restoredTaskIds.isEmpty()) {
            return;
        }
        final Set<Integer> restored = new HashSet<>(restoredTaskIds);
        final StringBuilder arguments = new StringBuilder("restore-layout ")
                .append(target.workspaceDisplayId);
        int count = 0;
        for (int index = savedTopFirst.size() - 1; index >= 0; index--) {
            final ParkedTask task = savedTopFirst.get(index);
            if (task.fullscreen
                    || !restored.contains(Integer.valueOf(task.taskId))) {
                continue;
            }
            final Rect bounds = FloatingWindowController.getWindowBounds(
                    target.workspaceDisplayId, task.bounds);
            arguments.append(' ').append(task.taskId)
                    .append(' ').append(bounds.left)
                    .append(' ').append(bounds.top)
                    .append(' ').append(bounds.right)
                    .append(' ').append(bounds.bottom);
            count++;
        }
        if (count == 0) {
            return;
        }
        ShellAccess.run(AppProcessCommand.run(
                TaskWindowingCommand.class.getName(),
                arguments.toString()));
    }

    static List<ParkedTask> captureTasks(
            final List<TaskRepository.TaskEntry> tasks,
            final Rect workArea) {
        return captureTasks(tasks, workArea, null);
    }

    static List<ParkedTask> captureTasks(
            final List<TaskRepository.TaskEntry> tasks,
            final Rect workArea,
            final Set<Integer> ownedTaskIds) {
        final List<ParkedTask> result = new ArrayList<>();
        if (tasks == null || workArea == null
                || workArea.right <= workArea.left
                || workArea.bottom <= workArea.top) {
            return result;
        }
        for (final TaskRepository.TaskEntry task : tasks) {
            if (!shouldParkTask(task)
                    || (ownedTaskIds != null
                            && !ownedTaskIds.contains(
                                    Integer.valueOf(task.taskId)))) {
                continue;
            }
            result.add(new ParkedTask(
                    task.taskId,
                    task.userId,
                    task.packageName,
                    !task.isFreeform(),
                    task.visible,
                    task.hasBounds()
                            ? RelativeWindowBounds.from(task.bounds, workArea)
                            : null));
        }
        return result;
    }

    static void mergePreservedTasks(
            final Map<Integer, ParkedTask> parked,
            final List<ParkedTask> observedTopFirst,
            final boolean replaceExisting) {
        if (parked == null || observedTopFirst == null) {
            return;
        }
        for (final ParkedTask task : observedTopFirst) {
            final Integer taskId = Integer.valueOf(task.taskId);
            if (replaceExisting || !parked.containsKey(taskId)) {
                parked.remove(taskId);
                parked.put(taskId, task);
            }
        }
    }

    private static boolean hasArea(final Rect bounds) {
        return bounds != null
                && bounds.right > bounds.left
                && bounds.bottom > bounds.top;
    }

    private static Set<Integer> copyTaskIds(
            final Set<Integer> taskIds) {
        return taskIds == null || taskIds.isEmpty()
                ? Collections.emptySet() : new HashSet<>(taskIds);
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
                    && parked.userId >= 0 && task.userId == parked.userId
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
        final int userId;
        final String packageName;
        final boolean fullscreen;
        final boolean visible;
        final RelativeWindowBounds bounds;

        ParkedTask(
                final int taskId,
                final int userId,
                final String packageName,
                final boolean fullscreen,
                final boolean visible,
                final RelativeWindowBounds bounds) {
            this.taskId = taskId;
            this.userId = userId;
            this.packageName = packageName;
            this.fullscreen = fullscreen;
            this.visible = visible;
            this.bounds = bounds;
        }
    }
}

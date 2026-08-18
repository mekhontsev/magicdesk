package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.os.Handler;
import android.util.Log;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class DesktopWindowTransitionController {
    interface RuntimeState {
        int displayId();
        boolean isRunning();
        boolean beginAppFullscreenTask(int taskId, Rect restoreBounds);
        boolean releaseFullscreenTask(int taskId);
        boolean closeFullscreenTask(int taskId);
        void focusTask(int taskId);
        void scheduleRefresh();
    }

    static final int SHORTCUT_FULLSCREEN = 1;
    static final int SHORTCUT_RESTORE = 2;
    static final int SHORTCUT_SNAP_LEFT = 3;
    static final int SHORTCUT_SNAP_RIGHT = 4;
    static final int SHORTCUT_CLOSE = 5;
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;

    private static final String TAG = "MagicDeskTasks";
    private final Handler mHandler;
    private final NativeWindowBoundsController mNativeWindowBounds;
    private final DesktopDisplayTaskState mDisplayTaskState;
    private final DesktopTaskRuntimeRegistry mTaskStates;
    private final RuntimeState mRuntimeState;

    DesktopWindowTransitionController(
            final Handler handler,
            final NativeWindowBoundsController nativeWindowBounds,
            final DesktopDisplayTaskState displayTaskState,
            final DesktopTaskRuntimeRegistry taskStates,
            final RuntimeState runtimeState) {
        mHandler = handler;
        mNativeWindowBounds = nativeWindowBounds;
        mDisplayTaskState = displayTaskState;
        mTaskStates = taskStates;
        mRuntimeState = runtimeState;
    }

    boolean hasManagedFullscreenState(final int taskId) {
        final DesktopTaskRuntimeState state = mTaskStates.find(taskId);
        return state != null && state.fullscreenRestoreBounds() != null;
    }

    void observeWindowingModeChange(
            final int taskId,
            final int previousMode,
            final int currentMode) {
        final DesktopTaskRuntimeState state = mTaskStates.find(taskId);
        if (!shouldForgetManagedFullscreenState(
                previousMode,
                currentMode,
                state != null && state.isFullscreenTransition(),
                state != null && state.isAppRequestedFullscreen())) {
            return;
        }
        // A native restore bypasses MagicDesk's restore callback. Forget its
        // old fullscreen ownership before classifying the next transition.
        if (state != null) {
            state.clearFullscreenRestoreBounds();
            state.setAppRequestedFullscreen(false);
        }
    }

    static boolean shouldForgetManagedFullscreenState(
            final int previousMode,
            final int currentMode,
            final boolean transitionPending,
            final boolean appRequested) {
        return previousMode == WINDOWING_MODE_FULLSCREEN
                && currentMode == WINDOWING_MODE_FREEFORM
                && !transitionPending
                && !appRequested;
    }

    static boolean supportsFullscreenTask(final int shortcut) {
        return shortcut == SHORTCUT_CLOSE
                || shortcut == SHORTCUT_SNAP_LEFT
                || shortcut == SHORTCUT_SNAP_RIGHT;
    }

    void applyShortcut(
            final TaskRepository.TaskEntry task,
            final int shortcut,
            final TaskRepository.TaskEntry minimizeFocusTask) {
        switch (shortcut) {
            case SHORTCUT_FULLSCREEN:
                makeFullscreen(task, false);
                break;
            case SHORTCUT_RESTORE:
                restoreOrMinimize(task, minimizeFocusTask);
                break;
            case SHORTCUT_SNAP_LEFT:
                snap(task, true);
                break;
            case SHORTCUT_SNAP_RIGHT:
                snap(task, false);
                break;
            case SHORTCUT_CLOSE:
                close(task);
                break;
            default:
                Log.w(TAG, "unknown native window shortcut=" + shortcut);
        }
    }

    void restoreTopFullscreenTask() {
        final int displayId = mRuntimeState.displayId();
        TaskRepository.load(displayId, snapshot -> mHandler.post(() -> {
            if (!mRuntimeState.isRunning()
                    || mRuntimeState.displayId() != displayId) {
                return;
            }
            final TaskRepository.TaskEntry task =
                    findTopFullscreenTask(snapshot.tasks);
            if (task == null) {
                return;
            }
            mTaskStates.state(task.taskId)
                    .setManualImmersiveOverride(true);
            restoreFullscreenTask(task, true);
        }));
    }

    void handleImmersiveRequest(
            final int taskId,
            final boolean requestingImmersive,
            final boolean initialSample,
            final boolean restoredByObserver) {
        final DesktopTaskRuntimeState state = mTaskStates.state(taskId);
        final Boolean previous =
                state.updateImmersiveRequested(requestingImmersive);
        if (restoredByObserver) {
            finishObservedAppFullscreenRestore(state);
            return;
        }
        if (initialSample) {
            if (state.isAppRequestedFullscreen()) {
                mRuntimeState.scheduleRefresh();
            }
            return;
        }
        final boolean newImmersiveRequest = isNewImmersiveRequest(
                previous, requestingImmersive, initialSample);
        final boolean startupWindowedRequest = newImmersiveRequest
                && state.consumeStartupWindowed();
        if (shouldClearManualImmersiveOverride(
                newImmersiveRequest, startupWindowedRequest)) {
            state.setManualImmersiveOverride(false);
        } else if (startupWindowedRequest) {
            Log.i(TAG, "kept startup task windowed task=" + taskId);
        }
        mRuntimeState.scheduleRefresh();
    }

    private void finishObservedAppFullscreenRestore(
            final DesktopTaskRuntimeState state) {
        final boolean transitionPending = state.isFullscreenTransition();
        state.finishFullscreenTransition();
        state.clearFullscreenRestoreBounds();
        state.setAppRequestedFullscreen(false);
        if (transitionPending && !hasFullscreenTransitions()) {
            finishWorkspaceTransition(mRuntimeState.displayId(), true);
        } else {
            mRuntimeState.scheduleRefresh();
        }
    }

    void noteManualFreeformTransition(final int taskId) {
        if (taskId >= 0) {
            mTaskStates.state(taskId)
                    .setManualImmersiveOverride(true);
        }
    }

    void beginExplicitWindowedLaunch(final int taskId) {
        if (taskId < 0) {
            return;
        }
        final DesktopTaskRuntimeState state = mTaskStates.state(taskId);
        state.setManualImmersiveOverride(true);
        state.setStartupWindowed(true);
        Log.i(TAG, "protecting startup windowed task=" + taskId);
    }

    void finishExplicitWindowedLaunch(final int taskId) {
        if (taskId >= 0) {
            final DesktopTaskRuntimeState state = mTaskStates.find(taskId);
            if (state != null) {
                state.setStartupWindowed(false);
            }
        }
    }

    static boolean isNewImmersiveRequest(
            final Boolean previous,
            final boolean requestingImmersive,
            final boolean initialSample) {
        return !initialSample
                && requestingImmersive
                && Boolean.FALSE.equals(previous);
    }

    static boolean shouldClearManualImmersiveOverride(
            final boolean newImmersiveRequest,
            final boolean startupWindowedRequest) {
        return !startupWindowedRequest
                && newImmersiveRequest;
    }

    void forgetTaskState(final int taskId) {
        final DesktopTaskRuntimeState state = mTaskStates.find(taskId);
        final boolean transitionPending = state != null
                && state.isFullscreenTransition();
        mTaskStates.forget(taskId);
        if (transitionPending && !hasFullscreenTransitions()) {
            finishWorkspaceTransition(
                    mRuntimeState.displayId(), false);
        }
    }

    void reconcile(
            final List<TaskRepository.TaskEntry> allTasks,
            final List<TaskRepository.TaskEntry> visibleFreeformTasks) {
        reconcileSubmittedAppFullscreenTransitions(allTasks);
        reconcileImmersiveRequests(allTasks, visibleFreeformTasks);
    }

    private void minimize(final TaskRepository.TaskEntry task,
            final TaskRepository.TaskEntry focusTask) {
        TaskRepository.minimizeTask(task, focusTask, result -> {
            if (!result.success) {
                Log.w(TAG, "native minimize failed task=" + task.taskId
                        + " message=" + result.message);
            }
        });
    }

    private void close(final TaskRepository.TaskEntry task) {
        if (task.isFullscreen()
                && mRuntimeState.closeFullscreenTask(task.taskId)) {
            return;
        }
        TaskRepository.closeTask(task, result -> {
            if (!result.success) {
                Log.w(TAG, "native close failed task=" + task.taskId
                        + " message=" + result.message);
            }
        });
    }

    private void snap(
            final TaskRepository.TaskEntry task,
            final boolean left) {
        if (!task.isFreeform()) {
            snapFullscreenTask(task, left);
            return;
        }
        final DesktopTaskRuntimeState state =
                mTaskStates.state(task.taskId);
        if (state.windowRestoreBounds() == null) {
            final Rect nativeRestoreBounds =
                    mNativeWindowBounds.getMaximizeRestoreBounds(task.taskId);
            state.setWindowRestoreBounds(
                    nativeRestoreBounds != null
                            ? nativeRestoreBounds : task.bounds);
        }
        mNativeWindowBounds.requestBounds(
                task, mNativeWindowBounds.getSnappedBounds(left), true);
    }

    private void snapFullscreenTask(
            final TaskRepository.TaskEntry task,
            final boolean left) {
        final int taskId = task.taskId;
        final DesktopTaskRuntimeState state = mTaskStates.state(taskId);
        if (!state.beginFullscreenRestoreTransition()) {
            return;
        }
        final Rect savedBounds = state.fullscreenRestoreBounds();
        final Rect restoreBounds;
        if (savedBounds != null) {
            restoreBounds = savedBounds;
        } else {
            try {
                restoreBounds = FloatingWindowController
                        .getDefaultWindowBounds(mRuntimeState.displayId());
            } catch (IOException e) {
                state.finishFullscreenTransition();
                Log.w(TAG,
                        "cannot resolve fullscreen snap restore bounds", e);
                return;
            }
        }
        state.setManualImmersiveOverride(true);
        final Rect targetBounds =
                mNativeWindowBounds.getSnappedBounds(left);
        releaseFullscreenParent(taskId);
        TaskRepository.setFreeform(
                task, targetBounds,
                result -> mHandler.post(() -> {
                    if (!mTaskStates.isCurrent(taskId, state)) {
                        return;
                    }
                    state.finishFullscreenTransition();
                    if (!result.success) {
                        Log.w(TAG,
                                "fullscreen snap failed task="
                                        + task.taskId
                                        + " message=" + result.message);
                        return;
                    }
                    state.setWindowRestoreBounds(restoreBounds);
                    state.clearFullscreenRestoreBounds();
                    state.setAppRequestedFullscreen(false);
                    rememberWindowed(task, targetBounds);
                    mRuntimeState.focusTask(taskId);
                    mRuntimeState.scheduleRefresh();
                }));
    }

    private void restoreOrMinimize(
            final TaskRepository.TaskEntry task,
            final TaskRepository.TaskEntry minimizeFocusTask) {
        final DesktopTaskRuntimeState state =
                mTaskStates.find(task.taskId);
        final Rect savedBounds = state == null
                ? null : state.windowRestoreBounds();
        if (savedBounds == null) {
            minimize(task, minimizeFocusTask);
            return;
        }
        resize(task, savedBounds, true);
    }

    private void resize(
            final TaskRepository.TaskEntry task,
            final Rect targetBounds,
            final boolean clearRestoreBounds) {
        final DesktopTaskRuntimeState state =
                mTaskStates.state(task.taskId);
        TaskRepository.resizeTaskBounds(
                task,
                targetBounds,
                result -> mHandler.post(() -> {
                    if (!mTaskStates.isCurrent(task.taskId, state)) {
                        return;
                    }
                    if (!result.success) {
                        Log.w(TAG,
                                "native bounds change failed task="
                                        + task.taskId
                                        + " message=" + result.message);
                        return;
                    }
                    if (clearRestoreBounds) {
                        state.clearWindowRestoreBounds();
                    }
                    mRuntimeState.scheduleRefresh();
                }));
    }

    private void makeFullscreen(
            final TaskRepository.TaskEntry task,
            final boolean appRequested) {
        final int taskId = task.taskId;
        final DesktopTaskRuntimeState state = mTaskStates.state(taskId);
        if (!state.beginFullscreenTransition()) {
            return;
        }
        final int displayId = mRuntimeState.displayId();
        state.setFullscreenRestoreBounds(task.bounds);
        if (!appRequested) {
            rememberWindowed(task, task.bounds);
        }
        mNativeWindowBounds.clearForFullscreen(task.taskId);
        if (appRequested) {
            state.setAppRequestedFullscreen(true);
        }
        mDisplayTaskState.beginFullscreenTransition(
                mDisplayTaskState.visibleTasks(), task.taskId);
        final TaskRepository.ActionCallback callback =
                result -> mHandler.post(() -> {
                    if (!mTaskStates.isCurrent(taskId, state)) {
                        return;
                    }
                    if (!result.success) {
                        state.finishFullscreenTransition();
                        finishWorkspaceTransition(displayId, false);
                        state.clearFullscreenRestoreBounds();
                        if (appRequested) {
                            state.setAppRequestedFullscreen(false);
                        }
                        Log.w(TAG,
                                "fullscreen shortcut failed task="
                                        + task.taskId
                                        + " message=" + result.message);
                        return;
                    }
                    if (appRequested) {
                        // Submission is asynchronous. A task snapshot confirms
                        // when WindowManager has applied the transition.
                        mRuntimeState.scheduleRefresh();
                        return;
                    }
                    state.finishFullscreenTransition();
                    finishWorkspaceTransition(displayId, true);
                    if (BuiltInDesktopAppCatalog.remembersWindowState(task)) {
                        AppWindowStateStore.rememberMode(
                                task.packageName,
                                AppWindowState.Mode.FULLSCREEN);
                    }
                });
        if (appRequested && mRuntimeState.beginAppFullscreenTask(
                taskId, task.bounds)) {
            mRuntimeState.scheduleRefresh();
            return;
        }
        if (appRequested) {
            TaskRepository.setAppRequestedFullscreen(task, callback);
        } else {
            TaskRepository.setFullscreen(
                    task,
                    callback);
        }
    }

    private void restoreFullscreenTask(
            final TaskRepository.TaskEntry task,
            final boolean userRequested) {
        final int taskId = task.taskId;
        final DesktopTaskRuntimeState state = mTaskStates.state(taskId);
        if (!state.beginFullscreenRestoreTransition()) {
            return;
        }
        final Rect savedBounds = state.fullscreenRestoreBounds();
        final Rect targetBounds;
        if (savedBounds != null) {
            targetBounds = savedBounds;
        } else {
            try {
                targetBounds = FloatingWindowController
                        .getDefaultWindowBounds(mRuntimeState.displayId());
            } catch (IOException e) {
                state.finishFullscreenTransition();
                Log.w(TAG,
                        "cannot resolve fullscreen restore bounds", e);
                return;
            }
        }
        releaseFullscreenParent(taskId);
        final TaskRepository.ActionCallback callback =
                result -> mHandler.post(() -> finishFullscreenRestore(
                        task,
                        state,
                        targetBounds,
                        userRequested,
                        result.success,
                        result.message));
        if (task.isFreeform()) {
            TaskRepository.rebuildFreeform(task, targetBounds, callback);
        } else {
            TaskRepository.setFreeform(task, targetBounds, callback);
        }
    }

    private void releaseFullscreenParent(final int taskId) {
        if (!mRuntimeState.releaseFullscreenTask(taskId)) {
            // Continue through the normal path. If the observer disconnected,
            // Binder death already removed its organizer-owned task area.
            Log.w(TAG, "fullscreen parent release unavailable task=" + taskId);
        }
    }

    private void finishFullscreenRestore(
            final TaskRepository.TaskEntry task,
            final DesktopTaskRuntimeState state,
            final Rect targetBounds,
            final boolean userRequested,
            final boolean success,
            final String message) {
        if (!mTaskStates.isCurrent(task.taskId, state)) {
            return;
        }
        state.finishFullscreenTransition();
        if (!success) {
            Log.w(TAG, "fullscreen restore failed task=" + task.taskId
                    + " message=" + message);
            return;
        }
        state.clearFullscreenRestoreBounds();
        state.setAppRequestedFullscreen(false);
        if (userRequested) {
            rememberWindowed(task, targetBounds);
        }
        mRuntimeState.focusTask(task.taskId);
        mRuntimeState.scheduleRefresh();
    }

    private void reconcileImmersiveRequests(
            final List<TaskRepository.TaskEntry> allTasks,
            final List<TaskRepository.TaskEntry> visibleFreeformTasks) {
        final Set<Integer> liveTaskIds = new HashSet<>();
        for (final TaskRepository.TaskEntry task : allTasks) {
            liveTaskIds.add(Integer.valueOf(task.taskId));
        }
        final Set<Integer> staleAutomaticTasks =
                appRequestedFullscreenTaskIds();
        staleAutomaticTasks.removeAll(liveTaskIds);
        for (final Integer taskId : staleAutomaticTasks) {
            forgetTaskState(taskId.intValue());
        }

        for (final Integer taskId : appRequestedFullscreenTaskIds()) {
            final DesktopTaskRuntimeState state =
                    mTaskStates.find(taskId.intValue());
            if (state == null || state.isImmersiveRequested()) {
                continue;
            }
            final TaskRepository.TaskEntry task =
                    findTask(allTasks, taskId.intValue());
            if (task == null) {
                state.setAppRequestedFullscreen(false);
                state.clearFullscreenRestoreBounds();
                continue;
            }
            if (state.isFullscreenTransition()) {
                if (state.isFullscreenRestoreTransition()) {
                    continue;
                }
                if (!task.isFreeform()) {
                    continue;
                }
                // The application can enter and leave fullscreen between two
                // observer samples. Its freeform state proves that the entry
                // transition was overtaken; complete that state before
                // starting the canonical restore below.
                state.finishFullscreenTransition();
                if (!hasFullscreenTransitions()) {
                    finishWorkspaceTransition(
                            mRuntimeState.displayId(), true);
                }
            }
            // An activity orientation change can make firmware restore the
            // task mode before WMShell restores its caption and desktop
            // surface. Do not skip the canonical transaction merely because
            // the observed task is already nominally freeform.
            restoreFullscreenTask(task, false);
        }

        if (visibleFreeformTasks.isEmpty()) {
            return;
        }
        final TaskRepository.TaskEntry topTask =
                visibleFreeformTasks.get(0);
        final DesktopTaskRuntimeState topState =
                mTaskStates.find(topTask.taskId);
        if (topTask.active
                && topState != null
                && topState.isImmersiveRequested()
                && !topState.isAppRequestedFullscreen()
                && !topState.hasManualImmersiveOverride()
                && !topState.isFullscreenTransition()) {
            makeFullscreen(topTask, true);
        }
    }

    private void reconcileSubmittedAppFullscreenTransitions(
            final List<TaskRepository.TaskEntry> tasks) {
        if (!hasFullscreenTransitions()
                || !hasAppRequestedFullscreenTasks()) {
            return;
        }
        final Set<Integer> liveTaskIds = new HashSet<>();
        final Set<Integer> completedTaskIds = new HashSet<>();
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task == null) {
                continue;
            }
            final Integer taskId = Integer.valueOf(task.taskId);
            liveTaskIds.add(taskId);
            final DesktopTaskRuntimeState state =
                    mTaskStates.find(task.taskId);
            if (state != null
                    && state.isFullscreenTransition()
                    && state.isFullscreenEntryTransition()
                    && state.isAppRequestedFullscreen()
                    && !task.isFreeform()) {
                completedTaskIds.add(taskId);
            }
        }
        for (final Integer taskId : completedTaskIds) {
            final DesktopTaskRuntimeState state =
                    mTaskStates.find(taskId.intValue());
            if (state != null) {
                state.finishFullscreenTransition();
            }
        }
        if (!completedTaskIds.isEmpty()
                && !hasFullscreenTransitions()) {
            finishWorkspaceTransition(
                    mRuntimeState.displayId(), true);
        }

        final Set<Integer> removedTaskIds = fullscreenTransitionTaskIds();
        removedTaskIds.retainAll(appRequestedFullscreenTaskIds());
        removedTaskIds.removeAll(liveTaskIds);
        for (final Integer taskId : removedTaskIds) {
            forgetTaskState(taskId.intValue());
        }
    }

    private boolean hasFullscreenTransitions() {
        for (final DesktopTaskRuntimeState state : mTaskStates.snapshot()) {
            if (state.isFullscreenTransition()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAppRequestedFullscreenTasks() {
        for (final DesktopTaskRuntimeState state : mTaskStates.snapshot()) {
            if (state.isAppRequestedFullscreen()) {
                return true;
            }
        }
        return false;
    }

    private Set<Integer> fullscreenTransitionTaskIds() {
        final Set<Integer> taskIds = new HashSet<>();
        for (final DesktopTaskRuntimeState state : mTaskStates.snapshot()) {
            if (state.isFullscreenTransition()) {
                taskIds.add(Integer.valueOf(state.taskId()));
            }
        }
        return taskIds;
    }

    private Set<Integer> appRequestedFullscreenTaskIds() {
        final Set<Integer> taskIds = new HashSet<>();
        for (final DesktopTaskRuntimeState state : mTaskStates.snapshot()) {
            if (state.isAppRequestedFullscreen()) {
                taskIds.add(Integer.valueOf(state.taskId()));
            }
        }
        return taskIds;
    }

    private void finishWorkspaceTransition(
            final int displayId,
            final boolean success) {
        mDisplayTaskState.finishFullscreenTransition(success);
        if (mRuntimeState.isRunning()
                && mRuntimeState.displayId() == displayId) {
            mRuntimeState.scheduleRefresh();
        }
    }

    private void rememberWindowed(
            final TaskRepository.TaskEntry task,
            final Rect bounds) {
        if (!BuiltInDesktopAppCatalog.remembersWindowState(task)) {
            return;
        }
        final RelativeWindowBounds relative = RelativeWindowBounds.from(
                bounds, mNativeWindowBounds.getTaskbarMaximizedBounds());
        if (relative != null) {
            AppWindowStateStore.rememberWindowed(
                    task.packageName, relative);
        }
    }

    private static TaskRepository.TaskEntry findTopFullscreenTask(
            final List<TaskRepository.TaskEntry> tasks) {
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task.active && !task.isFreeform()
                    && DesktopManagedTaskPolicy
                            .isControllableApplicationTask(task)) {
                return task;
            }
        }
        return null;
    }

    private static TaskRepository.TaskEntry findTask(
            final List<TaskRepository.TaskEntry> tasks,
            final int taskId) {
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task.taskId == taskId) {
                return task;
            }
        }
        return null;
    }
}

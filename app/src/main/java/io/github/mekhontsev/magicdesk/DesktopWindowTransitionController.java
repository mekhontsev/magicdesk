package io.github.mekhontsev.magicdesk;

import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class DesktopWindowTransitionController {
    interface RuntimeState {
        int displayId();
        boolean isRunning();
        TaskRepository.Snapshot selectDesktopTaskSnapshot(
                TaskRepository.Snapshot snapshot);
        void focusTask(int taskId);
        void demoteTask(int taskId);
        void scheduleRefresh();
    }

    enum RestoreShortcutAction {
        RESTORE_FULLSCREEN,
        RESTORE_WINDOW_BOUNDS,
        DEMOTE
    }

    static final int SHORTCUT_FULLSCREEN = 1;
    static final int SHORTCUT_RESTORE = 2;
    static final int SHORTCUT_SNAP_LEFT = 3;
    static final int SHORTCUT_SNAP_RIGHT = 4;
    static final int SHORTCUT_CLOSE = 5;
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final long STARTUP_IMMERSIVE_SETTLE_MILLIS = 1_000L;

    private static final String TAG = "MagicDeskTasks";
    private final Handler mHandler;
    private final NativeWindowBoundsController mNativeWindowBounds;
    private final DesktopDisplayTaskState mDisplayTaskState;
    private final DesktopTaskRuntimeRegistry mTaskStates;
    private final RuntimeState mRuntimeState;
    private final DesktopWindowTransitionGateway mGateway;

    DesktopWindowTransitionController(
            final Handler handler,
            final NativeWindowBoundsController nativeWindowBounds,
            final DesktopDisplayTaskState displayTaskState,
            final DesktopTaskRuntimeRegistry taskStates,
            final RuntimeState runtimeState,
            final DesktopWindowTransitionGateway gateway) {
        mHandler = handler;
        mNativeWindowBounds = nativeWindowBounds;
        mDisplayTaskState = displayTaskState;
        mTaskStates = taskStates;
        mRuntimeState = runtimeState;
        mGateway = gateway;
    }

    boolean hasManagedFullscreenState(final int taskId) {
        final DesktopTaskRuntimeState state = mTaskStates.find(taskId);
        return state != null && state.fullscreenRestoreBounds() != null;
    }

    void observeWindowingModeChange(
            final int taskId,
            final int previousMode,
            final int currentMode,
            final boolean backgroundAppFullscreenReleased) {
        final DesktopTaskRuntimeState state = mTaskStates.find(taskId);
        if (backgroundAppFullscreenReleased) {
            finishObservedAppFullscreenRestore(state);
            return;
        }
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

    private void finishObservedAppFullscreenRestore(
            final DesktopTaskRuntimeState state) {
        if (state == null) {
            return;
        }
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
                || shortcut == SHORTCUT_RESTORE
                || shortcut == SHORTCUT_SNAP_LEFT
                || shortcut == SHORTCUT_SNAP_RIGHT;
    }

    static RestoreShortcutAction classifyRestoreShortcut(
            final boolean fullscreen,
            final boolean hasWindowRestoreBounds) {
        if (fullscreen) {
            return RestoreShortcutAction.RESTORE_FULLSCREEN;
        }
        return hasWindowRestoreBounds
                ? RestoreShortcutAction.RESTORE_WINDOW_BOUNDS
                : RestoreShortcutAction.DEMOTE;
    }

    void applyShortcut(
            final TaskRepository.TaskEntry task,
            final int shortcut) {
        switch (shortcut) {
            case SHORTCUT_FULLSCREEN:
                makeFullscreen(task, false);
                break;
            case SHORTCUT_RESTORE:
                applyRestoreShortcut(task);
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

    void makeTaskFullscreen(
            final TaskRepository.TaskEntry task,
            final TaskRepository.ActionCallback completion) {
        makeFullscreen(task, false, completion);
    }

    void restoreTopFullscreenTask() {
        final int displayId = mRuntimeState.displayId();
        TaskRepository.load(displayId, snapshot -> mHandler.post(() -> {
            if (!mRuntimeState.isRunning()
                    || mRuntimeState.displayId() != displayId) {
                return;
            }
            final TaskRepository.Snapshot workspace =
                    mRuntimeState.selectDesktopTaskSnapshot(snapshot);
            final TaskRepository.TaskEntry task =
                    findTopFullscreenTask(workspace.available
                            ? workspace.tasks
                            : Collections.emptyList());
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
            final boolean foreground) {
        if (!initialSample && foreground) {
            DesktopWindowTransitionProvenance.noteApplicationRequest(
                    taskId, requestingImmersive);
        }
        final DesktopTaskRuntimeState state = mTaskStates.state(taskId);
        if (shouldIgnoreBackgroundImmersiveExit(
                requestingImmersive, initialSample, foreground)) {
            // Losing input focus temporarily exposes system bars. Do not retain
            // that sample as an application-requested fullscreen exit. TaskInfo
            // focus is unreliable for organizer children, so the shell observer
            // reports the actual focused input window.
            state.clearImmersiveRequested();
            return;
        }
        final Boolean previous =
                state.updateImmersiveObservation(
                        requestingImmersive, foreground);
        if (initialSample) {
            // The shell launch returns once the task exists, before a cold
            // client publishes its initial insets state. Start the bounded
            // startup-request window from that first client sample instead.
            state.observeStartupWindowedInitialSample(
                    requestingImmersive,
                    SystemClock.uptimeMillis(),
                    STARTUP_IMMERSIVE_SETTLE_MILLIS);
            if (shouldReconcileInitialImmersiveSample(
                    previous,
                    requestingImmersive,
                    state.isAppRequestedFullscreen())) {
                mRuntimeState.scheduleRefresh();
            }
            return;
        }
        final boolean newImmersiveRequest = isNewImmersiveRequest(
                previous, requestingImmersive, initialSample);
        final boolean startupWindowedRequest = newImmersiveRequest
                && state.consumeStartupWindowed(
                        SystemClock.uptimeMillis());
        if (shouldClearManualImmersiveOverride(
                newImmersiveRequest, startupWindowedRequest)) {
            state.setManualImmersiveOverride(false);
        } else if (startupWindowedRequest) {
            Log.i(TAG, "kept startup task windowed task=" + taskId);
        }
        mRuntimeState.scheduleRefresh();
    }

    void observeRequestedOrientation(
            final int taskId,
            final int requestedOrientation) {
        mTaskStates.state(taskId)
                .setRequestedOrientation(requestedOrientation);
        mRuntimeState.scheduleRefresh();
    }

    static boolean shouldIgnoreBackgroundImmersiveExit(
            final boolean requestingImmersive,
            final boolean initialSample,
            final boolean foreground) {
        return !requestingImmersive && !initialSample && !foreground;
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

    static boolean isNewImmersiveRequest(
            final Boolean previous,
            final boolean requestingImmersive,
            final boolean initialSample) {
        return !initialSample
                && requestingImmersive
                && Boolean.FALSE.equals(previous);
    }

    static boolean shouldReconcileInitialImmersiveSample(
            final Boolean previous,
            final boolean requestingImmersive,
            final boolean appRequestedFullscreen) {
        // A display reconfiguration resets the shell monitor's client sample,
        // but not this task's runtime state. Reconcile an already-observed
        // request once the task is visible again without treating a new
        // process's first immersive state as a fresh request.
        return appRequestedFullscreen
                || (requestingImmersive && Boolean.TRUE.equals(previous));
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
            final List<TaskRepository.TaskEntry> visibleFreeformTasks,
            final boolean focusHandoffPending) {
        reconcileSubmittedAppFullscreenTransitions(allTasks);
        reconcileImmersiveRequests(
                allTasks, visibleFreeformTasks, focusHandoffPending);
    }

    private void close(final TaskRepository.TaskEntry task) {
        if (!task.isFullscreen() && !task.isFreeform()) {
            Log.w(TAG, "native close ignored unsupported task="
                    + task.taskId + " mode=" + task.windowingMode);
            return;
        }
        // Closing a task is an Android task-lifecycle operation. The runtime
        // only intercepts it when a managed fullscreen parent needs an
        // explicit focus handoff; ordinary freeform tasks use ATMS directly.
        MagicDeskRuntime.closeTask(task, result -> mHandler.post(() -> {
            if (!result.success) {
                Log.w(TAG, "native close failed task=" + task.taskId
                        + " message=" + result.message);
            }
        }));
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
        final Rect workAreaBounds =
                mNativeWindowBounds.getTaskbarMaximizedBounds();
        final TaskRepository.ActionCallback callback =
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
                    rememberWindowed(task, targetBounds, workAreaBounds);
                    mRuntimeState.focusTask(taskId);
                    mRuntimeState.scheduleRefresh();
                });
        final DesktopWindowTransitionRequest request =
                DesktopWindowTransitionRequest.restoreFreeform(
                        mRuntimeState.displayId(),
                        taskId,
                        targetBounds,
                        "native-window-snap-shortcut");
        submitRequired(request, callback);
    }

    private void applyRestoreShortcut(
            final TaskRepository.TaskEntry task) {
        final DesktopTaskRuntimeState state =
                mTaskStates.find(task.taskId);
        final Rect savedBounds = state == null
                ? null : state.windowRestoreBounds();
        switch (classifyRestoreShortcut(
                task.isFullscreen(), savedBounds != null)) {
            case RESTORE_FULLSCREEN:
                mTaskStates.state(task.taskId)
                        .setManualImmersiveOverride(true);
                restoreFullscreenTask(task, true);
                break;
            case RESTORE_WINDOW_BOUNDS:
                resize(task, savedBounds, true);
                break;
            case DEMOTE:
                mRuntimeState.demoteTask(task.taskId);
                break;
            default:
                throw new IllegalStateException("unknown restore action");
        }
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
        makeFullscreen(task, appRequested, null);
    }

    private void makeFullscreen(
            final TaskRepository.TaskEntry task,
            final boolean appRequested,
            final TaskRepository.ActionCallback completion) {
        final int taskId = task.taskId;
        final DesktopTaskRuntimeState state = mTaskStates.state(taskId);
        if (!state.beginFullscreenTransition()) {
            complete(
                    completion,
                    false,
                    "task transition already active");
            return;
        }
        final int displayId = mRuntimeState.displayId();
        state.setFullscreenRestoreBounds(task.bounds);
        if (!appRequested) {
            rememberWindowed(
                    task,
                    task.bounds,
                    mNativeWindowBounds.getTaskbarMaximizedBounds());
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
                        complete(completion, false, result.message);
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
                                BuiltInDesktopAppCatalog.appIdentityKey(task),
                                AppWindowState.Mode.FULLSCREEN);
                    }
                    complete(completion, true, result.message);
                });
        final DesktopWindowTransitionRequest request = appRequested
                ? DesktopWindowTransitionRequest.enterAppFullscreen(
                        displayId,
                        taskId,
                        task.bounds,
                        "application-immersive-reconciliation")
                : DesktopWindowTransitionRequest.enterFullscreen(
                        displayId,
                        taskId,
                        "native-window-fullscreen-shortcut");
        submitRequired(request, callback);
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
        final Rect workAreaBounds =
                mNativeWindowBounds.getTaskbarMaximizedBounds();
        final TaskRepository.ActionCallback callback =
                result -> mHandler.post(() -> finishFullscreenRestore(
                        task,
                        state,
                        targetBounds,
                        workAreaBounds,
                        userRequested,
                        result.success,
                        result.message));
        final DesktopWindowTransitionRequest request =
                DesktopWindowTransitionRequest.restoreFreeform(
                        mRuntimeState.displayId(),
                        taskId,
                        targetBounds,
                        userRequested
                                ? "native-window-restore-shortcut"
                                : "application-immersive-reconciliation");
        submitRequired(request, callback);
    }

    private boolean submit(
            final DesktopWindowTransitionRequest request,
            final TaskRepository.ActionCallback callback) {
        DesktopWindowTransitionProvenance.noteMagicDeskCommand(request);
        final boolean accepted = mGateway.submit(request, callback);
        DesktopWindowTransitionDiagnostics.recordSubmission(
                request, accepted);
        return accepted;
    }

    private void submitRequired(
            final DesktopWindowTransitionRequest request,
            final TaskRepository.ActionCallback callback) {
        if (submit(request, callback)) {
            return;
        }
        callback.onComplete(new TaskRepository.ActionResult(
                false,
                "desktop transition gateway unavailable"));
    }

    private static void complete(
            final TaskRepository.ActionCallback callback,
            final boolean success,
            final String message) {
        if (callback != null) {
            callback.onComplete(new TaskRepository.ActionResult(
                    success, message == null ? "" : message));
        }
    }

    private void finishFullscreenRestore(
            final TaskRepository.TaskEntry task,
            final DesktopTaskRuntimeState state,
            final Rect targetBounds,
            final Rect workAreaBounds,
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
            rememberWindowed(task, targetBounds, workAreaBounds);
        }
        mRuntimeState.focusTask(task.taskId);
        mRuntimeState.scheduleRefresh();
    }

    private void reconcileImmersiveRequests(
            final List<TaskRepository.TaskEntry> allTasks,
            final List<TaskRepository.TaskEntry> visibleFreeformTasks,
            final boolean focusHandoffPending) {
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
            if (state == null
                    || state.immersiveRequested() == null
                    || state.isImmersiveRequested()) {
                continue;
            }
            final TaskRepository.TaskEntry task =
                    findTask(allTasks, taskId.intValue());
            if (task == null) {
                state.setAppRequestedFullscreen(false);
                state.clearFullscreenRestoreBounds();
                continue;
            }
            if (hasFixedRequestedOrientation(
                    state.requestedOrientation())) {
                // Insets and orientation callbacks are independent. Keep the
                // foreground exit pending until the activity releases its fixed
                // orientation; a renewed immersive=true sample cancels it.
                continue;
            }
            if (focusHandoffPending) {
                // Background immersive exits are filtered when the state
                // monitor reports the actual focused input window. Do not use
                // TaskInfo.active here: organizer children can remain falsely
                // inactive even after input focus has returned to them.
                state.clearImmersiveRequested();
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
        if (shouldEnterAppFullscreen(topState)) {
            makeFullscreen(topTask, true);
        }
    }

    static boolean shouldEnterAppFullscreen(
            final DesktopTaskRuntimeState state) {
        return state != null
                && state.isImmersiveRequested()
                && state.isImmersiveRequestForeground()
                && !state.isAppRequestedFullscreen()
                && !state.hasManualImmersiveOverride()
                && !state.isFullscreenTransition();
    }

    static boolean hasFixedRequestedOrientation(
            final int requestedOrientation) {
        return isLandscapeRequest(requestedOrientation)
                || isPortraitRequest(requestedOrientation);
    }

    private static boolean isLandscapeRequest(final int orientation) {
        return orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                || orientation
                        == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                || orientation
                        == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                || orientation
                        == ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE;
    }

    private static boolean isPortraitRequest(final int orientation) {
        return orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                || orientation
                        == ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                || orientation
                        == ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                || orientation
                        == ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
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
            final Rect bounds,
            final Rect workAreaBounds) {
        // Async transition callbacks can outlive their display session. Use the
        // geometry captured when the transition began, not a later context.
        if (!BuiltInDesktopAppCatalog.remembersWindowState(task)) {
            return;
        }
        final RelativeWindowBounds relative = RelativeWindowBounds.from(
                bounds, workAreaBounds);
        if (relative != null) {
            AppWindowStateStore.rememberWindowed(
                    BuiltInDesktopAppCatalog.appIdentityKey(task), relative);
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

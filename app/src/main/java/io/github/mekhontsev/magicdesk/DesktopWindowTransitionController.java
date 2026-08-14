package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.util.Log;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class DesktopWindowTransitionController {
    interface RuntimeState {
        int displayId();
        boolean isRunning();
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
    private final Context mContext;
    private final NativeWindowBoundsController mNativeWindowBounds;
    private final RuntimeState mRuntimeState;
    private final Map<Integer, Rect> mRestoreBounds = new HashMap<>();
    private final Map<Integer, Rect> mFullscreenRestoreBounds =
            new HashMap<>();
    private final Map<Integer, Boolean> mImmersiveRequests = new HashMap<>();
    private final Set<Integer> mAppRequestedFullscreenTasks =
            new HashSet<>();
    private final Set<Integer> mFullscreenTransitionTasks = new HashSet<>();
    private final Set<Integer> mManualImmersiveOverrides =
            ConcurrentHashMap.newKeySet();
    private final Set<Integer> mStartupWindowedTasks =
            ConcurrentHashMap.newKeySet();

    DesktopWindowTransitionController(
            final Context context,
            final Handler handler,
            final NativeWindowBoundsController nativeWindowBounds,
            final RuntimeState runtimeState) {
        mContext = context.getApplicationContext();
        mHandler = handler;
        mNativeWindowBounds = nativeWindowBounds;
        mRuntimeState = runtimeState;
    }

    Set<Integer> fullscreenTransitionTasks() {
        return mFullscreenTransitionTasks;
    }

    Map<Integer, Rect> fullscreenRestoreBounds() {
        return mFullscreenRestoreBounds;
    }

    boolean hasManagedFullscreenState(final int taskId) {
        return mFullscreenRestoreBounds.containsKey(Integer.valueOf(taskId));
    }

    void observeWindowingModeChange(
            final int taskId,
            final int previousMode,
            final int currentMode) {
        final Integer taskKey = Integer.valueOf(taskId);
        if (!shouldForgetManagedFullscreenState(
                previousMode,
                currentMode,
                mFullscreenTransitionTasks.contains(taskKey))) {
            return;
        }
        // A native restore bypasses MagicDesk's restore callback. Forget its
        // old fullscreen ownership before classifying the next transition.
        mFullscreenRestoreBounds.remove(taskKey);
        mAppRequestedFullscreenTasks.remove(taskKey);
    }

    static boolean shouldForgetManagedFullscreenState(
            final int previousMode,
            final int currentMode,
            final boolean transitionPending) {
        return previousMode == WINDOWING_MODE_FULLSCREEN
                && currentMode == WINDOWING_MODE_FREEFORM
                && !transitionPending;
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
            final Integer taskId = Integer.valueOf(task.taskId);
            mManualImmersiveOverrides.add(taskId);
            restoreFullscreenTask(task, true);
        }));
    }

    void handleImmersiveRequest(
            final int taskId,
            final boolean requestingImmersive,
            final boolean initialSample) {
        final Integer key = Integer.valueOf(taskId);
        final Boolean previous = mImmersiveRequests.put(
                key, Boolean.valueOf(requestingImmersive));
        if (initialSample) {
            if (mAppRequestedFullscreenTasks.contains(key)) {
                mRuntimeState.scheduleRefresh();
            }
            return;
        }
        final boolean newImmersiveRequest = isNewImmersiveRequest(
                previous, requestingImmersive, initialSample);
        final boolean startupWindowedRequest = newImmersiveRequest
                && mStartupWindowedTasks.remove(key);
        if (shouldClearManualImmersiveOverride(
                newImmersiveRequest, startupWindowedRequest)) {
            mManualImmersiveOverrides.remove(key);
        } else if (startupWindowedRequest) {
            Log.i(TAG, "kept startup task windowed task=" + taskId);
        }
        mRuntimeState.scheduleRefresh();
    }

    void noteManualFreeformTransition(final int taskId) {
        if (taskId >= 0) {
            mManualImmersiveOverrides.add(Integer.valueOf(taskId));
        }
    }

    void beginExplicitWindowedLaunch(final int taskId) {
        if (taskId < 0) {
            return;
        }
        final Integer key = Integer.valueOf(taskId);
        mManualImmersiveOverrides.add(key);
        mStartupWindowedTasks.add(key);
        Log.i(TAG, "protecting startup windowed task=" + taskId);
    }

    void finishExplicitWindowedLaunch(final int taskId) {
        if (taskId >= 0) {
            mStartupWindowedTasks.remove(Integer.valueOf(taskId));
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
        final Integer key = Integer.valueOf(taskId);
        mImmersiveRequests.remove(key);
        mAppRequestedFullscreenTasks.remove(key);
        if (mFullscreenTransitionTasks.remove(key)
                && mFullscreenTransitionTasks.isEmpty()) {
            finishWorkspaceTransition(
                    mRuntimeState.displayId(), false);
        }
        mManualImmersiveOverrides.remove(key);
        mStartupWindowedTasks.remove(key);
        mFullscreenRestoreBounds.remove(key);
        mRestoreBounds.remove(key);
        mNativeWindowBounds.forget(taskId);
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
        final Integer taskId = Integer.valueOf(task.taskId);
        if (!mRestoreBounds.containsKey(taskId)) {
            final Rect nativeRestoreBounds =
                    mNativeWindowBounds.getMaximizeRestoreBounds(task.taskId);
            mRestoreBounds.put(
                    taskId,
                    new Rect(nativeRestoreBounds != null
                            ? nativeRestoreBounds : task.bounds));
        }
        mNativeWindowBounds.requestBounds(
                task, mNativeWindowBounds.getSnappedBounds(left), true);
    }

    private void snapFullscreenTask(
            final TaskRepository.TaskEntry task,
            final boolean left) {
        final Integer taskId = Integer.valueOf(task.taskId);
        if (!mFullscreenTransitionTasks.add(taskId)) {
            return;
        }
        final Rect savedBounds = mFullscreenRestoreBounds.get(taskId);
        final Rect restoreBounds;
        if (savedBounds != null) {
            restoreBounds = new Rect(savedBounds);
        } else {
            try {
                restoreBounds = FloatingWindowController
                        .getDefaultWindowBounds(mRuntimeState.displayId());
            } catch (IOException e) {
                mFullscreenTransitionTasks.remove(taskId);
                Log.w(TAG,
                        "cannot resolve fullscreen snap restore bounds", e);
                return;
            }
        }
        mManualImmersiveOverrides.add(taskId);
        final Rect targetBounds =
                mNativeWindowBounds.getSnappedBounds(left);
        TaskRepository.setFreeform(
                task, targetBounds,
                result -> mHandler.post(() -> {
                    mFullscreenTransitionTasks.remove(taskId);
                    if (!result.success) {
                        Log.w(TAG,
                                "fullscreen snap failed task="
                                        + task.taskId
                                        + " message=" + result.message);
                        return;
                    }
                    mRestoreBounds.put(taskId, restoreBounds);
                    mFullscreenRestoreBounds.remove(taskId);
                    mAppRequestedFullscreenTasks.remove(taskId);
                    rememberWindowed(task.packageName, targetBounds);
                    mRuntimeState.scheduleRefresh();
                }));
    }

    private void restoreOrMinimize(
            final TaskRepository.TaskEntry task,
            final TaskRepository.TaskEntry minimizeFocusTask) {
        final Integer taskId = Integer.valueOf(task.taskId);
        final Rect savedBounds = mRestoreBounds.get(taskId);
        if (savedBounds == null) {
            minimize(task, minimizeFocusTask);
            return;
        }
        resize(task, new Rect(savedBounds), true);
    }

    private void resize(
            final TaskRepository.TaskEntry task,
            final Rect targetBounds,
            final boolean clearRestoreBounds) {
        TaskRepository.resizeTaskBounds(
                task,
                targetBounds,
                result -> mHandler.post(() -> {
                    if (!result.success) {
                        Log.w(TAG,
                                "native bounds change failed task="
                                        + task.taskId
                                        + " message=" + result.message);
                        return;
                    }
                    if (clearRestoreBounds) {
                        mRestoreBounds.remove(
                                Integer.valueOf(task.taskId));
                    }
                    mRuntimeState.scheduleRefresh();
                }));
    }

    private void makeFullscreen(
            final TaskRepository.TaskEntry task,
            final boolean appRequested) {
        final Integer taskId = Integer.valueOf(task.taskId);
        if (!mFullscreenTransitionTasks.add(taskId)) {
            return;
        }
        final int displayId = mRuntimeState.displayId();
        mFullscreenRestoreBounds.put(taskId, new Rect(task.bounds));
        if (!appRequested) {
            rememberWindowed(task.packageName, task.bounds);
        }
        mNativeWindowBounds.clearForFullscreen(task.taskId);
        if (appRequested) {
            mAppRequestedFullscreenTasks.add(taskId);
        }
        DesktopTaskStateStore.beginFullscreenTransition(
                displayId,
                DesktopTaskStateStore.getVisibleTasks(displayId),
                task.taskId);
        final TaskRepository.ActionCallback callback =
                result -> mHandler.post(() -> {
                    if (!result.success) {
                        mFullscreenTransitionTasks.remove(taskId);
                        finishWorkspaceTransition(displayId, false);
                        mFullscreenRestoreBounds.remove(taskId);
                        if (appRequested) {
                            mAppRequestedFullscreenTasks.remove(taskId);
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
                    mFullscreenTransitionTasks.remove(taskId);
                    finishWorkspaceTransition(displayId, true);
                    AppWindowStateStore.rememberMode(
                            task.packageName,
                            AppWindowState.Mode.FULLSCREEN);
                });
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
        final Integer taskId = Integer.valueOf(task.taskId);
        if (!mFullscreenTransitionTasks.add(taskId)) {
            return;
        }
        final Rect savedBounds = mFullscreenRestoreBounds.get(taskId);
        final Rect targetBounds;
        if (savedBounds != null) {
            targetBounds = new Rect(savedBounds);
        } else {
            try {
                targetBounds = FloatingWindowController
                        .getDefaultWindowBounds(mRuntimeState.displayId());
            } catch (IOException e) {
                mFullscreenTransitionTasks.remove(taskId);
                Log.w(TAG,
                        "cannot resolve fullscreen restore bounds", e);
                return;
            }
        }
        TaskRepository.setFreeform(
                task, targetBounds,
                result -> mHandler.post(() -> finishFullscreenRestore(
                        task,
                        targetBounds,
                        userRequested,
                        result.success,
                        result.message)));
    }

    private void finishFullscreenRestore(
            final TaskRepository.TaskEntry task,
            final Rect targetBounds,
            final boolean userRequested,
            final boolean success,
            final String message) {
        final Integer taskId = Integer.valueOf(task.taskId);
        mFullscreenTransitionTasks.remove(taskId);
        if (!success) {
            Log.w(TAG, "fullscreen restore failed task=" + task.taskId
                    + " message=" + message);
            return;
        }
        mFullscreenRestoreBounds.remove(taskId);
        mAppRequestedFullscreenTasks.remove(taskId);
        if (userRequested) {
            rememberWindowed(task.packageName, targetBounds);
        }
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
                new HashSet<>(mAppRequestedFullscreenTasks);
        staleAutomaticTasks.removeAll(liveTaskIds);
        for (final Integer taskId : staleAutomaticTasks) {
            forgetTaskState(taskId.intValue());
        }

        for (final Integer taskId
                : new HashSet<>(mAppRequestedFullscreenTasks)) {
            if (Boolean.TRUE.equals(mImmersiveRequests.get(taskId))) {
                continue;
            }
            final TaskRepository.TaskEntry task =
                    findTask(allTasks, taskId.intValue());
            if (task == null || task.isFreeform()) {
                if (task != null
                        && mFullscreenTransitionTasks.contains(taskId)) {
                    continue;
                }
                mAppRequestedFullscreenTasks.remove(taskId);
                mFullscreenRestoreBounds.remove(taskId);
                continue;
            }
            restoreFullscreenTask(task, false);
        }

        if (visibleFreeformTasks.isEmpty()) {
            return;
        }
        final TaskRepository.TaskEntry topTask =
                visibleFreeformTasks.get(0);
        final Integer topTaskId = Integer.valueOf(topTask.taskId);
        if (topTask.active
                && Boolean.TRUE.equals(mImmersiveRequests.get(topTaskId))
                && !mAppRequestedFullscreenTasks.contains(topTaskId)
                && !mManualImmersiveOverrides.contains(topTaskId)
                && !mFullscreenTransitionTasks.contains(topTaskId)) {
            makeFullscreen(topTask, true);
        }
    }

    private void reconcileSubmittedAppFullscreenTransitions(
            final List<TaskRepository.TaskEntry> tasks) {
        if (mFullscreenTransitionTasks.isEmpty()
                || mAppRequestedFullscreenTasks.isEmpty()) {
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
            if (mFullscreenTransitionTasks.contains(taskId)
                    && mAppRequestedFullscreenTasks.contains(taskId)
                    && !task.isFreeform()) {
                completedTaskIds.add(taskId);
            }
        }
        for (final Integer taskId : completedTaskIds) {
            mFullscreenTransitionTasks.remove(taskId);
        }
        if (!completedTaskIds.isEmpty()
                && mFullscreenTransitionTasks.isEmpty()) {
            finishWorkspaceTransition(
                    mRuntimeState.displayId(), true);
        }

        final Set<Integer> removedTaskIds =
                new HashSet<>(mFullscreenTransitionTasks);
        removedTaskIds.retainAll(mAppRequestedFullscreenTasks);
        removedTaskIds.removeAll(liveTaskIds);
        for (final Integer taskId : removedTaskIds) {
            forgetTaskState(taskId.intValue());
        }
    }

    private void finishWorkspaceTransition(
            final int displayId,
            final boolean success) {
        DesktopTaskStateStore.finishFullscreenTransition(
                displayId, success);
        if (mRuntimeState.isRunning()
                && mRuntimeState.displayId() == displayId) {
            mRuntimeState.scheduleRefresh();
        }
    }

    private void rememberWindowed(
            final String packageName,
            final Rect bounds) {
        final RelativeWindowBounds relative = RelativeWindowBounds.from(
                bounds, mNativeWindowBounds.getTaskbarMaximizedBounds());
        if (relative != null) {
            AppWindowStateStore.rememberWindowed(packageName, relative);
        }
    }

    private static TaskRepository.TaskEntry findTopFullscreenTask(
            final List<TaskRepository.TaskEntry> tasks) {
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task.active && !task.isFreeform()
                    && DesktopManagedTaskPolicy
                            .isManagedApplicationTask(task)) {
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

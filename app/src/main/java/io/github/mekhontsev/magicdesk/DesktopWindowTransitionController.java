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

    private static final String TAG = "MagicDeskTasks";
    private static final String MAGICDESK_PACKAGE =
            "io.github.mekhontsev.magicdesk";

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

    static boolean supportsFullscreenTask(final int shortcut) {
        return shortcut == SHORTCUT_CLOSE
                || shortcut == SHORTCUT_SNAP_LEFT
                || shortcut == SHORTCUT_SNAP_RIGHT;
    }

    void applyShortcut(
            final TaskRepository.TaskEntry task,
            final int shortcut) {
        switch (shortcut) {
            case SHORTCUT_FULLSCREEN:
                makeFullscreen(task, false);
                break;
            case SHORTCUT_RESTORE:
                restoreOrMinimize(task);
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
            if (Boolean.TRUE.equals(mImmersiveRequests.get(taskId))) {
                mManualImmersiveOverrides.add(taskId);
            }
            restoreFullscreenTask(task);
        }));
    }

    void handleImmersiveRequest(
            final int taskId,
            final boolean requestingImmersive,
            final boolean initialSample) {
        final Integer key = Integer.valueOf(taskId);
        if (initialSample
                && !mAppRequestedFullscreenTasks.contains(key)) {
            mImmersiveRequests.remove(key);
            if (!requestingImmersive) {
                mManualImmersiveOverrides.remove(key);
            }
            return;
        }
        mImmersiveRequests.put(
                key, Boolean.valueOf(requestingImmersive));
        if (!requestingImmersive) {
            mManualImmersiveOverrides.remove(key);
        }
        mRuntimeState.scheduleRefresh();
    }

    void noteManualFreeformTransition(final int taskId) {
        if (taskId >= 0) {
            mManualImmersiveOverrides.add(Integer.valueOf(taskId));
        }
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

    private void minimize(final TaskRepository.TaskEntry task) {
        TaskRepository.minimizeTask(task, result -> {
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
        if (Boolean.TRUE.equals(mImmersiveRequests.get(taskId))) {
            mManualImmersiveOverrides.add(taskId);
        }
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
                    mRuntimeState.scheduleRefresh();
                }));
    }

    private void restoreOrMinimize(
            final TaskRepository.TaskEntry task) {
        final Integer taskId = Integer.valueOf(task.taskId);
        final Rect savedBounds = mRestoreBounds.get(taskId);
        if (savedBounds == null) {
            minimize(task);
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
                });
        if (appRequested) {
            TaskRepository.setAppRequestedFullscreen(task, callback);
        } else {
            TaskRepository.setFullscreen(
                    task,
                    FullscreenTransitionPolicy.shouldPreserveClient(
                            mContext, task),
                    callback);
        }
    }

    private void restoreFullscreenTask(
            final TaskRepository.TaskEntry task) {
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
                        task, result.success, result.message)));
    }

    private void finishFullscreenRestore(
            final TaskRepository.TaskEntry task,
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
            restoreFullscreenTask(task);
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

    private static TaskRepository.TaskEntry findTopFullscreenTask(
            final List<TaskRepository.TaskEntry> tasks) {
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task.active && !task.home && !task.isFreeform()
                    && !MAGICDESK_PACKAGE.equals(task.packageName)) {
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

package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.util.Log;
import android.view.Display;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DesktopTaskController {
    private static final String TAG = "MagicDeskTasks";
    private static final String MAGICDESK_PACKAGE = "io.github.mekhontsev.magicdesk";
    private static final String TOUCHPAD_ACTIVITY =
            "cn.nubia.keymapcenter.mirror.MirrorInputActivity";
    private static final String SECONDARY_HOME_ACTIVITY =
            "com.android.launcher3.secondarydisplay.SecondaryDisplayLauncher";
    private static final long EVENT_DEBOUNCE_MILLIS = 120;
    private static final long WATCHER_RESTART_MILLIS = 1000;
    private static final int DESKTOP_TASKBAR_RESERVE_DP = 64;
    static final int SHORTCUT_FULLSCREEN = 1;
    static final int SHORTCUT_RESTORE = 2;
    static final int SHORTCUT_SNAP_LEFT = 3;
    static final int SHORTCUT_SNAP_RIGHT = 4;
    static final int SHORTCUT_CLOSE = 5;
    private static final Map<Integer, List<TaskRepository.TaskEntry>> sVisibleTasksByDisplay =
            new HashMap<>();
    private static final Map<Integer, List<TaskRepository.TaskEntry>> sLastVisibleTasksByDisplay =
            new HashMap<>();
    private static final Map<Integer, Boolean> sHasVisibleAppTaskByDisplay =
            new HashMap<>();
    private static final Set<Integer> sFrozenLastVisibleStacks = new HashSet<>();
    private static DesktopTaskController sActiveController;

    private final Context mApplicationContext;
    private final Handler mHandler;
    private final DesktopTaskWatcher mTaskWatcher;
    private final Map<Integer, Rect> mRestoreBounds = new HashMap<>();
    private final Map<Integer, Rect> mFullscreenRestoreBounds = new HashMap<>();
    private final Map<Integer, Rect> mLastNativeWindowBounds = new HashMap<>();
    private final Map<Integer, Rect> mNativeMaximizeRestoreBounds = new HashMap<>();
    private final Map<Integer, NativeBoundsTransition> mNativeBoundsTransitions =
            new HashMap<>();
    private final Map<Integer, Boolean> mImmersiveRequests = new HashMap<>();
    private final Set<Integer> mAppRequestedFullscreenTasks = new HashSet<>();
    private final Set<Integer> mFullscreenTransitionTasks = new HashSet<>();
    private final Set<Integer> mManualImmersiveOverrides = new HashSet<>();
    private final Set<Integer> mLastVisibleAppTaskIds = new HashSet<>();
    private final Runnable mRefreshRunnable = this::runScheduledRefresh;

    private Context mWindowContext;
    private int mDisplayId = -1;
    private int mGeneration;
    private int mImmersiveWatchTaskId = -1;
    private volatile int mFocusingTaskId = -1;
    private long mRefreshDueUptimeMillis = -1;
    private boolean mRunning;
    private boolean mTaskWatcherReady;
    private Boolean mLastTouchpadVisible;
    private boolean mTouchpadRestorePending;
    private boolean mTouchpadRestoreAttemptInProgress;

    DesktopTaskController(final Context context, final Handler handler) {
        mApplicationContext = context.getApplicationContext();
        mHandler = handler;
        mTaskWatcher = new DesktopTaskWatcher(
                mHandler,
                new DesktopTaskWatcher.Listener() {
                    @Override
                    public boolean isActive(final int generation) {
                        return mRunning && generation == mGeneration;
                    }

                    @Override
                    public void onReady(final int generation) {
                        mTaskWatcherReady = true;
                        sendImmersiveWatchCommand();
                        sendNativeMaximizeWatchCommand();
                        scheduleRefresh(EVENT_DEBOUNCE_MILLIS);
                    }

                    @Override
                    public void onChanged(final int generation) {
                        scheduleRefresh(EVENT_DEBOUNCE_MILLIS);
                    }

                    @Override
                    public void onImmersiveRequest(
                            final int generation,
                            final int taskId,
                            final boolean requesting) {
                        handleImmersiveRequest(taskId, requesting);
                    }

                    @Override
                    public void onTaskGone(
                            final int generation,
                            final int taskId) {
                        forgetTaskState(taskId);
                    }

                    @Override
                    public void onNativeMaximizeEvent(
                            final int generation,
                            final String event,
                            final int taskId) {
                        Log.d(TAG, event + " task=" + taskId);
                        scheduleRefresh(0);
                    }

                    @Override
                    public void onDisconnected(final int generation) {
                        mTaskWatcherReady = false;
                        scheduleRefresh(0);
                        mHandler.postDelayed(() -> {
                            if (mRunning && generation == mGeneration) {
                                startTaskWatcher(generation);
                            }
                        }, WATCHER_RESTART_MILLIS);
                    }
                });
    }

    void start(final int displayId) {
        if (displayId <= 0) {
            stop();
            return;
        }
        if (mRunning && mDisplayId == displayId) {
            scheduleRefresh(0);
            return;
        }
        stop();
        mDisplayId = displayId;
        mRunning = createWindowContext(displayId);
        if (!mRunning) {
            return;
        }
        mGeneration++;
        setActiveController(this);
        startTaskWatcher(mGeneration);
        scheduleRefresh(0);
        Log.i(TAG, "started on display=" + displayId);
    }

    void stop() {
        final int stoppedDisplayId = mDisplayId;
        mRunning = false;
        mGeneration++;
        mHandler.removeCallbacks(mRefreshRunnable);
        mRefreshDueUptimeMillis = -1;
        mTaskWatcher.stop();
        mWindowContext = null;
        mDisplayId = -1;
        mImmersiveWatchTaskId = -1;
        mFocusingTaskId = -1;
        mTaskWatcherReady = false;
        mLastNativeWindowBounds.clear();
        mNativeMaximizeRestoreBounds.clear();
        mNativeBoundsTransitions.clear();
        mLastVisibleAppTaskIds.clear();
        mLastTouchpadVisible = null;
        mTouchpadRestorePending = false;
        mTouchpadRestoreAttemptInProgress = false;
        clearActiveController(this);
        clearVisibleFreeformTasks(stoppedDisplayId);
    }

    static synchronized List<TaskRepository.TaskEntry> getVisibleFreeformTasks(
            final int displayId) {
        final List<TaskRepository.TaskEntry> tasks =
                sVisibleTasksByDisplay.get(Integer.valueOf(displayId));
        return tasks == null ? null : new ArrayList<>(tasks);
    }

    static synchronized List<TaskRepository.TaskEntry> getLastVisibleFreeformTasks(
            final int displayId) {
        final List<TaskRepository.TaskEntry> tasks =
                sLastVisibleTasksByDisplay.get(Integer.valueOf(displayId));
        return tasks == null ? Collections.emptyList() : copyTasks(tasks);
    }

    static synchronized Boolean hasVisibleAppTaskSnapshot(final int displayId) {
        return sHasVisibleAppTaskByDisplay.get(Integer.valueOf(displayId));
    }

    private static synchronized void rememberVisibleFreeformTasks(final int displayId,
            final List<TaskRepository.TaskEntry> tasks) {
        if (displayId < 0 || tasks == null || tasks.isEmpty()) {
            return;
        }
        sLastVisibleTasksByDisplay.put(Integer.valueOf(displayId),
                Collections.unmodifiableList(copyTasks(tasks)));
    }

    static synchronized void beginFullscreenTransition(final int displayId,
            final List<TaskRepository.TaskEntry> visibleTasks, final int excludedTaskId) {
        if (displayId < 0) {
            return;
        }
        final List<TaskRepository.TaskEntry> workspace = new ArrayList<>();
        if (visibleTasks != null) {
            for (final TaskRepository.TaskEntry task : visibleTasks) {
                if (task != null && task.taskId != excludedTaskId) {
                    workspace.add(task);
                }
            }
        }
        sLastVisibleTasksByDisplay.put(Integer.valueOf(displayId),
                Collections.unmodifiableList(copyTasks(workspace)));
        sFrozenLastVisibleStacks.add(Integer.valueOf(displayId));
    }

    static synchronized void finishFullscreenTransition(final int displayId,
            final boolean success) {
        if (displayId < 0) {
            return;
        }
        sFrozenLastVisibleStacks.remove(Integer.valueOf(displayId));
        final DesktopTaskController controller = sActiveController;
        if (controller != null && controller.mRunning
                && controller.mDisplayId == displayId) {
            controller.scheduleRefresh(0);
        }
        if (success) {
            return;
        }
        final List<TaskRepository.TaskEntry> visibleTasks =
                sVisibleTasksByDisplay.get(Integer.valueOf(displayId));
        if (visibleTasks != null) {
            sLastVisibleTasksByDisplay.put(Integer.valueOf(displayId),
                    Collections.unmodifiableList(copyTasks(visibleTasks)));
        }
    }

    static synchronized void forgetVisibleFreeformTasks(final int displayId) {
        if (displayId >= 0) {
            sVisibleTasksByDisplay.put(Integer.valueOf(displayId), Collections.emptyList());
        }
    }

    static void focusStack(final List<TaskRepository.TaskEntry> topFirstTasks,
            final TaskRepository.TaskEntry topTask,
            final TaskRepository.ActionCallback callback) {
        final DesktopTaskController controller = getActiveController();
        if (controller == null) {
            completeFocusCallback(callback, false, "task watcher unavailable");
            return;
        }
        final int focusedTaskId = topTask == null ? -1 : topTask.taskId;
        controller.mFocusingTaskId = focusedTaskId;
        final TaskRepository.ActionCallback trackedCallback = result -> {
            if (!result.success && controller.mFocusingTaskId == focusedTaskId) {
                controller.mFocusingTaskId = -1;
            }
            completeFocusCallback(callback, result.success, result.message);
        };
        if (topTask != null && topTask.isFreeform() && !topTask.visible) {
            TaskRepository.restoreTask(topTask, trackedCallback);
            return;
        }

        final Set<Integer> orderedTaskIds = new LinkedHashSet<>();
        if (topFirstTasks != null) {
            for (int index = topFirstTasks.size() - 1; index >= 0; index--) {
                final TaskRepository.TaskEntry task = topFirstTasks.get(index);
                if (isFocusableTask(task)) {
                    orderedTaskIds.add(Integer.valueOf(task.taskId));
                }
            }
        }
        if (isFocusableTask(topTask)) {
            orderedTaskIds.remove(Integer.valueOf(topTask.taskId));
            orderedTaskIds.add(Integer.valueOf(topTask.taskId));
        }
        if (orderedTaskIds.isEmpty()) {
            controller.mFocusingTaskId = -1;
            completeFocusCallback(callback, true, "no tasks");
            return;
        }
        controller.mTaskWatcher.sendFocusStack(
                new ArrayList<>(orderedTaskIds), trackedCallback);
    }

    static boolean handleActiveTaskShortcut(final int shortcut) {
        final DesktopTaskController controller = getActiveController();
        if (controller == null || !controller.mRunning) {
            return false;
        }
        controller.mHandler.post(() -> controller.handleActiveTaskShortcutInternal(shortcut));
        return true;
    }

    static boolean dismissTransientActivity() {
        final DesktopTaskController controller = getActiveController();
        if (controller == null || !controller.mRunning) {
            return false;
        }
        controller.mHandler.post(controller::dismissTransientActivityInternal);
        return true;
    }

    static boolean sendSystemBack() {
        final DesktopTaskController controller = getActiveController();
        if (controller == null || !controller.mRunning) {
            return false;
        }
        controller.mHandler.post(controller::sendSystemBackInternal);
        return true;
    }

    private void sendSystemBackInternal() {
        final int displayId = mDisplayId;
        if (!mRunning || displayId <= 0) {
            return;
        }
        TaskRepository.sendBackToDisplay(displayId, result -> {
            if (!result.success) {
                Log.w(TAG, "system Back failed display=" + displayId
                        + " message=" + result.message);
            }
        });
    }

    private void dismissTransientActivityInternal() {
        final int displayId = mDisplayId;
        final int generation = mGeneration;
        TaskRepository.load(displayId, snapshot -> mHandler.post(() -> {
            if (!mRunning || generation != mGeneration || mDisplayId != displayId) {
                return;
            }
            for (final TaskRepository.TaskEntry task : snapshot.tasks) {
                if (task != null && task.active && task.visible
                        && task.hasCrossPackageTopActivity()) {
                    TaskRepository.sendBackToDisplay(displayId, result -> {
                        if (!result.success) {
                            Log.w(TAG, "transient activity dismiss failed: "
                                    + result.message);
                        }
                    });
                    return;
                }
            }
        }));
    }

    private void handleActiveTaskShortcutInternal(final int shortcut) {
        handleNativeTaskShortcut(shortcut);
    }

    private void handleNativeTaskShortcut(final int shortcut) {
        final int displayId = mDisplayId;
        final int generation = mGeneration;
        TaskRepository.load(displayId, snapshot -> mHandler.post(() -> {
            if (!mRunning || generation != mGeneration || mDisplayId != displayId) {
                return;
            }
            final boolean supportsFullscreenTask = shortcut == SHORTCUT_CLOSE
                    || shortcut == SHORTCUT_SNAP_LEFT
                    || shortcut == SHORTCUT_SNAP_RIGHT;
            final TaskRepository.TaskEntry task = supportsFullscreenTask
                    ? findTopVisibleAppTask(snapshot.tasks)
                    : findTopVisibleFreeformTask(snapshot.tasks);
            if (task == null) {
                if (shortcut == SHORTCUT_RESTORE) {
                    restoreTopFullscreenTask();
                } else {
                    Log.w(TAG, "no active task for shortcut=" + shortcut
                            + " display=" + displayId);
                }
                return;
            }
            applyNativeTaskShortcut(task, shortcut);
        }));
    }

    private void applyNativeTaskShortcut(
            final TaskRepository.TaskEntry task, final int shortcut) {
        switch (shortcut) {
            case SHORTCUT_FULLSCREEN:
                makeFullscreen(task, false);
                break;
            case SHORTCUT_RESTORE:
                restoreOrMinimizeNativeTask(task);
                break;
            case SHORTCUT_SNAP_LEFT:
                snapNativeTask(task, true);
                break;
            case SHORTCUT_SNAP_RIGHT:
                snapNativeTask(task, false);
                break;
            case SHORTCUT_CLOSE:
                closeNativeTask(task);
                break;
            default:
                Log.w(TAG, "unknown native window shortcut=" + shortcut);
        }
    }

    private static TaskRepository.TaskEntry findTopVisibleAppTask(
            final List<TaskRepository.TaskEntry> tasks) {
        if (tasks == null) {
            return null;
        }
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task != null && task.visible && task.active && !task.home
                    && !MAGICDESK_PACKAGE.equals(task.packageName)) {
                return task;
            }
        }
        return null;
    }

    private static TaskRepository.TaskEntry findTopVisibleFreeformTask(
            final List<TaskRepository.TaskEntry> tasks) {
        if (tasks == null) {
            return null;
        }
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task != null && task.visible && task.active && task.isFreeform()
                    && !task.home && !MAGICDESK_PACKAGE.equals(task.packageName)
                    && !task.bounds.isEmpty()) {
                return task;
            }
        }
        return null;
    }

    private void minimizeNativeTask(final TaskRepository.TaskEntry task) {
        TaskRepository.minimizeTask(task, result -> {
            if (!result.success) {
                Log.w(TAG, "native minimize failed task=" + task.taskId
                        + " message=" + result.message);
            }
        });
    }

    private void closeNativeTask(final TaskRepository.TaskEntry task) {
        TaskRepository.closeTask(task, result -> {
            if (!result.success) {
                Log.w(TAG, "native close failed task=" + task.taskId
                        + " message=" + result.message);
            }
        });
    }

    private void snapNativeTask(final TaskRepository.TaskEntry task,
            final boolean left) {
        if (!task.isFreeform()) {
            snapFullscreenTask(task, left);
            return;
        }
        final Integer taskId = Integer.valueOf(task.taskId);
        if (!mRestoreBounds.containsKey(taskId)) {
            final Rect nativeRestoreBounds =
                    mNativeMaximizeRestoreBounds.get(taskId);
            mRestoreBounds.put(taskId, new Rect(nativeRestoreBounds != null
                    ? nativeRestoreBounds : task.bounds));
        }
        requestTrackedNativeBounds(
                task, getNativeSnappedBounds(left), true);
    }

    private void snapFullscreenTask(final TaskRepository.TaskEntry task,
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
                restoreBounds =
                        FloatingWindowController.getDefaultWindowBounds(mDisplayId);
            } catch (IOException e) {
                mFullscreenTransitionTasks.remove(taskId);
                Log.w(TAG, "cannot resolve fullscreen snap restore bounds", e);
                return;
            }
        }
        if (Boolean.TRUE.equals(mImmersiveRequests.get(taskId))) {
            mManualImmersiveOverrides.add(taskId);
        }
        final Rect targetBounds = getNativeSnappedBounds(left);
        NativeDesktopController.moveTaskToDesktop(task, targetBounds,
                (success, message) -> mHandler.post(() -> {
                    mFullscreenTransitionTasks.remove(taskId);
                    if (!success) {
                        Log.w(TAG, "fullscreen snap failed task=" + task.taskId
                                + " message=" + message);
                        return;
                    }
                    mRestoreBounds.put(taskId, restoreBounds);
                    mFullscreenRestoreBounds.remove(taskId);
                    mAppRequestedFullscreenTasks.remove(taskId);
                    scheduleRefresh(0);
                }));
    }

    private void restoreOrMinimizeNativeTask(
            final TaskRepository.TaskEntry task) {
        final Integer taskId = Integer.valueOf(task.taskId);
        final Rect savedBounds = mRestoreBounds.get(taskId);
        if (savedBounds == null) {
            minimizeNativeTask(task);
            return;
        }
        resizeNativeTask(task, new Rect(savedBounds), true);
    }

    private void resizeNativeTask(final TaskRepository.TaskEntry task,
            final Rect targetBounds, final boolean clearRestoreBounds) {
        TaskRepository.resizeTaskBounds(task, targetBounds, result -> mHandler.post(() -> {
            if (!result.success) {
                Log.w(TAG, "native bounds change failed task=" + task.taskId
                        + " message=" + result.message);
                return;
            }
            if (clearRestoreBounds) {
                mRestoreBounds.remove(Integer.valueOf(task.taskId));
            }
            scheduleRefresh(0);
        }));
    }

    private Rect getNativeSnappedBounds(final boolean left) {
        final int displayWidth = mWindowContext.getResources()
                .getDisplayMetrics().widthPixels;
        final int displayHeight = mWindowContext.getResources()
                .getDisplayMetrics().heightPixels;
        final int middle = displayWidth / 2;
        final int taskbarTop = Math.max(1,
                displayHeight - dp(DESKTOP_TASKBAR_RESERVE_DP));
        return left
                ? new Rect(0, 0, middle, taskbarTop)
                : new Rect(middle, 0, displayWidth, taskbarTop);
    }

    private void makeFullscreen(final TaskRepository.TaskEntry task,
            final boolean appRequested) {
        final Integer taskId = Integer.valueOf(task.taskId);
        if (!mFullscreenTransitionTasks.add(taskId)) {
            return;
        }
        final int displayId = mDisplayId;
        mFullscreenRestoreBounds.put(taskId, new Rect(task.bounds));
        mLastNativeWindowBounds.remove(taskId);
        mNativeMaximizeRestoreBounds.remove(taskId);
        mNativeBoundsTransitions.remove(taskId);
        if (appRequested) {
            mAppRequestedFullscreenTasks.add(taskId);
        }
        final List<TaskRepository.TaskEntry> visibleTasks = getVisibleFreeformTasks(displayId);
        beginFullscreenTransition(displayId, visibleTasks, task.taskId);
        final TaskRepository.ActionCallback callback = result -> mHandler.post(() -> {
            if (!result.success) {
                mFullscreenTransitionTasks.remove(taskId);
                finishFullscreenTransition(displayId, false);
                mFullscreenRestoreBounds.remove(taskId);
                if (appRequested) {
                    mAppRequestedFullscreenTasks.remove(taskId);
                }
                Log.w(TAG, "fullscreen shortcut failed task=" + task.taskId
                        + " message=" + result.message);
                return;
            }
            if (appRequested) {
                // Submission is asynchronous. Keep the transition pending until
                // a task snapshot confirms that WindowManager applied it.
                scheduleRefresh(0);
                return;
            }
            mFullscreenTransitionTasks.remove(taskId);
            finishFullscreenTransition(displayId, true);
        });
        if (appRequested) {
            TaskRepository.setAppRequestedFullscreen(task, callback);
        } else {
            TaskRepository.setFullscreen(task, callback);
        }
    }

    private void restoreTopFullscreenTask() {
        final int displayId = mDisplayId;
        TaskRepository.load(displayId, snapshot -> mHandler.post(() -> {
            if (!mRunning || mDisplayId != displayId) {
                return;
            }
            TaskRepository.TaskEntry activeTask = null;
            for (final TaskRepository.TaskEntry task : snapshot.tasks) {
                if (task.active && !task.home && !task.isFreeform()
                        && !MAGICDESK_PACKAGE.equals(task.packageName)) {
                    activeTask = task;
                    break;
                }
            }
            if (activeTask == null) {
                return;
            }
            final TaskRepository.TaskEntry selectedTask = activeTask;
            final Integer taskId = Integer.valueOf(selectedTask.taskId);
            if (Boolean.TRUE.equals(mImmersiveRequests.get(taskId))) {
                mManualImmersiveOverrides.add(taskId);
            }
            restoreFullscreenTask(selectedTask);
        }));
    }

    private void restoreFullscreenTask(final TaskRepository.TaskEntry task) {
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
                targetBounds = FloatingWindowController.getDefaultWindowBounds(mDisplayId);
            } catch (IOException e) {
                mFullscreenTransitionTasks.remove(taskId);
                Log.w(TAG, "cannot resolve fullscreen restore bounds", e);
                return;
            }
        }
        NativeDesktopController.moveTaskToDesktop(task, targetBounds,
                (success, message) -> mHandler.post(() ->
                        finishFullscreenRestore(task, success, message)));
    }

    private void finishFullscreenRestore(final TaskRepository.TaskEntry task,
            final boolean success, final String message) {
        final Integer taskId = Integer.valueOf(task.taskId);
        mFullscreenTransitionTasks.remove(taskId);
        if (!success) {
            Log.w(TAG, "fullscreen restore failed task=" + task.taskId
                    + " message=" + message);
            return;
        }
        mFullscreenRestoreBounds.remove(taskId);
        mAppRequestedFullscreenTasks.remove(taskId);
        scheduleRefresh(0);
    }

    private void handleImmersiveRequest(final int taskId,
            final boolean requestingImmersive) {
        final Integer key = Integer.valueOf(taskId);
        mImmersiveRequests.put(key, Boolean.valueOf(requestingImmersive));
        if (!requestingImmersive) {
            mManualImmersiveOverrides.remove(key);
        }
        scheduleRefresh(0);
    }

    private void forgetTaskState(final int taskId) {
        final Integer key = Integer.valueOf(taskId);
        mImmersiveRequests.remove(key);
        mAppRequestedFullscreenTasks.remove(key);
        if (mFullscreenTransitionTasks.remove(key)
                && mFullscreenTransitionTasks.isEmpty()) {
            finishFullscreenTransition(mDisplayId, false);
        }
        mManualImmersiveOverrides.remove(key);
        mFullscreenRestoreBounds.remove(key);
        mRestoreBounds.remove(key);
        mLastNativeWindowBounds.remove(key);
        mNativeMaximizeRestoreBounds.remove(key);
        mNativeBoundsTransitions.remove(key);
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
            TaskRepository.TaskEntry task = null;
            for (final TaskRepository.TaskEntry candidate : allTasks) {
                if (candidate.taskId == taskId.intValue()) {
                    task = candidate;
                    break;
                }
            }
            if (task == null || task.isFreeform()) {
                if (task != null && mFullscreenTransitionTasks.contains(taskId)) {
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
        final TaskRepository.TaskEntry topTask = visibleFreeformTasks.get(0);
        final Integer topTaskId = Integer.valueOf(topTask.taskId);
        if (topTask.active
                && Boolean.TRUE.equals(mImmersiveRequests.get(topTaskId))
                && !mAppRequestedFullscreenTasks.contains(topTaskId)
                && !mManualImmersiveOverrides.contains(topTaskId)
                && !mFullscreenTransitionTasks.contains(topTaskId)) {
            makeFullscreen(topTask, true);
        }
    }

    private static synchronized DesktopTaskController getActiveController() {
        return sActiveController;
    }

    private static synchronized void setActiveController(
            final DesktopTaskController controller) {
        sActiveController = controller;
    }

    private static synchronized void clearActiveController(
            final DesktopTaskController controller) {
        if (sActiveController == controller) {
            sActiveController = null;
        }
    }

    private static boolean isFocusableTask(final TaskRepository.TaskEntry task) {
        return task != null && task.taskId >= 0 && !task.home
                && !MAGICDESK_PACKAGE.equals(task.packageName);
    }

    private static final class NativeBoundsTransition {
        final Rect targetBounds;
        final boolean clearsMaximizeState;

        NativeBoundsTransition(final Rect targetBounds,
                final boolean clearsMaximizeState) {
            this.targetBounds = new Rect(targetBounds);
            this.clearsMaximizeState = clearsMaximizeState;
        }
    }

    private static void completeFocusCallback(final TaskRepository.ActionCallback callback,
            final boolean success, final String message) {
        if (callback != null) {
            callback.onComplete(new TaskRepository.ActionResult(success, message));
        }
    }

    private static synchronized void publishVisibleFreeformTasks(final int displayId,
            final List<TaskRepository.TaskEntry> tasks) {
        sVisibleTasksByDisplay.put(Integer.valueOf(displayId),
                Collections.unmodifiableList(new ArrayList<>(tasks)));
        if (!sFrozenLastVisibleStacks.contains(Integer.valueOf(displayId))) {
            rememberVisibleFreeformTasks(displayId, tasks);
        }
    }

    private static synchronized void clearVisibleFreeformTasks(final int displayId) {
        if (displayId >= 0) {
            sVisibleTasksByDisplay.remove(Integer.valueOf(displayId));
            sLastVisibleTasksByDisplay.remove(Integer.valueOf(displayId));
            sHasVisibleAppTaskByDisplay.remove(Integer.valueOf(displayId));
            sFrozenLastVisibleStacks.remove(Integer.valueOf(displayId));
        }
    }

    private static List<TaskRepository.TaskEntry> copyTasks(
            final List<TaskRepository.TaskEntry> tasks) {
        final List<TaskRepository.TaskEntry> copies = new ArrayList<>(tasks.size());
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task == null) {
                continue;
            }
            copies.add(new TaskRepository.TaskEntry(
                    task.rootTaskId, task.taskId, task.displayId,
                    task.packageName, task.componentName, task.topActivityName,
                    task.windowingMode, task.bounds, task.home, task.visible,
                    task.active));
        }
        return copies;
    }

    private boolean createWindowContext(final int displayId) {
        final DisplayManager displayManager = mApplicationContext.getSystemService(
                DisplayManager.class);
        final Display display = displayManager == null ? null : displayManager.getDisplay(displayId);
        if (display == null) {
            Log.w(TAG, "display not found: " + displayId);
            return false;
        }
        mWindowContext = mApplicationContext.createDisplayContext(display);
        return true;
    }

    private void refresh() {
        if (!mRunning) {
            return;
        }
        requestSnapshot();
    }

    private void requestSnapshot() {
        final int generation = mGeneration;
        TaskRepository.load(mDisplayId, snapshot -> mHandler.post(() -> {
            if (!mRunning || generation != mGeneration) {
                return;
            }
            applySnapshot(snapshot);
        }));
    }

    private void startTaskWatcher(final int generation) {
        mTaskWatcher.start(generation);
    }

    private boolean sendWatcherCommand(final String command) {
        return mTaskWatcher.sendCommand(command);
    }

    private void sendNativeMaximizeWatchCommand() {
        if (mWindowContext == null) {
            return;
        }
        final Rect displayBounds = getNativeFullscreenBounds();
        final Rect workAreaBounds = getNativeTaskbarMaximizedBounds();
        sendWatcherCommand("watch-native-maximize " + mDisplayId + " "
                + displayBounds.width() + " " + displayBounds.height() + " "
                + workAreaBounds.height());
    }

    private void updateImmersiveWatch(
            final List<TaskRepository.TaskEntry> tasks) {
        int taskId = -1;
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task != null
                    && task.displayId == mDisplayId
                    && task.visible
                    && !task.home
                    && !MAGICDESK_PACKAGE.equals(task.packageName)) {
                taskId = task.taskId;
                break;
            }
        }
        if (taskId == mImmersiveWatchTaskId) {
            return;
        }
        mImmersiveWatchTaskId = taskId;
        sendImmersiveWatchCommand();
    }

    private void sendImmersiveWatchCommand() {
        if (!mTaskWatcherReady) {
            return;
        }
        if (mImmersiveWatchTaskId >= 0) {
            sendWatcherCommand("watch-task " + mDisplayId
                    + " " + mImmersiveWatchTaskId);
        } else {
            sendWatcherCommand("pause-immersive");
        }
    }

    private void applySnapshot(final TaskRepository.Snapshot snapshot) {
        if (!snapshot.rootAvailable) {
            Log.w(TAG, "task snapshot unavailable: " + snapshot.error);
            return;
        }
        reconcileNativeWindowBounds(snapshot.tasks);
        MainActivity.syncTaskbarWithSnapshot(mDisplayId, snapshot);
        updateImmersiveWatch(snapshot.tasks);
        final List<TaskRepository.TaskEntry> visibleTasks = new ArrayList<>();
        final Set<Integer> visibleAppTaskIds = new HashSet<>();
        boolean hasVisibleAppTask = false;
        for (final TaskRepository.TaskEntry task : snapshot.tasks) {
            if (isVisibleFreeformTask(task)) {
                visibleTasks.add(task);
            }
            if (task != null && task.displayId == mDisplayId && task.visible
                    && !task.home && !MAGICDESK_PACKAGE.equals(task.packageName)) {
                hasVisibleAppTask = true;
                visibleAppTaskIds.add(Integer.valueOf(task.taskId));
            }
        }
        final int focusingTaskId = mFocusingTaskId;
        reconcilePhoneUi(snapshot.phoneTasks, visibleAppTaskIds,
                focusingTaskId >= 0);
        if (focusingTaskId >= 0) {
            final TaskRepository.TaskEntry focusingTask =
                    findTask(snapshot.tasks, focusingTaskId);
            if (focusingTask == null || focusingTask.active) {
                mFocusingTaskId = -1;
            }
        }
        synchronized (DesktopTaskController.class) {
            sHasVisibleAppTaskByDisplay.put(
                    Integer.valueOf(mDisplayId), Boolean.valueOf(hasVisibleAppTask));
        }
        publishVisibleFreeformTasks(mDisplayId, visibleTasks);
        reconcileSubmittedAppFullscreenTransitions(snapshot.tasks);
        reconcileImmersiveRequests(snapshot.tasks, visibleTasks);
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
        if (!completedTaskIds.isEmpty() && mFullscreenTransitionTasks.isEmpty()) {
            finishFullscreenTransition(mDisplayId, true);
        }

        final Set<Integer> removedTaskIds =
                new HashSet<>(mFullscreenTransitionTasks);
        removedTaskIds.retainAll(mAppRequestedFullscreenTasks);
        removedTaskIds.removeAll(liveTaskIds);
        for (final Integer taskId : removedTaskIds) {
            forgetTaskState(taskId.intValue());
        }
    }

    private void reconcilePhoneUi(final List<TaskRepository.TaskEntry> phoneTasks,
            final Set<Integer> visibleAppTaskIds,
            final boolean focusingExternalTask) {
        boolean touchpadVisible = false;
        boolean secondaryHomeVisible = false;
        for (final TaskRepository.TaskEntry task : phoneTasks) {
            if (task == null || !task.visible || task.componentName == null) {
                continue;
            }
            if (task.componentName.endsWith(TOUCHPAD_ACTIVITY)) {
                touchpadVisible = true;
            } else if (task.componentName.endsWith(SECONDARY_HOME_ACTIVITY)) {
                secondaryHomeVisible = true;
            }
        }

        boolean externalTaskMinimized = false;
        for (final Integer taskId : mLastVisibleAppTaskIds) {
            if (!visibleAppTaskIds.contains(taskId)) {
                externalTaskMinimized = true;
                break;
            }
        }
        if (!focusingExternalTask
                && externalTaskMinimized && secondaryHomeVisible && !touchpadVisible
                && mLastTouchpadVisible != null) {
            if (mLastTouchpadVisible.booleanValue()) {
                Log.i(TAG, "Nubia touchpad displaced by external task minimize");
                mTouchpadRestorePending = true;
            } else {
                Log.i(TAG, "restore phone Home displaced by external task minimize");
                ConsoleModeSwitcher.restorePrimaryPhoneHome();
            }
        }
        attemptPendingTouchpadRestore();

        mLastVisibleAppTaskIds.clear();
        mLastVisibleAppTaskIds.addAll(visibleAppTaskIds);
        mLastTouchpadVisible = Boolean.valueOf(touchpadVisible);
    }

    private static TaskRepository.TaskEntry findTask(
            final List<TaskRepository.TaskEntry> tasks, final int taskId) {
        if (tasks == null) {
            return null;
        }
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task != null && task.taskId == taskId) {
                return task;
            }
        }
        return null;
    }

    private void attemptPendingTouchpadRestore() {
        if (!mTouchpadRestorePending || mTouchpadRestoreAttemptInProgress) {
            return;
        }
        mTouchpadRestoreAttemptInProgress = true;
        ConsoleModeSwitcher.restoreTouchpadIfMissing((touchpadMissing, restored) ->
                mHandler.post(() -> {
                    mTouchpadRestoreAttemptInProgress = false;
                    if (!mRunning) {
                        mTouchpadRestorePending = false;
                        return;
                    }
                    if (!touchpadMissing) {
                        Log.d(TAG, "touchpad transition is still in progress");
                        return;
                    }
                    mTouchpadRestorePending = !restored;
                    if (!restored) {
                        Log.w(TAG, "touchpad restore failed; waiting for another task event");
                    }
                }));
    }

    private void reconcileNativeWindowBounds(
            final List<TaskRepository.TaskEntry> tasks) {
        if (mWindowContext == null) {
            return;
        }
        final Rect fullscreenBounds = getNativeFullscreenBounds();
        final Rect maximizedBounds = getNativeTaskbarMaximizedBounds();
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task == null || task.displayId != mDisplayId || task.home
                    || MAGICDESK_PACKAGE.equals(task.packageName)
                    || !task.isFreeform() || task.bounds.isEmpty()) {
                continue;
            }
            final Integer taskId = Integer.valueOf(task.taskId);
            if (mFullscreenTransitionTasks.contains(taskId)
                    || mFullscreenRestoreBounds.containsKey(taskId)) {
                mLastNativeWindowBounds.remove(taskId);
                mNativeMaximizeRestoreBounds.remove(taskId);
                mNativeBoundsTransitions.remove(taskId);
                continue;
            }

            final NativeBoundsTransition transition =
                    mNativeBoundsTransitions.get(taskId);
            if (transition != null) {
                if (task.bounds.equals(transition.targetBounds)) {
                    mNativeBoundsTransitions.remove(taskId);
                    if (transition.clearsMaximizeState) {
                        mNativeMaximizeRestoreBounds.remove(taskId);
                        mLastNativeWindowBounds.put(
                                taskId, new Rect(transition.targetBounds));
                    }
                }
                continue;
            }
            if (!task.visible) {
                continue;
            }

            final Rect restoreBounds = mNativeMaximizeRestoreBounds.get(taskId);
            if (task.bounds.equals(fullscreenBounds)) {
                if (restoreBounds != null) {
                    requestTrackedNativeBounds(task, restoreBounds, true);
                } else {
                    Rect previousBounds = mLastNativeWindowBounds.get(taskId);
                    if (previousBounds == null || previousBounds.isEmpty()
                            || previousBounds.equals(fullscreenBounds)
                            || previousBounds.equals(maximizedBounds)) {
                        previousBounds = getDefaultNativeWindowBounds(maximizedBounds);
                    }
                    mNativeMaximizeRestoreBounds.put(
                            taskId, new Rect(previousBounds));
                    requestTrackedNativeBounds(task, maximizedBounds, false);
                }
                continue;
            }

            if (restoreBounds != null) {
                if (!task.bounds.equals(maximizedBounds)) {
                    Log.d(TAG, "preserve native maximize task=" + task.taskId
                            + " unexpectedBounds=" + task.bounds);
                    requestTrackedNativeBounds(task, maximizedBounds, false);
                }
                continue;
            }

            final Rect stableBounds = mLastNativeWindowBounds.get(taskId);
            if (task.hasCrossPackageTopActivity()) {
                if (stableBounds != null && !stableBounds.isEmpty()
                        && !task.bounds.equals(stableBounds)) {
                    Log.d(TAG, "preserve task bounds across transient activity task="
                            + task.taskId + " current=" + task.bounds
                            + " stable=" + stableBounds
                            + " top=" + task.topActivityName);
                    requestTrackedNativeBounds(task, stableBounds, true);
                }
                continue;
            }

            if (task.bounds.equals(maximizedBounds)) {
                Rect previousBounds = mLastNativeWindowBounds.get(taskId);
                if (previousBounds == null || previousBounds.isEmpty()) {
                    previousBounds = getDefaultNativeWindowBounds(maximizedBounds);
                }
                mNativeMaximizeRestoreBounds.put(taskId, new Rect(previousBounds));
            } else if (!task.bounds.equals(maximizedBounds)) {
                mLastNativeWindowBounds.put(taskId, new Rect(task.bounds));
            }
        }
    }

    private void requestTrackedNativeBounds(
            final TaskRepository.TaskEntry task, final Rect targetBounds,
            final boolean clearsMaximizeState) {
        final Integer taskId = Integer.valueOf(task.taskId);
        final NativeBoundsTransition transition =
                new NativeBoundsTransition(targetBounds, clearsMaximizeState);
        mNativeBoundsTransitions.put(taskId, transition);
        TaskRepository.resizeTaskBounds(task, targetBounds, result -> mHandler.post(() -> {
            if (mNativeBoundsTransitions.get(taskId) != transition) {
                if (result.success) {
                    scheduleRefresh(0);
                }
                return;
            }
            if (!result.success) {
                mNativeBoundsTransitions.remove(taskId);
                if (!clearsMaximizeState) {
                    mNativeMaximizeRestoreBounds.remove(taskId);
                }
                Log.w(TAG, "native bounds transition failed task=" + task.taskId
                        + " message=" + result.message);
                return;
            }
            scheduleRefresh(0);
        }));
    }

    @SuppressWarnings("deprecation")
    private Rect getNativeFullscreenBounds() {
        final DisplayManager displayManager = mApplicationContext.getSystemService(
                DisplayManager.class);
        final Display display = displayManager == null
                ? null : displayManager.getDisplay(mDisplayId);
        if (display != null) {
            final Point size = new Point();
            display.getRealSize(size);
            if (size.x > 0 && size.y > 0) {
                return new Rect(0, 0, size.x, size.y);
            }
        }
        return new Rect(0, 0,
                mWindowContext.getResources().getDisplayMetrics().widthPixels,
                mWindowContext.getResources().getDisplayMetrics().heightPixels);
    }

    private Rect getNativeTaskbarMaximizedBounds() {
        final Rect bounds = getNativeFullscreenBounds();
        bounds.bottom = Math.max(1,
                bounds.bottom - dp(DESKTOP_TASKBAR_RESERVE_DP));
        return bounds;
    }

    private Rect getDefaultNativeWindowBounds(final Rect workArea) {
        final int width = Math.min(1200,
                Math.max(Math.min(640, workArea.width()),
                        Math.round(workArea.width() * 0.625f)));
        final int height = Math.min(760,
                Math.max(Math.min(420, workArea.height()),
                        Math.round(workArea.height() * 0.72f)));
        final int left = workArea.left + Math.max(0, (workArea.width() - width) / 2);
        final int top = workArea.top + Math.max(0, (workArea.height() - height) / 2);
        return new Rect(left, top, left + width, top + height);
    }

    private boolean isVisibleFreeformTask(final TaskRepository.TaskEntry task) {
        return task != null
                && task.displayId == mDisplayId
                && task.visible
                && task.isFreeform()
                && !task.home
                && !MAGICDESK_PACKAGE.equals(task.packageName)
                && !task.bounds.isEmpty();
    }

    private void scheduleRefresh(final long delayMillis) {
        if (!mRunning) {
            return;
        }
        final long now = android.os.SystemClock.uptimeMillis();
        final long due = now + Math.max(0, delayMillis);
        if (mRefreshDueUptimeMillis >= 0 && mRefreshDueUptimeMillis <= due) {
            return;
        }
        mHandler.removeCallbacks(mRefreshRunnable);
        mRefreshDueUptimeMillis = due;
        mHandler.postDelayed(mRefreshRunnable, Math.max(0, due - now));
    }

    private void runScheduledRefresh() {
        mRefreshDueUptimeMillis = -1;
        refresh();
    }

    private int dp(final int value) {
        return Math.round(value * mWindowContext.getResources().getDisplayMetrics().density);
    }
}

package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.UserHandle;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.util.Log;
import android.view.Display;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class DesktopTaskController implements DesktopTaskRuntime {
    interface SnapshotListener {
        void onSnapshot(
                int displayId,
                List<TaskRepository.TaskEntry> tasks,
                Rect workArea,
                boolean sessionTaskArea,
                boolean sessionOwnershipReady,
                Set<Integer> sessionOwnedTaskIds);
    }

    private static final String TAG = "MagicDeskTasks";
    private static final String MAGICDESK_PACKAGE = "io.github.mekhontsev.magicdesk";
    private static final long EVENT_DEBOUNCE_MILLIS = 120;
    private static final long WATCHER_RESTART_MILLIS = 1000;
    static final int SHORTCUT_FULLSCREEN =
            DesktopWindowTransitionController.SHORTCUT_FULLSCREEN;
    static final int SHORTCUT_RESTORE =
            DesktopWindowTransitionController.SHORTCUT_RESTORE;
    static final int SHORTCUT_SNAP_LEFT =
            DesktopWindowTransitionController.SHORTCUT_SNAP_LEFT;
    static final int SHORTCUT_SNAP_RIGHT =
            DesktopWindowTransitionController.SHORTCUT_SNAP_RIGHT;
    static final int SHORTCUT_CLOSE =
            DesktopWindowTransitionController.SHORTCUT_CLOSE;
    private final Context mApplicationContext;
    private final Handler mHandler;
    private final Runnable mTaskStackChanged;
    private final SnapshotListener mSnapshotListener;
    private final DesktopTaskWatcher mTaskWatcher;
    private final PhoneTouchpadReconciler mPhoneTouchpadReconciler;
    private final PlatformWindowingDriver mWindowing;
    private final DesktopDisplayTaskState mDisplayTaskState;
    private final DesktopTaskRuntimeRegistry mTaskRuntimeStates;
    private final NativeWindowBoundsController mNativeWindowBounds;
    private final DesktopWindowTransitionController mWindowTransitions;
    private final AppWindowStateTracker mAppWindowStates;
    private final DesktopAutomationTaskEventTracker mAutomationEvents =
            new DesktopAutomationTaskEventTracker();
    private final Runnable mRefreshRunnable = this::runScheduledRefresh;

    private Context mWindowContext;
    private int mDisplayId = -1;
    private int mGeneration;
    private volatile int mTaskWatcherGeneration;
    private volatile int mFocusingTaskId = -1;
    private volatile int mActiveTaskId = -1;
    private long mRefreshDueUptimeMillis = -1;
    private boolean mRunning;
    private volatile boolean mTaskWatcherRunning;
    private boolean mTaskWatcherReady;
    private boolean mSessionOwnershipReady;
    private volatile List<TaskRepository.TaskEntry> mLatestTasks =
            Collections.emptyList();
    private Set<Integer> mSessionOwnedTaskIds = Collections.emptySet();
    private final Set<Integer> mTaskbarConcealedTaskIds =
            new LinkedHashSet<>();
    private List<Integer> mShowDesktopRestoreOrder = Collections.emptyList();
    private Set<Integer> mShowDesktopNewlyConcealedTaskIds =
            Collections.emptySet();
    private final ArrayDeque<ShowDesktopToggleRequest>
            mShowDesktopToggleRequests = new ArrayDeque<>();
    private boolean mShowDesktopToggleRunning;

    private static final class ShowDesktopToggleRequest {
        final int displayId;
        final int desktopHostTaskId;
        final TaskRepository.ActionCallback callback;
        boolean completed;

        ShowDesktopToggleRequest(
                final int displayId,
                final int desktopHostTaskId,
                final TaskRepository.ActionCallback callback) {
            this.displayId = displayId;
            this.desktopHostTaskId = desktopHostTaskId;
            this.callback = callback;
        }
    }

    DesktopTaskController(
            final Context context,
            final Handler handler,
            final Runnable taskStackChanged,
            final SnapshotListener snapshotListener,
            final PlatformWindowingDriver windowing) {
        mApplicationContext = context.getApplicationContext();
        mHandler = handler;
        mTaskStackChanged = taskStackChanged;
        mSnapshotListener = snapshotListener;
        mWindowing = windowing;
        mPhoneTouchpadReconciler = new PhoneTouchpadReconciler();
        mAppWindowStates = new AppWindowStateTracker(handler);
        mDisplayTaskState = new DesktopDisplayTaskState();
        mTaskRuntimeStates = new DesktopTaskRuntimeRegistry();
        mNativeWindowBounds = new NativeWindowBoundsController(
                mApplicationContext,
                handler,
                mTaskRuntimeStates,
                new NativeWindowBoundsController.RuntimeState() {
                    @Override
                    public int displayId() {
                        return mDisplayId;
                    }

                    @Override
                    public Context windowContext() {
                        return mWindowContext;
                    }

                    @Override
                    public DesktopViewport viewport() {
                        return DesktopRuntimeBridge.getDesktopViewport(
                                mDisplayId);
                    }

                    @Override
                    public Rect workAreaBounds() {
                        // A display context can retain Nubia's physical phone
                        // density after the virtual display is overridden.
                        // Reuse the taskbar geometry measured by the shell.
                        return DesktopRuntimeBridge.getDesktopWorkAreaBounds(
                                mDisplayId);
                    }

                    @Override
                    public void scheduleRefresh() {
                        DesktopTaskController.this.scheduleRefresh(0);
                    }
                });
        mWindowTransitions = new DesktopWindowTransitionController(
                mHandler,
                mNativeWindowBounds,
                mDisplayTaskState,
                mTaskRuntimeStates,
                new DesktopWindowTransitionController.RuntimeState() {
                    @Override
                    public int displayId() {
                        return mDisplayId;
                    }

                    @Override
                    public boolean isRunning() {
                        return mRunning;
                    }

                    @Override
                    public void focusTask(final int taskId) {
                        if (!mRunning || taskId < 0) {
                            return;
                        }
                        if (mTaskWatcherReady) {
                            sendWorkspaceCommand(
                                    DesktopWorkspaceCommand.ACTIVATE,
                                    mDisplayId,
                                    Collections.singletonList(
                                            Integer.valueOf(taskId)),
                                    null);
                        } else {
                            TaskRepository.bringTaskToFront(
                                    mDisplayId, taskId, null);
                        }
                    }

                    @Override
                    public void demoteTask(final int taskId) {
                        requestTaskDemotion(
                                taskId,
                                result -> {
                                    if (!result.success) {
                                        Log.w(TAG,
                                                "task demotion failed task="
                                                        + taskId
                                                        + " message="
                                                        + result.message);
                                    }
                                });
                    }

                    @Override
                    public void scheduleRefresh() {
                        DesktopTaskController.this.scheduleRefresh(0);
                    }
                },
                this::submitWindowTransition);
        mTaskWatcher = new DesktopTaskWatcher(
                mHandler,
                new DesktopTaskWatcher.Listener() {
                    @Override
                    public boolean isActive(final int generation) {
                        return mTaskWatcherRunning
                                && generation == mTaskWatcherGeneration;
                    }

                    @Override
                    public void onReady(final int generation) {
                        mTaskWatcherReady = true;
                        if (mRunning) {
                            configureTaskWatcher();
                            scheduleRefresh(EVENT_DEBOUNCE_MILLIS);
                        }
                        notifyTaskStackChanged();
                    }

                    @Override
                    public void onChanged(final int generation) {
                        notifyTaskStackChanged();
                        if (mRunning) {
                            scheduleRefresh(EVENT_DEBOUNCE_MILLIS);
                        }
                    }

                    @Override
                    public void onImmersiveRequest(
                            final int generation,
                            final int taskId,
                            final boolean requesting,
                            final boolean initialSample,
                            final boolean foreground) {
                        if (mRunning) {
                            if (!requesting
                                    && !initialSample
                                    && foreground) {
                                confirmTrackedFocus(taskId);
                            }
                            mWindowTransitions.handleImmersiveRequest(
                                    taskId,
                                    requesting,
                                    initialSample,
                                    foreground);
                        }
                    }

                    @Override
                    public void onTaskRequestedOrientationChanged(
                            final int generation,
                            final int taskId,
                            final int requestedOrientation) {
                        if (mRunning) {
                            mWindowTransitions.observeRequestedOrientation(
                                    taskId, requestedOrientation);
                        }
                    }

                    @Override
                    public void onTaskGone(
                        final int generation,
                        final int taskId) {
                        if (mRunning) {
                            clearTrackedFocus(taskId);
                            mWindowTransitions.forgetTaskState(taskId);
                        }
                    }

                    @Override
                    public void onWindowingModeChanged(
                            final int generation,
                            final int taskId,
                            final int previousMode,
                            final int currentMode,
                            final int previousCaptionSourceId,
                            final boolean backgroundAppFullscreenReleased) {
                        if (!mRunning) {
                            return;
                        }
                        Log.d(TAG, "windowing mode task=" + taskId
                                + " " + previousMode + " -> " + currentMode);
                        mWindowTransitions.observeWindowingModeChange(
                                taskId,
                                previousMode,
                                currentMode,
                                backgroundAppFullscreenReleased);
                        // Managed fullscreen performs an immediate refresh.
                        // Repeat it once when the existing task monitor sees
                        // the mode change: a late client relayout can restore
                        // the old Nubia caption source after the first update.
                        // Native caption-button transitions use this same
                        // one-shot path as their primary refresh.
                        if (TaskCaptionInsetsRefresher
                                .shouldRefreshAfterWindowingModeChange(
                                        previousMode,
                                        currentMode,
                                        previousCaptionSourceId)) {
                            mTaskWatcher.refreshTaskCaption(
                                    mDisplayId,
                                    taskId,
                                    previousCaptionSourceId);
                        }
                        scheduleRefresh(0);
                    }

                    @Override
                    public void onFreeformBoundsChanged(
                            final int generation,
                            final int taskId,
                            final String stateKey,
                            final int displayId,
                            final Rect bounds) {
                        if (!mRunning || displayId != mDisplayId) {
                            return;
                        }
                        if (!mNativeWindowBounds
                                .isNativeCaptionSnapOutsideWorkArea(bounds)) {
                            mAppWindowStates.observe(
                                    stateKey,
                                    displayId,
                                    bounds,
                                    mNativeWindowBounds
                                            .getTaskbarMaximizedBounds(),
                                    mNativeWindowBounds
                                            .getFullscreenBounds());
                        }
                        scheduleRefresh(0);
                    }

                    @Override
                    public void onInputFocusRefreshRequired(
                            final int generation,
                            final int focusedTaskId) {
                        if (mRunning) {
                            DesktopRuntimeBridge.refreshDesktopInputFocus(
                                    mDisplayId,
                                    focusedTaskId,
                                    () -> mTaskWatcher
                                            .notifyInputFocusRefreshComplete(
                                                    focusedTaskId));
                        }
                    }

                    @Override
                    public void onTaskFocusChanged(
                            final int generation,
                            final int taskId,
                            final int displayId,
                            final boolean focused) {
                        if (!mRunning || !focused
                                || displayId != mDisplayId) {
                            return;
                        }
                        // System Back and native task activation bypass the
                        // requested-focus path. Keep host focusability in sync
                        // with the framework callback before input repair runs.
                        DesktopRuntimeBridge.prepareTaskFocus(
                                displayId, taskId);
                        if (mFocusingTaskId >= 0
                                && mFocusingTaskId != taskId) {
                            mFocusingTaskId = -1;
                        }
                        restoreTaskbarTask(taskId);
                        mActiveTaskId = taskId;
                        confirmTrackedFocus(taskId);
                        recordFocusEvent(
                                "focus_observed",
                                displayId,
                                taskId,
                                true,
                                "framework task focus");
                        scheduleRefresh(0);
                    }

                    @Override
                    public void onDesktopTaskAreaForegroundChanged(
                            final int generation,
                            final boolean foreground) {
                        if (mRunning
                                && taskAreaPolicy()
                                        .usesManagedHostArea()) {
                            DesktopRuntimeBridge
                                    .setDesktopPlaneForeground(
                                            mDisplayId, foreground);
                        }
                    }

                    @Override
                    public void onDesktopTaskOwnershipChanged(
                            final int generation,
                            final int displayId,
                            final int[] taskIds) {
                        if (!mRunning || displayId != mDisplayId) {
                            return;
                        }
                        final Set<Integer> owned = new HashSet<>();
                        if (taskIds != null) {
                            for (final int taskId : taskIds) {
                                if (taskId >= 0) {
                                    owned.add(Integer.valueOf(taskId));
                                }
                            }
                        }
                        mSessionOwnedTaskIds = owned;
                        mSessionOwnershipReady = true;
                        scheduleRefresh(0);
                    }

                    @Override
                    public void onDisconnected(final int generation) {
                        mTaskWatcherReady = false;
                        clearSessionOwnership();
                        if (mRunning) {
                            scheduleRefresh(0);
                        }
                        mHandler.postDelayed(() -> {
                            if (mTaskWatcherRunning
                                    && generation == mTaskWatcherGeneration
                                    && ShellAccess.isReady()) {
                                startTaskWatcher(generation);
                            }
                        }, WATCHER_RESTART_MILLIS);
                    }
                });
    }

    void start(final int displayId) {
        if (displayId < 0) {
            stop();
            return;
        }
        if (mRunning && mDisplayId == displayId) {
            configureTaskWatcher();
            scheduleRefresh(0);
            return;
        }
        // A phone session can pre-create its shell task area before this
        // controller becomes active. Do not clear that valid configuration on
        // the initial start; only tear down an earlier controller display.
        if (mRunning || mDisplayId >= Display.DEFAULT_DISPLAY) {
            stop();
        }
        mDisplayId = displayId;
        clearSessionOwnership();
        mRunning = createWindowContext(displayId);
        if (!mRunning) {
            return;
        }
        mGeneration++;
        if (mTaskWatcherReady) {
            configureTaskWatcher();
        }
        scheduleRefresh(0);
        Log.i(TAG, "started on display=" + displayId);
    }

    void stop() {
        mRunning = false;
        mGeneration++;
        cancelShowDesktopToggleRequests("desktop task runtime stopped");
        mHandler.removeCallbacks(mRefreshRunnable);
        mRefreshDueUptimeMillis = -1;
        mTaskWatcher.setPhoneTouchpadRequested(false);
        mTaskWatcher.clearConfiguration(mDisplayId);
        mWindowContext = null;
        mDisplayId = -1;
        mFocusingTaskId = -1;
        mActiveTaskId = -1;
        mLatestTasks = Collections.emptyList();
        synchronized (mTaskbarConcealedTaskIds) {
            mTaskbarConcealedTaskIds.clear();
            mShowDesktopRestoreOrder = Collections.emptyList();
            mShowDesktopNewlyConcealedTaskIds = Collections.emptySet();
        }
        clearSessionOwnership();
        mNativeWindowBounds.reset();
        mAppWindowStates.stop();
        mPhoneTouchpadReconciler.reset();
        mDisplayTaskState.clear();
        mTaskRuntimeStates.clear();
        mAutomationEvents.reset();
    }

    void destroy() {
        stop();
        setTaskWatcherEnabled(false);
        mTaskWatcher.destroy();
    }

    void setTaskWatcherEnabled(final boolean enabled) {
        setTaskWatcherEnabled(enabled, null);
    }

    void setTaskWatcherEnabled(
            final boolean enabled,
            final Runnable completion) {
        if (enabled == mTaskWatcherRunning) {
            if (completion != null) {
                completion.run();
            }
            return;
        }
        mTaskWatcherRunning = enabled;
        mTaskWatcherReady = false;
        mTaskWatcherGeneration++;
        if (enabled) {
            startTaskWatcher(mTaskWatcherGeneration);
            if (completion != null) {
                completion.run();
            }
        } else {
            mTaskWatcher.stop(completion);
        }
    }

    @Override
    public boolean isTaskObserverReady() {
        return mTaskWatcherRunning && mTaskWatcherReady;
    }

    @Override
    public List<TaskRepository.TaskEntry> getVisibleFreeformTasks(
            final int displayId) {
        return isActiveOnDisplay(displayId)
                ? mDisplayTaskState.visibleTasks() : null;
    }

    @Override
    public boolean closeTask(
            final TaskRepository.TaskEntry task,
            final TaskRepository.ActionCallback callback) {
        if (task == null || task.taskId < 0
                || !isActiveOnDisplay(task.displayId)
                || (!task.isFreeform() && !task.isFullscreen())) {
            return false;
        }
        final int generation = mGeneration;
        mHandler.post(() -> {
            if (!mRunning || generation != mGeneration
                    || mDisplayId != task.displayId
                    || !mTaskWatcherReady) {
                TaskRepository.closeTask(task, callback);
                return;
            }
            final boolean submitted = closeDesktopTaskInternal(
                    task.taskId,
                    result -> {
                        if (!mRunning || generation != mGeneration
                                || mDisplayId != task.displayId) {
                            completeActionCallback(
                                    callback,
                                    false,
                                    "desktop session changed");
                            return;
                        }
                        if (result.success) {
                            scheduleRefresh(0);
                            completeActionCallback(callback, true, "");
                            return;
                        }
                        TaskRepository.closeTask(task, callback);
                    });
            if (submitted) {
                return;
            }
            // Returning true transfers the whole asynchronous close operation
            // to this controller. If its hierarchy-preserving path becomes
            // unavailable later, complete the operation through the normal
            // task-removal backend instead of losing the caller's fallback.
            TaskRepository.closeTask(task, callback);
        });
        return true;
    }

    @Override
    public boolean forceStopPackage(
            final String packageName,
            final TaskRepository.ActionCallback callback) {
        if (!PackageNameValidator.isSafe(packageName)
                || MAGICDESK_PACKAGE.equals(packageName)
                || !mRunning
                || !mTaskWatcherReady) {
            return false;
        }
        final List<TaskRepository.TaskEntry> visibleTasks =
                mDisplayTaskState.visibleTasks();
        if (!containsPackageTask(visibleTasks, packageName)) {
            return false;
        }
        final DesktopSessionSnapshot session =
                DesktopRuntimeBridge.getSessionSnapshot();
        final int hostTaskId = session.activeDisplayId() == mDisplayId
                ? session.hostTaskId() : -1;
        final int focusTaskId = selectPackageRemovalSurvivorTaskId(
                visibleTasks, packageName, hostTaskId);
        if (focusTaskId < 0) {
            return false;
        }

        final int displayId = mDisplayId;
        final int generation = mGeneration;
        mHandler.post(() -> {
            if (!mRunning || generation != mGeneration
                    || displayId != mDisplayId || !mTaskWatcherReady) {
                TaskRepository.forceStop(packageName, callback);
                return;
            }
            // Remove the package's desktop tasks with the survivor handoff in
            // one committed hierarchy update. A later process stop then has
            // no foreground task whose death could make Android launch HOME.
            DesktopRuntimeBridge.prepareTaskFocus(displayId, focusTaskId);
            mTaskWatcher.removeDesktopPackageTasks(
                    displayId,
                    packageName,
                    focusTaskId,
                    removal -> {
                        if (!removal.success) {
                            Log.w(TAG, "desktop force-stop removal failed: "
                                    + removal.message);
                        }
                        // The task may have disappeared because the process
                        // already crashed. Task removal preserves the desktop
                        // handoff, but it is not a prerequisite for honoring
                        // an explicit package force-stop request.
                        TaskRepository.forceStop(
                                packageName,
                                result -> {
                                    if (mRunning
                                            && generation == mGeneration
                                            && displayId == mDisplayId) {
                                        scheduleRefresh(0);
                                    }
                                    completeActionCallback(
                                            callback,
                                            result.success,
                                            result.message);
                                });
                    });
        });
        return true;
    }

    private boolean closeDesktopTaskInternal(
            final int taskId,
            final TaskRepository.ActionCallback callback) {
        if (!mRunning || !mTaskWatcherReady || taskId < 0) {
            return false;
        }
        final DesktopSessionSnapshot session =
                DesktopRuntimeBridge.getSessionSnapshot();
        final int hostTaskId = session.activeDisplayId() == mDisplayId
                ? session.hostTaskId() : -1;
        final int focusTaskId = selectCloseSurvivorTaskId(
                mDisplayTaskState.visibleTasks(), taskId, hostTaskId);
        if (focusTaskId < 0) {
            return false;
        }
        // Relayout the host before the shell-side focus transaction. A
        // non-focusable host makes SystemUI start HOME when the last
        // freeform task disappears on the phone display.
        DesktopRuntimeBridge.prepareTaskFocus(mDisplayId, focusTaskId);
        return mTaskWatcher.closeDesktopTask(
                mDisplayId, taskId, focusTaskId, callback);
    }

    private boolean submitWindowTransition(
            final DesktopWindowTransitionRequest request,
            final TaskRepository.ActionCallback callback) {
        if (request == null || request.displayId != mDisplayId
                || !mRunning || !mTaskWatcherReady) {
            return false;
        }
        final int generation = mGeneration;
        final int displayId = mDisplayId;
        final TaskRepository.ActionCallback scopedCallback = result -> {
            if (!mRunning || generation != mGeneration
                    || displayId != mDisplayId) {
                return;
            }
            completeActionCallback(
                    callback, result.success, result.message);
        };
        switch (request.operation) {
            case ENTER_FULLSCREEN:
                return mTaskWatcher.beginFullscreenTask(
                        request.displayId,
                        request.taskId,
                        scopedCallback);
            case ENTER_APP_FULLSCREEN:
                return mTaskWatcher.beginAppFullscreenTask(
                        request.displayId,
                        request.taskId,
                        request.bounds(),
                        scopedCallback);
            case RESTORE_FREEFORM:
                return mTaskWatcher.restoreFullscreenTask(
                        request.displayId,
                        request.taskId,
                        request.bounds(),
                        scopedCallback);
            case CLOSE_FREEFORM:
            case CLOSE_FULLSCREEN:
                return closeDesktopTaskInternal(
                        request.taskId, scopedCallback);
            default:
                return false;
        }
    }

    static int selectCloseSurvivorTaskId(
            final List<TaskRepository.TaskEntry> visibleTasks,
            final int closingTaskId,
            final int hostTaskId) {
        if (visibleTasks != null) {
            for (final TaskRepository.TaskEntry task : visibleTasks) {
                if (task != null && task.taskId != closingTaskId) {
                    return task.taskId;
                }
            }
        }
        return hostTaskId;
    }

    static int selectPackageRemovalSurvivorTaskId(
            final List<TaskRepository.TaskEntry> visibleTasks,
            final String packageName,
            final int hostTaskId) {
        if (visibleTasks != null) {
            for (final TaskRepository.TaskEntry task : visibleTasks) {
                if (task != null
                        && !packageName.equals(task.packageName)) {
                    return task.taskId;
                }
            }
        }
        return hostTaskId;
    }

    private static boolean containsPackageTask(
            final List<TaskRepository.TaskEntry> visibleTasks,
            final String packageName) {
        if (visibleTasks == null) {
            return false;
        }
        for (final TaskRepository.TaskEntry task : visibleTasks) {
            if (task != null && packageName.equals(task.packageName)) {
                return true;
            }
        }
        return false;
    }

    static List<TaskRepository.TaskEntry> selectVisibleFreeformTasks(
            final TaskRepository.Snapshot snapshot) {
        if (snapshot == null || !snapshot.available) {
            return Collections.emptyList();
        }
        final List<TaskRepository.TaskEntry> visibleTasks =
                new ArrayList<>();
        for (final TaskRepository.TaskEntry task : snapshot.tasks) {
            if (isDesktopHostTask(task)) {
                break;
            }
            if (task.visible && task.isFreeform()
                    && DesktopManagedTaskPolicy
                            .isControllableApplicationTask(task)) {
                visibleTasks.add(task);
            }
        }
        return visibleTasks;
    }

    @Override
    public List<TaskRepository.TaskEntry> getLastVisibleFreeformTasks(
            final int displayId) {
        return !isActiveOnDisplay(displayId)
                ? Collections.emptyList()
                : mDisplayTaskState.lastVisibleTasks();
    }

    @Override
    public Boolean hasVisibleAppTaskSnapshot(final int displayId) {
        return isActiveOnDisplay(displayId)
                ? mDisplayTaskState.hasVisibleAppTask() : null;
    }

    @Override
    public void beginFullscreenTransition(final int displayId,
            final List<TaskRepository.TaskEntry> visibleTasks, final int excludedTaskId) {
        if (isActiveOnDisplay(displayId)) {
            mDisplayTaskState.beginFullscreenTransition(
                    visibleTasks, excludedTaskId);
        }
    }

    @Override
    public void finishFullscreenTransition(final int displayId,
            final boolean success) {
        if (isActiveOnDisplay(displayId)) {
            mDisplayTaskState.finishFullscreenTransition(success);
            scheduleRefresh(0);
        }
    }

    @Override
    public void forgetVisibleFreeformTasks(final int displayId) {
        if (isActiveOnDisplay(displayId)) {
            mDisplayTaskState.forgetVisibleTasks();
        }
    }

    @Override
    public void focusStack(final List<TaskRepository.TaskEntry> topFirstTasks,
            final TaskRepository.TaskEntry topTask,
            final TaskRepository.ActionCallback callback) {
        if (!mRunning || !mTaskWatcherReady) {
            final int requestedDisplayId = requestedDisplayId(
                    topFirstTasks, topTask);
            if (isActiveDesktopDisplay(requestedDisplayId)) {
                completeActionCallback(
                        callback, false, "desktop task observer unavailable");
            } else {
                TaskRepository.bringStackToFront(
                        topFirstTasks, topTask, callback);
            }
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
            mFocusingTaskId = -1;
            completeActionCallback(callback, true, "no tasks");
            return;
        }
        final List<Integer> orderedTaskIdList =
                new ArrayList<>(orderedTaskIds);
        final int focusedTaskId = orderedTaskIdList.get(
                orderedTaskIdList.size() - 1).intValue();
        focusThroughGateway(
                orderedTaskIdList,
                focusedTaskId,
                callback);
    }

    @Override
    public void focusDesktopTasks(
            final int displayId,
            final List<Integer> taskIds,
            final TaskRepository.ActionCallback callback) {
        if (taskIds == null || taskIds.isEmpty()) {
            completeActionCallback(callback, false, "no tasks");
            return;
        }
        if (!mRunning || !mTaskWatcherReady || mDisplayId != displayId) {
            if (isActiveDesktopDisplay(displayId)) {
                completeActionCallback(
                        callback, false, "desktop task observer unavailable");
            } else {
                TaskRepository.runFocusAction(displayId, taskIds, callback);
            }
            return;
        }
        final int generation = mGeneration;
        TaskRepository.load(displayId, snapshot -> mHandler.post(() -> {
            if (!mRunning || generation != mGeneration
                    || mDisplayId != displayId || !mTaskWatcherReady) {
                completeActionCallback(
                        callback, false, "desktop task runtime unavailable");
                return;
            }
            if (!snapshot.available) {
                completeActionCallback(callback, false, snapshot.error);
                return;
            }
            final int focusedTaskId = taskIds.get(
                    taskIds.size() - 1).intValue();
            final TaskRepository.TaskEntry focusedTask = findTask(
                    snapshot.tasks, focusedTaskId);
            if (focusedTask == null || !isFocusableTask(focusedTask)) {
                completeActionCallback(callback, false, "task unavailable");
                return;
            }
            focusThroughGateway(
                    taskIds,
                    focusedTaskId,
                    callback);
        }));
    }

    @Override
    public void showDesktop(
            final int displayId,
            final int desktopHostTaskId,
            final TaskRepository.ActionCallback callback) {
        if (displayId < 0 || desktopHostTaskId < 0) {
            completeActionCallback(callback, false, "invalid desktop host");
            return;
        }
        synchronized (mTaskbarConcealedTaskIds) {
            mShowDesktopRestoreOrder = Collections.emptyList();
            mShowDesktopNewlyConcealedTaskIds = Collections.emptySet();
        }
        demoteNextShowDesktopTask(
                displayId,
                desktopHostTaskId,
                mGeneration,
                new ArrayList<>(),
                new LinkedHashSet<>(),
                callback);
    }

    private void demoteNextShowDesktopTask(
            final int displayId,
            final int desktopHostTaskId,
            final int generation,
            final List<Integer> restoreOrder,
            final Set<Integer> newlyConcealedTaskIds,
            final TaskRepository.ActionCallback callback) {
        TaskRepository.load(displayId, snapshot -> mHandler.post(() -> {
            if (!mRunning || generation != mGeneration
                    || mDisplayId != displayId || !mTaskWatcherReady) {
                completeActionCallback(
                        callback, false, "desktop task runtime unavailable");
                return;
            }
            if (!snapshot.available) {
                completeActionCallback(callback, false, snapshot.error);
                return;
            }
            if (findTask(snapshot.tasks, desktopHostTaskId) == null) {
                completeActionCallback(
                        callback, false, "desktop host unavailable");
                return;
            }
            final Set<Integer> concealedTaskIds;
            synchronized (mTaskbarConcealedTaskIds) {
                concealedTaskIds = new LinkedHashSet<>(
                        mTaskbarConcealedTaskIds);
            }
            final TaskRepository.TaskEntry activeTask =
                    selectShowDesktopDemotionTask(
                            snapshot.tasks,
                            displayId,
                            desktopHostTaskId,
                            concealedTaskIds);
            if (activeTask == null) {
                sendWorkspaceCommand(
                        DesktopWorkspaceCommand.PRESENT_DESKTOP,
                        displayId,
                        Collections.singletonList(
                                Integer.valueOf(desktopHostTaskId)),
                        result -> {
                            if (result.success) {
                                synchronized (mTaskbarConcealedTaskIds) {
                                    mShowDesktopRestoreOrder =
                                            new ArrayList<>(restoreOrder);
                                    mShowDesktopNewlyConcealedTaskIds =
                                            new LinkedHashSet<>(
                                                    newlyConcealedTaskIds);
                                }
                                scheduleRefresh(0);
                            }
                            completeActionCallback(
                                    callback,
                                    result.success,
                                    result.success
                                            ? "desktop shown"
                                            : result.message);
                        });
                return;
            }
            demoteTask(
                    snapshot,
                    activeTask.taskId,
                    result -> {
                        if (!result.success) {
                            completeActionCallback(
                                    callback, false, result.message);
                            return;
                        }
                        restoreOrder.add(
                                0, Integer.valueOf(activeTask.taskId));
                        newlyConcealedTaskIds.add(
                                Integer.valueOf(activeTask.taskId));
                        synchronized (mTaskbarConcealedTaskIds) {
                            mShowDesktopRestoreOrder =
                                    new ArrayList<>(restoreOrder);
                            mShowDesktopNewlyConcealedTaskIds =
                                    new LinkedHashSet<>(
                                            newlyConcealedTaskIds);
                        }
                        demoteNextShowDesktopTask(
                                displayId,
                                desktopHostTaskId,
                                generation,
                                restoreOrder,
                                newlyConcealedTaskIds,
                                callback);
                    });
        }));
    }

    @Override
    public void restoreShowDesktopWorkspace(
            final int displayId,
            final int desktopHostTaskId,
            final TaskRepository.ActionCallback callback) {
        if (displayId < 0 || desktopHostTaskId < 0) {
            completeActionCallback(callback, false, "invalid desktop host");
            return;
        }
        final int generation = mGeneration;
        TaskRepository.load(displayId, snapshot -> mHandler.post(() -> {
            if (!mRunning || generation != mGeneration
                    || mDisplayId != displayId || !mTaskWatcherReady) {
                completeActionCallback(
                        callback, false, "desktop task runtime unavailable");
                return;
            }
            if (!snapshot.available) {
                completeActionCallback(callback, false, snapshot.error);
                return;
            }
            final List<Integer> savedOrder;
            final Set<Integer> savedConcealedTaskIds;
            synchronized (mTaskbarConcealedTaskIds) {
                savedOrder = new ArrayList<>(mShowDesktopRestoreOrder);
                savedConcealedTaskIds = new LinkedHashSet<>(
                        mShowDesktopNewlyConcealedTaskIds);
            }
            final List<Integer> liveOrder = liveTaskOrder(
                    snapshot.tasks, displayId, savedOrder);
            if (liveOrder.isEmpty()) {
                completeActionCallback(
                        callback, true, "no saved workspace");
                return;
            }
            synchronized (mTaskbarConcealedTaskIds) {
                mTaskbarConcealedTaskIds.removeAll(savedConcealedTaskIds);
            }
            final int targetTaskId = liveOrder.get(
                    liveOrder.size() - 1).intValue();
            focusThroughGateway(
                    DesktopWorkspaceCommand.RESTORE_WORKSPACE,
                    liveOrder,
                    targetTaskId,
                    result -> {
                        synchronized (mTaskbarConcealedTaskIds) {
                            if (result.success) {
                                mShowDesktopRestoreOrder =
                                        Collections.emptyList();
                                mShowDesktopNewlyConcealedTaskIds =
                                        Collections.emptySet();
                            } else {
                                mTaskbarConcealedTaskIds.addAll(
                                        savedConcealedTaskIds);
                            }
                        }
                        scheduleRefresh(0);
                        completeActionCallback(
                                callback, result.success, result.message);
                    });
        }));
    }

    @Override
    public void toggleShowDesktopWorkspace(
            final int displayId,
            final int desktopHostTaskId,
            final TaskRepository.ActionCallback callback) {
        if (displayId < 0 || desktopHostTaskId < 0) {
            completeActionCallback(callback, false, "invalid desktop host");
            return;
        }
        mHandler.post(() -> {
            if (!mRunning || !mTaskWatcherReady || mDisplayId != displayId) {
                completeActionCallback(
                        callback, false, "desktop task runtime unavailable");
                return;
            }
            mShowDesktopToggleRequests.addLast(
                    new ShowDesktopToggleRequest(
                            displayId, desktopHostTaskId, callback));
            runNextShowDesktopToggle();
        });
    }

    private void runNextShowDesktopToggle() {
        if (mShowDesktopToggleRunning) {
            return;
        }
        final ShowDesktopToggleRequest request =
                mShowDesktopToggleRequests.peekFirst();
        if (request == null) {
            return;
        }
        mShowDesktopToggleRunning = true;
        final boolean restore;
        synchronized (mTaskbarConcealedTaskIds) {
            restore = !mShowDesktopRestoreOrder.isEmpty();
        }
        final TaskRepository.ActionCallback completion = result ->
                mHandler.post(() -> finishShowDesktopToggle(request, result));
        if (restore) {
            restoreShowDesktopWorkspace(
                    request.displayId,
                    request.desktopHostTaskId,
                    completion);
        } else {
            showDesktop(
                    request.displayId,
                    request.desktopHostTaskId,
                    completion);
        }
    }

    private void finishShowDesktopToggle(
            final ShowDesktopToggleRequest request,
            final TaskRepository.ActionResult result) {
        if (request.completed) {
            return;
        }
        request.completed = true;
        mShowDesktopToggleRequests.remove(request);
        mShowDesktopToggleRunning = false;
        completeActionCallback(
                request.callback,
                result != null && result.success,
                result == null ? "desktop workspace command failed"
                        : result.message);
        runNextShowDesktopToggle();
    }

    private void cancelShowDesktopToggleRequests(final String message) {
        while (!mShowDesktopToggleRequests.isEmpty()) {
            final ShowDesktopToggleRequest request =
                    mShowDesktopToggleRequests.removeFirst();
            if (!request.completed) {
                request.completed = true;
                completeActionCallback(request.callback, false, message);
            }
        }
        mShowDesktopToggleRunning = false;
    }

    @Override
    public void restoreDesktopWorkspace(
            final int displayId,
            final List<Integer> backToFrontTaskIds,
            final TaskRepository.ActionCallback callback) {
        if (displayId < 0 || backToFrontTaskIds == null
                || backToFrontTaskIds.isEmpty()) {
            completeActionCallback(callback, false, "invalid session workspace");
            return;
        }
        final int generation = mGeneration;
        TaskRepository.load(displayId, snapshot -> mHandler.post(() -> {
            if (!mRunning || generation != mGeneration
                    || mDisplayId != displayId || !mTaskWatcherReady) {
                completeActionCallback(
                        callback, false, "desktop task observer unavailable");
                return;
            }
            if (!snapshot.available) {
                completeActionCallback(callback, false, snapshot.error);
                return;
            }
            final List<Integer> liveOrder = liveTaskOrder(
                    snapshot.tasks, displayId, backToFrontTaskIds);
            if (liveOrder.isEmpty()) {
                completeActionCallback(callback, false, "session tasks unavailable");
                return;
            }
            final int targetTaskId = liveOrder.get(
                    liveOrder.size() - 1).intValue();
            focusThroughGateway(
                    DesktopWorkspaceCommand.RESTORE_SESSION,
                    liveOrder,
                    targetTaskId,
                    callback);
        }));
    }

    @Override
    public void toggleTaskbarTask(
            final int displayId,
            final int taskId,
            final TaskRepository.ActionCallback callback) {
        if (displayId < 0 || taskId < 0) {
            completeActionCallback(callback, false, "invalid task");
            return;
        }
        final int generation = mGeneration;
        TaskRepository.load(displayId, snapshot -> mHandler.post(() -> {
            if (!mRunning || generation != mGeneration
                    || mDisplayId != displayId || !mTaskWatcherReady) {
                completeActionCallback(
                        callback, false, "desktop task runtime unavailable");
                return;
            }
            if (!snapshot.available) {
                completeActionCallback(callback, false, snapshot.error);
                return;
            }
            final TaskRepository.TaskEntry task = findTask(
                    snapshot.tasks, taskId);
            if (task == null || !isFocusableTask(task)) {
                completeActionCallback(callback, false, "task unavailable");
                return;
            }
            final Set<Integer> effectiveConcealedTaskIds;
            synchronized (mTaskbarConcealedTaskIds) {
                effectiveConcealedTaskIds = new LinkedHashSet<>(
                        mTaskbarConcealedTaskIds);
            }
            if (EffectiveTaskStack.shouldActivateTaskbarTarget(
                    snapshot,
                    task,
                    effectiveConcealedTaskIds,
                    mActiveTaskId)) {
                focusThroughGateway(
                        Collections.singletonList(Integer.valueOf(taskId)),
                        taskId,
                        callback);
                return;
            }

            demoteTask(snapshot, taskId, callback);
        }));
    }

    private void requestTaskDemotion(
            final int taskId,
            final TaskRepository.ActionCallback callback) {
        final int displayId = mDisplayId;
        final int generation = mGeneration;
        TaskRepository.load(displayId, snapshot -> mHandler.post(() -> {
            if (!mRunning || generation != mGeneration
                    || mDisplayId != displayId || !mTaskWatcherReady) {
                completeActionCallback(
                        callback, false, "desktop task runtime unavailable");
                return;
            }
            if (!snapshot.available) {
                completeActionCallback(callback, false, snapshot.error);
                return;
            }
            final TaskRepository.TaskEntry task = findTask(
                    snapshot.tasks, taskId);
            if (task == null || !isFocusableTask(task)) {
                completeActionCallback(callback, false, "task unavailable");
                return;
            }
            final Set<Integer> concealedTaskIds;
            synchronized (mTaskbarConcealedTaskIds) {
                concealedTaskIds = new LinkedHashSet<>(
                        mTaskbarConcealedTaskIds);
            }
            if (EffectiveTaskStack.shouldActivateTaskbarTarget(
                    snapshot,
                    task,
                    concealedTaskIds,
                    mActiveTaskId)) {
                completeActionCallback(
                        callback, false, "task is not foreground");
                return;
            }
            demoteTask(snapshot, taskId, callback);
        }));
    }

    private void demoteTask(
            final TaskRepository.Snapshot snapshot,
            final int taskId,
            final TaskRepository.ActionCallback callback) {
        final boolean alreadyConcealed;
        final Set<Integer> concealedTaskIds;
        synchronized (mTaskbarConcealedTaskIds) {
            alreadyConcealed = !mTaskbarConcealedTaskIds.add(
                    Integer.valueOf(taskId));
            concealedTaskIds = new LinkedHashSet<>(
                    mTaskbarConcealedTaskIds);
        }
        final List<Integer> focusOrder =
                TaskbarTaskOrder.concealActiveTask(
                        snapshot,
                        taskId,
                        mDisplayTaskState.lastVisibleTasks(),
                        concealedTaskIds,
                        DesktopDisplayDrivers.activeTaskAreaPolicy(
                                mDisplayId)
                                .usesIndependentFullscreenPlanes(),
                        mActiveTaskId);
        if (focusOrder.size() < 2) {
            if (!alreadyConcealed) {
                restoreTaskbarTask(taskId);
            }
            completeActionCallback(
                    callback, false, "task stack unavailable");
            return;
        }
        final int targetTaskId = focusOrder.get(
                focusOrder.size() - 1).intValue();
        focusThroughGateway(
                DesktopWorkspaceCommand.DEMOTE,
                focusOrder,
                targetTaskId,
                result -> {
                    if (!result.success && !alreadyConcealed) {
                        restoreTaskbarTask(taskId);
                    }
                    completeActionCallback(
                            callback, result.success, result.message);
                });
    }

    /** Common focus route for taskbar, Alt+Tab, overview, and automation. */
    private void focusThroughGateway(
            final List<Integer> requestedTaskIds,
            final int focusedTaskId,
            final TaskRepository.ActionCallback callback) {
        focusThroughGateway(
                DesktopWorkspaceCommand.ACTIVATE,
                requestedTaskIds,
                focusedTaskId,
                callback);
    }

    private void focusThroughGateway(
            final int operation,
            final List<Integer> requestedTaskIds,
            final int focusedTaskId,
            final TaskRepository.ActionCallback callback) {
        final boolean restoredConcealedTask =
                restoreTaskbarTask(focusedTaskId);
        sendWorkspaceCommand(
                operation,
                mDisplayId,
                requestedTaskIds,
                beginFocusTracking(
                        focusedTaskId,
                        result -> {
                            if (!result.success && restoredConcealedTask) {
                                concealTaskbarTask(focusedTaskId);
                            }
                            completeActionCallback(
                                    callback, result.success, result.message);
                        }));
    }

    static TaskRepository.TaskEntry selectShowDesktopDemotionTask(
            final List<TaskRepository.TaskEntry> topFirstTasks,
            final int displayId,
            final int desktopHostTaskId,
            final Set<Integer> concealedTaskIds) {
        if (topFirstTasks == null) {
            return null;
        }
        for (final TaskRepository.TaskEntry task : topFirstTasks) {
            if (task != null && task.displayId == displayId
                    && task.taskId != desktopHostTaskId
                    && task.active && isFocusableTask(task)
                    && (concealedTaskIds == null
                            || !concealedTaskIds.contains(
                                    Integer.valueOf(task.taskId)))) {
                return task;
            }
        }
        return null;
    }

    static List<Integer> liveTaskOrder(
            final List<TaskRepository.TaskEntry> tasks,
            final int displayId,
            final List<Integer> savedOrder) {
        if (tasks == null || savedOrder == null || savedOrder.isEmpty()) {
            return Collections.emptyList();
        }
        final Set<Integer> liveTaskIds = new HashSet<>();
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task != null && task.displayId == displayId
                    && isFocusableTask(task)) {
                liveTaskIds.add(Integer.valueOf(task.taskId));
            }
        }
        final List<Integer> liveOrder = new ArrayList<>();
        for (final Integer taskId : savedOrder) {
            if (liveTaskIds.contains(taskId)) {
                liveOrder.add(taskId);
            }
        }
        return liveOrder;
    }

    private TaskRepository.ActionCallback beginFocusTracking(
            final int taskId,
            final TaskRepository.ActionCallback callback) {
        final int displayId = mDisplayId;
        mFocusingTaskId = taskId;
        mActiveTaskId = taskId;
        recordFocusEvent(
                "focus_requested", displayId, taskId, true, "requested");
        return result -> {
            if (!result.success) {
                clearTrackedFocus(taskId);
                recordFocusEvent(
                        "focus_request_failed",
                        displayId,
                        taskId,
                        false,
                        result.message);
            }
            completeActionCallback(callback, result.success, result.message);
        };
    }

    private static void recordFocusEvent(
            final String operation,
            final int displayId,
            final int taskId,
            final boolean success,
            final String detail) {
        try {
            DesktopAutomationEventJournal.record(
                    "task",
                    operation,
                    success,
                    detail,
                    new org.json.JSONObject()
                            .put("displayId", displayId)
                            .put("taskId", taskId));
        } catch (org.json.JSONException ignored) {
            DesktopAutomationEventJournal.record(
                    "task", operation, success, detail);
        }
    }

    private void clearTrackedFocus(final int taskId) {
        if (mFocusingTaskId == taskId) {
            mFocusingTaskId = -1;
        }
        if (mActiveTaskId == taskId) {
            mActiveTaskId = -1;
        }
    }

    private void confirmTrackedFocus(final int taskId) {
        if (mFocusingTaskId != taskId) {
            return;
        }
        mActiveTaskId = taskId;
        mFocusingTaskId = -1;
    }

    private void sendWorkspaceCommand(
            final int operation,
            final int displayId,
            final List<Integer> taskIds,
            final TaskRepository.ActionCallback callback) {
        mHandler.post(() -> {
            if (!mRunning || !mTaskWatcherReady || mDisplayId != displayId) {
                if (isActiveDesktopDisplay(displayId)) {
                    completeActionCallback(
                            callback,
                            false,
                            "desktop task observer unavailable");
                } else {
                    TaskRepository.runFocusAction(
                            displayId, taskIds, callback);
                }
                return;
            }
            final int focusedTaskId = taskIds.get(
                    taskIds.size() - 1).intValue();
            if (mWindowing.requiresDesktopInputFocusSynchronization()) {
                // Affected firmware can leave input focus on the host while
                // reporting the raised client task as focused.
                DesktopRuntimeBridge.prepareTaskFocus(
                        displayId, focusedTaskId);
            }
            final int[] physicalOrder = new int[taskIds.size()];
            for (int index = 0; index < taskIds.size(); index++) {
                physicalOrder[index] = taskIds.get(index).intValue();
            }
            final DesktopWorkspaceCommand command =
                    DesktopWorkspaceCommand.create(
                            operation,
                            displayId,
                            focusedTaskId,
                            physicalOrder);
            mTaskWatcher.sendWorkspaceCommand(command, result -> {
                recordWorkspaceCommandEvent(command, result);
                completeActionCallback(
                        callback, result.success, result.message);
            });
        });
    }

    private static boolean isActiveDesktopDisplay(final int displayId) {
        return displayId >= 0
                && DesktopRuntimeBridge.getSessionSnapshot()
                        .activeDisplayId() == displayId;
    }

    private static int requestedDisplayId(
            final List<TaskRepository.TaskEntry> tasks,
            final TaskRepository.TaskEntry target) {
        if (target != null && target.displayId >= 0) {
            return target.displayId;
        }
        if (tasks != null) {
            for (final TaskRepository.TaskEntry task : tasks) {
                if (task != null && task.displayId >= 0) {
                    return task.displayId;
                }
            }
        }
        return -1;
    }

    private static void recordWorkspaceCommandEvent(
            final DesktopWorkspaceCommand command,
            final TaskRepository.ActionResult result) {
        try {
            DesktopAutomationEventJournal.record(
                    "workspace",
                    "command_completed",
                    result.success,
                    command.operationName(),
                    new org.json.JSONObject()
                            .put("operation", command.operationName())
                            .put("displayId", command.displayId)
                            .put("targetTaskId", command.targetTaskId)
                            .put("taskCount",
                                    command.backToFrontTaskIds.length)
                            .put("focusConverged", result.success));
        } catch (org.json.JSONException ignored) {
            DesktopAutomationEventJournal.record(
                    "workspace",
                    "command_completed",
                    result.success,
                    command.operationName());
        }
    }

    @Override
    public boolean handleActiveTaskShortcut(final int shortcut) {
        if (!mRunning) {
            return false;
        }
        final int activeTaskId = mActiveTaskId;
        mHandler.post(() -> handleActiveTaskShortcutInternal(
                shortcut, activeTaskId));
        return true;
    }

    @Override
    public boolean arrangeTask(final int taskId, final int shortcut) {
        if (!mRunning || taskId < 0) {
            return false;
        }
        final int displayId = mDisplayId;
        final int generation = mGeneration;
        TaskRepository.load(displayId, snapshot -> mHandler.post(() -> {
            if (!mRunning || generation != mGeneration
                    || mDisplayId != displayId || !snapshot.available) {
                return;
            }
            TaskRepository.TaskEntry task = null;
            for (final TaskRepository.TaskEntry candidate : snapshot.tasks) {
                if (candidate != null && candidate.taskId == taskId) {
                    task = candidate;
                    break;
                }
            }
            if (task == null) {
                return;
            }
            mWindowTransitions.applyShortcut(task, shortcut);
        }));
        return true;
    }

    @Override
    public void noteManualFreeformTransition(final int taskId) {
        if (mRunning) {
            mWindowTransitions.noteManualFreeformTransition(taskId);
        }
    }

    @Override
    public void beginExplicitWindowedLaunch(final int taskId) {
        if (mRunning) {
            mWindowTransitions.beginExplicitWindowedLaunch(taskId);
        }
    }

    @Override
    public boolean protectExplicitFullscreenTask(
            final int displayId,
            final int taskId) {
        return mRunning
                && mDisplayId == displayId
                && mTaskWatcher.protectExplicitFullscreenTask(
                        displayId, taskId);
    }

    @Override
    public void expectTouchpadDisplacement() {
        if (mRunning) {
            mPhoneTouchpadReconciler.expectDisplacement();
            mTaskWatcher.setPhoneTouchpadPreservation(true);
        }
    }

    @Override
    public void finishTouchpadPreservation() {
        if (mRunning) {
            mTaskWatcher.setPhoneTouchpadPreservation(false);
            mPhoneTouchpadReconciler.finishPreservation();
        }
    }

    @Override
    public void setPhoneTouchpadRequested(final boolean requested) {
        mTaskWatcher.setPhoneTouchpadRequested(requested);
    }

    @Override
    public void disableExternalTaskMigrationProtection() {
        if (!mRunning) {
            return;
        }
        mTaskWatcher.setExternalTaskMigrationProtection(false);
    }

    @Override
    public void restoreExternalTaskMigrationProtection() {
        if (!mRunning) {
            return;
        }
        mTaskWatcher.setExternalTaskMigrationProtection(
                shouldProtectExternalSession());
    }

    @Override
    public boolean dismissTransientActivity() {
        if (!mRunning) {
            return false;
        }
        mHandler.post(this::dismissTransientActivityInternal);
        return true;
    }

    @Override
    public boolean sendSystemBack() {
        if (!mRunning) {
            return false;
        }
        mHandler.post(this::sendSystemBackInternal);
        return true;
    }

    @Override
    public boolean startSelfTestTaskStackGuard(
            final int displayId,
            final int hostTaskId,
            final String stage) {
        return mRunning
                && mTaskWatcherReady
                && mDisplayId == displayId
                && mTaskWatcher.startSelfTestTaskStackGuard(
                        displayId, hostTaskId, stage);
    }

    @Override
    public void setSelfTestTaskStackGuardStage(final String stage) {
        if (mRunning && mTaskWatcherReady) {
            mTaskWatcher.setSelfTestTaskStackGuardStage(stage);
        }
    }

    @Override
    public SelfTestTaskStackReport stopSelfTestTaskStackGuard() {
        return mTaskWatcherReady
                ? mTaskWatcher.stopSelfTestTaskStackGuard()
                : SelfTestTaskStackReport.unavailable(
                        "task observer unavailable");
    }

    @Override
    public TaskWindowSnapshot inspectTaskWindow(
            final int displayId,
            final int taskId) {
        return mRunning && mTaskWatcherReady && mDisplayId == displayId
                ? mTaskWatcher.inspectTaskWindow(displayId, taskId)
                : null;
    }

    private void sendSystemBackInternal() {
        final int displayId = mDisplayId;
        if (!mRunning || displayId < 0) {
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

    private void handleActiveTaskShortcutInternal(
            final int shortcut,
            final int activeTaskId) {
        handleNativeTaskShortcut(shortcut, activeTaskId);
    }

    private void handleNativeTaskShortcut(
            final int shortcut,
            final int activeTaskId) {
        final int displayId = mDisplayId;
        final int generation = mGeneration;
        final boolean supportsFullscreenTask =
                DesktopWindowTransitionController
                        .supportsFullscreenTask(shortcut);
        final List<TaskRepository.TaskEntry> latestTasks = mLatestTasks;
        final TaskRepository.TaskEntry latestActiveTask =
                findKnownShortcutTask(
                        latestTasks,
                        activeTaskId,
                        !supportsFullscreenTask);
        if (latestActiveTask != null) {
            applyNativeTaskShortcut(shortcut, latestActiveTask);
            return;
        }
        TaskRepository.load(displayId, snapshot -> mHandler.post(() -> {
            if (!mRunning || generation != mGeneration || mDisplayId != displayId) {
                return;
            }
            final TaskRepository.TaskEntry task = selectShortcutTask(
                    snapshot.tasks,
                    activeTaskId,
                    !supportsFullscreenTask);
            if (task == null) {
                if (shortcut == SHORTCUT_RESTORE) {
                    mWindowTransitions.restoreTopFullscreenTask();
                } else {
                    Log.w(TAG, "no active task for shortcut=" + shortcut
                            + " display=" + displayId);
                }
                return;
            }
            applyNativeTaskShortcut(shortcut, task);
        }));
    }

    private void applyNativeTaskShortcut(
            final int shortcut,
            final TaskRepository.TaskEntry task) {
        mWindowTransitions.applyShortcut(task, shortcut);
    }

    private static TaskRepository.TaskEntry findTopVisibleAppTask(
            final List<TaskRepository.TaskEntry> tasks) {
        return selectTopVisibleTask(tasks, false);
    }

    static TaskRepository.TaskEntry selectKnownOrTopVisibleTask(
            final List<TaskRepository.TaskEntry> tasks,
            final int knownTaskId) {
        final TaskRepository.TaskEntry known = findTask(tasks, knownTaskId);
        if (known != null && known.visible && isFocusableTask(known)) {
            return known;
        }
        return findTopVisibleAppTask(tasks);
    }

    private static TaskRepository.TaskEntry findTopVisibleFreeformTask(
            final List<TaskRepository.TaskEntry> tasks) {
        return selectTopVisibleTask(tasks, true);
    }

    static TaskRepository.TaskEntry selectShortcutTask(
            final List<TaskRepository.TaskEntry> tasks,
            final int activeTaskId,
            final boolean requireBoundedFreeform) {
        final TaskRepository.TaskEntry known = findKnownShortcutTask(
                tasks, activeTaskId, requireBoundedFreeform);
        if (known != null) {
            return known;
        }
        return selectTopVisibleTask(tasks, requireBoundedFreeform);
    }

    static TaskRepository.TaskEntry findKnownShortcutTask(
            final List<TaskRepository.TaskEntry> tasks,
            final int activeTaskId,
            final boolean requireBoundedFreeform) {
        if (tasks == null || activeTaskId < 0) {
            return null;
        }
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task != null
                    && task.taskId == activeTaskId
                    && task.visible
                    && (!requireBoundedFreeform
                            || task.isBoundedFreeform())
                    && isFocusableTask(task)) {
                return task;
            }
        }
        return null;
    }

    static TaskRepository.TaskEntry selectTopVisibleTask(
            final List<TaskRepository.TaskEntry> tasks,
            final boolean requireBoundedFreeform) {
        if (tasks == null) {
            return null;
        }
        TaskRepository.TaskEntry visibleFallback = null;
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task != null
                    && task.visible
                    && (!requireBoundedFreeform
                            || task.isBoundedFreeform())
                    && isFocusableTask(task)) {
                if (task.active) {
                    return task;
                }
                if (visibleFallback == null) {
                    // Some firmware leaves the top task visible and focused
                    // while reporting active=false. The snapshot is top-first.
                    visibleFallback = task;
                }
            }
        }
        return visibleFallback;
    }

    private static boolean isFocusableTask(final TaskRepository.TaskEntry task) {
        return task != null && task.taskId >= 0
                && DesktopManagedTaskPolicy
                        .isControllableApplicationTask(task);
    }

    private static void completeActionCallback(final TaskRepository.ActionCallback callback,
            final boolean success, final String message) {
        if (callback != null) {
            callback.onComplete(new TaskRepository.ActionResult(success, message));
        }
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

    private void notifyTaskStackChanged() {
        if (mTaskStackChanged != null) {
            mTaskStackChanged.run();
        }
    }

    private void configureTaskWatcher() {
        if (mWindowContext == null) {
            return;
        }
        final Rect displayBounds = mNativeWindowBounds.getFullscreenBounds();
        final Rect workAreaBounds =
                mNativeWindowBounds.getTaskbarMaximizedBounds();
        final DesktopTaskAreaPolicy taskAreaPolicy = taskAreaPolicy();
        final DesktopSessionSnapshot session =
                DesktopRuntimeBridge.getSessionSnapshot();
        final int desktopHostTaskId = session.activeDisplayId() == mDisplayId
                ? session.hostTaskId() : -1;
        mTaskWatcher.configure(
                mDisplayId,
                displayBounds,
                workAreaBounds,
                taskAreaPolicy.wireValue(),
                desktopHostTaskId);
        mTaskWatcher.setExternalTaskMigrationProtection(
                shouldProtectExternalSession());
    }

    private boolean shouldProtectExternalSession() {
        final DesktopDisplayTarget target =
                DesktopRuntimeBridge.getDesktopTarget(mDisplayId);
        return target != null
                && (target.kind == DesktopDisplayTarget.Kind.WIRED
                        || target.kind == DesktopDisplayTarget.Kind.WIRELESS)
                && DesktopDisplayDrivers.forTarget(target)
                        .features().taskAreaPolicy
                        .usesDirectRootWorkspace()
                && mWindowing.protectsExternalSessionFromPhoneTaskMigration();
    }

    private DesktopTaskAreaPolicy taskAreaPolicy() {
        if (mDisplayId < 0) {
            return DesktopTaskAreaPolicy.UNCONFIGURED;
        }
        return DesktopDisplayDrivers.activeTaskAreaPolicy(mDisplayId);
    }

    @Override
    public int launchWindowedTask(
            final int displayId,
            final Intent intent,
            final Rect bounds) throws IOException {
        requireTaskObserver(displayId);
        return mTaskWatcher.launchWindowedTask(
                displayId, intent, bounds);
    }

    @Override
    public int launchFullscreenTaskInManagedSession(
            final int displayId,
            final Intent intent) throws IOException {
        requireSessionTaskArea(displayId);
        return mTaskWatcher.launchFullscreenTaskInManagedSession(
                displayId, intent);
    }

    @Override
    public int launchFullscreenTask(
            final int displayId,
            final Intent intent) throws IOException {
        requireTaskObserver(displayId);
        return mTaskWatcher.launchFullscreenTask(displayId, intent);
    }

    @Override
    public int launchAppShortcut(
            final int displayId,
            final String packageName,
            final String shortcutId,
            final UserHandle user,
            final int windowingMode,
            final Rect bounds,
            final int existingTaskId) throws IOException {
        return mTaskWatcher.launchAppShortcut(
                displayId,
                packageName,
                shortcutId,
                user,
                windowingMode,
                bounds,
                existingTaskId);
    }

    @Override
    public void noteTaskLaunchFocus(
            final int displayId, final int taskId) {
        if (displayId < 0 || taskId < 0) {
            return;
        }
        mHandler.post(() -> {
            if (!mRunning || mDisplayId != displayId) {
                return;
            }
            mFocusingTaskId = taskId;
            mActiveTaskId = taskId;
            recordFocusEvent(
                    "launch_focus_expected",
                    displayId,
                    taskId,
                    true,
                    "window launch completed");
            scheduleRefresh(0);
        });
    }

    @Override
    public void launchTaskAction(
            final int displayId,
            final int taskId,
            final Intent intent) throws IOException {
        requireTaskObserver(displayId);
        mTaskWatcher.launchTaskAction(displayId, taskId, intent);
    }

    @Override
    public void placeWindowedTaskInManagedSession(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final Rect bounds) throws IOException {
        requireSessionTaskArea(targetDisplayId);
        mTaskWatcher.placeWindowedTaskInManagedSession(
                taskId, sourceDisplayId, targetDisplayId, bounds);
    }

    @Override
    public void placeFullscreenTaskInManagedSession(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId) throws IOException {
        requireSessionTaskArea(targetDisplayId);
        mTaskWatcher.placeFullscreenTaskInManagedSession(
                taskId, sourceDisplayId, targetDisplayId);
    }

    private void requireSessionTaskArea(final int displayId)
            throws IOException {
        requireTaskObserver(displayId);
        if (!taskAreaPolicy().usesManagedApplicationArea()) {
            throw new IOException(
                    "session task area is unavailable for display "
                            + displayId);
        }
    }

    private void requireTaskObserver(final int displayId)
            throws IOException {
        if (!mRunning || !mTaskWatcherReady || displayId != mDisplayId) {
            throw new IOException(
                    "task observer is unavailable for display " + displayId);
        }
    }

    private void applySnapshot(final TaskRepository.Snapshot snapshot) {
        if (!snapshot.available) {
            Log.w(TAG, "task snapshot unavailable: " + snapshot.error);
            return;
        }
        mLatestTasks = Collections.unmodifiableList(
                new ArrayList<>(snapshot.tasks));
        reconcileTaskbarConcealment(snapshot.tasks);
        if (mSnapshotListener != null) {
            mSnapshotListener.onSnapshot(
                    mDisplayId,
                    snapshot.tasks,
                    DesktopRuntimeBridge.getDesktopWorkAreaBounds(mDisplayId),
                    taskAreaPolicy().usesManagedApplicationArea(),
                    mSessionOwnershipReady,
                    mSessionOwnedTaskIds);
        }
        mAutomationEvents.observe(snapshot);
        mNativeWindowBounds.reconcile(snapshot.tasks);
        DesktopRuntimeBridge.syncTaskbarWithSnapshot(mDisplayId, snapshot);
        final List<TaskRepository.TaskEntry> visibleTasks = new ArrayList<>();
        boolean hasVisibleAppTask = false;
        boolean aboveDesktopHost = true;
        for (final TaskRepository.TaskEntry task : snapshot.tasks) {
            if (isDesktopHostTask(task)) {
                aboveDesktopHost = false;
                continue;
            }
            if (aboveDesktopHost && isVisibleFreeformTask(task)) {
                visibleTasks.add(task);
            }
            if (aboveDesktopHost
                    && task != null && task.displayId == mDisplayId && task.visible
                    && DesktopManagedTaskPolicy
                            .isControllableApplicationTask(task)) {
                hasVisibleAppTask = true;
            }
        }
        final int focusingTaskId = mFocusingTaskId;
        if (mDisplayId != Display.DEFAULT_DISPLAY) {
            mPhoneTouchpadReconciler.reconcile(
                    mDisplayId,
                    snapshot.phoneTasks);
        }
        if (focusingTaskId >= 0) {
            final TaskRepository.TaskEntry focusingTask =
                    findTask(snapshot.tasks, focusingTaskId);
            if (focusingTask == null) {
                clearTrackedFocus(focusingTaskId);
            } else if (focusingTask.active) {
                confirmTrackedFocus(focusingTaskId);
            }
        } else {
            final TaskRepository.TaskEntry activeTask =
                    selectKnownOrTopVisibleTask(
                            snapshot.tasks, mActiveTaskId);
            mActiveTaskId = activeTask == null ? -1 : activeTask.taskId;
        }
        mDisplayTaskState.publish(visibleTasks, hasVisibleAppTask);
        mWindowTransitions.reconcile(
                snapshot.tasks,
                visibleTasks,
                focusingTaskId >= 0);
    }

    private void reconcileTaskbarConcealment(
            final List<TaskRepository.TaskEntry> tasks) {
        final Set<Integer> liveTaskIds = new HashSet<>();
        if (tasks != null) {
            for (final TaskRepository.TaskEntry task : tasks) {
                if (task == null || task.displayId != mDisplayId) {
                    continue;
                }
                liveTaskIds.add(Integer.valueOf(task.taskId));
            }
        }
        synchronized (mTaskbarConcealedTaskIds) {
            mTaskbarConcealedTaskIds.retainAll(liveTaskIds);
        }
    }

    private boolean isTaskbarTaskConcealed(final int taskId) {
        synchronized (mTaskbarConcealedTaskIds) {
            return mTaskbarConcealedTaskIds.contains(
                    Integer.valueOf(taskId));
        }
    }

    private boolean concealTaskbarTask(final int taskId) {
        synchronized (mTaskbarConcealedTaskIds) {
            return mTaskbarConcealedTaskIds.add(Integer.valueOf(taskId));
        }
    }

    private boolean restoreTaskbarTask(final int taskId) {
        synchronized (mTaskbarConcealedTaskIds) {
            return mTaskbarConcealedTaskIds.remove(Integer.valueOf(taskId));
        }
    }

    private void clearSessionOwnership() {
        mSessionOwnershipReady = false;
        mSessionOwnedTaskIds = Collections.emptySet();
    }

    private boolean isActiveOnDisplay(final int displayId) {
        return mRunning && mDisplayId == displayId;
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

    private boolean isVisibleFreeformTask(final TaskRepository.TaskEntry task) {
        return task != null
                && task.displayId == mDisplayId
                && task.visible
                && task.isBoundedFreeform()
                && DesktopManagedTaskPolicy.isControllableApplicationTask(
                        task);
    }

    static boolean isDesktopHostTask(final TaskRepository.TaskEntry task) {
        return task != null
                && MAGICDESK_PACKAGE.equals(task.packageName)
                && task.componentName != null
                && (task.componentName.endsWith("/.DesktopActivity")
                        || task.componentName.endsWith(
                                "/" + MAGICDESK_PACKAGE + ".DesktopActivity"));
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

}

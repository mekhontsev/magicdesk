package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.util.Log;
import android.view.Display;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class DesktopTaskController {
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
    private static WeakReference<DesktopTaskController> sActiveController =
            new WeakReference<>(null);

    private final Context mApplicationContext;
    private final Handler mHandler;
    private final Runnable mTaskStackChanged;
    private final DesktopTaskWatcher mTaskWatcher;
    private final DesktopPhoneUiReconciler mPhoneUiReconciler;
    private final NativeWindowBoundsController mNativeWindowBounds;
    private final DesktopWindowTransitionController mWindowTransitions;
    private final AppWindowStateTracker mAppWindowStates;
    private final Runnable mRefreshRunnable = this::runScheduledRefresh;

    private Context mWindowContext;
    private int mDisplayId = -1;
    private int mGeneration;
    private int mTaskWatcherGeneration;
    private volatile int mFocusingTaskId = -1;
    private long mRefreshDueUptimeMillis = -1;
    private boolean mRunning;
    private boolean mTaskWatcherRunning;
    private boolean mTaskWatcherReady;
    private boolean mRestoringLocalDesktop;

    DesktopTaskController(
            final Context context,
            final Handler handler,
            final Runnable taskStackChanged) {
        mApplicationContext = context.getApplicationContext();
        mHandler = handler;
        mTaskStackChanged = taskStackChanged;
        mPhoneUiReconciler = new DesktopPhoneUiReconciler(
                mApplicationContext);
        mAppWindowStates = new AppWindowStateTracker(handler);
        mNativeWindowBounds = new NativeWindowBoundsController(
                mApplicationContext,
                handler,
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
                mApplicationContext,
                mHandler,
                mNativeWindowBounds,
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
                    public void scheduleRefresh() {
                        DesktopTaskController.this.scheduleRefresh(0);
                    }
                });
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
                            final boolean initialSample) {
                        if (mRunning) {
                            mWindowTransitions.handleImmersiveRequest(
                                    taskId, requesting, initialSample);
                        }
                    }

                    @Override
                    public void onTaskGone(
                            final int generation,
                            final int taskId) {
                        if (mRunning) {
                            mWindowTransitions.forgetTaskState(taskId);
                        }
                    }

                    @Override
                    public void onNativeMaximizeChanged(
                            final int generation,
                            final int taskId,
                            final boolean enteredFullscreen) {
                        if (!mRunning) {
                            return;
                        }
                        Log.d(TAG,
                                (enteredFullscreen
                                        ? "native maximize"
                                        : "native maximize exit")
                                        + " task=" + taskId);
                        scheduleRefresh(0);
                    }

                    @Override
                    public void onFreeformBoundsChanged(
                            final int generation,
                            final int taskId,
                            final String packageName,
                            final int displayId,
                            final Rect bounds) {
                        if (!mRunning || displayId != mDisplayId) {
                            return;
                        }
                        mAppWindowStates.observe(
                                packageName,
                                displayId,
                                bounds,
                                mNativeWindowBounds
                                        .getTaskbarMaximizedBounds(),
                                mNativeWindowBounds.getFullscreenBounds());
                    }

                    @Override
                    public void onInputFocusRefreshRequired(
                            final int generation) {
                        if (mRunning) {
                            DesktopRuntimeBridge.refreshDesktopInputFocus(
                                    mDisplayId);
                        }
                    }

                    @Override
                    public void onDisconnected(final int generation) {
                        mTaskWatcherReady = false;
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
        stop();
        mDisplayId = displayId;
        mRunning = createWindowContext(displayId);
        if (!mRunning) {
            return;
        }
        mGeneration++;
        setActiveController(this);
        if (mTaskWatcherReady) {
            configureTaskWatcher();
        }
        scheduleRefresh(0);
        Log.i(TAG, "started on display=" + displayId);
    }

    void stop() {
        final int stoppedDisplayId = mDisplayId;
        mRunning = false;
        mGeneration++;
        mHandler.removeCallbacks(mRefreshRunnable);
        mRefreshDueUptimeMillis = -1;
        mTaskWatcher.clearConfiguration();
        mWindowContext = null;
        mDisplayId = -1;
        mFocusingTaskId = -1;
        mRestoringLocalDesktop = false;
        mNativeWindowBounds.reset();
        mAppWindowStates.stop();
        mPhoneUiReconciler.reset();
        clearActiveController(this);
        DesktopTaskStateStore.clear(stoppedDisplayId);
    }

    void destroy() {
        stop();
        setTaskWatcherEnabled(false);
        mTaskWatcher.destroy();
    }

    void setTaskWatcherEnabled(final boolean enabled) {
        if (enabled == mTaskWatcherRunning) {
            return;
        }
        mTaskWatcherRunning = enabled;
        mTaskWatcherReady = false;
        mTaskWatcherGeneration++;
        if (enabled) {
            startTaskWatcher(mTaskWatcherGeneration);
        } else {
            mTaskWatcher.stop();
        }
    }

    static synchronized List<TaskRepository.TaskEntry> getVisibleFreeformTasks(
            final int displayId) {
        return DesktopTaskStateStore.getVisibleTasks(displayId);
    }

    static synchronized List<TaskRepository.TaskEntry> getLastVisibleFreeformTasks(
            final int displayId) {
        return DesktopTaskStateStore.getLastVisibleTasks(displayId);
    }

    static synchronized Boolean hasVisibleAppTaskSnapshot(final int displayId) {
        return DesktopTaskStateStore.hasVisibleAppTask(displayId);
    }

    static synchronized void beginFullscreenTransition(final int displayId,
            final List<TaskRepository.TaskEntry> visibleTasks, final int excludedTaskId) {
        DesktopTaskStateStore.beginFullscreenTransition(
                displayId, visibleTasks, excludedTaskId);
    }

    static synchronized void finishFullscreenTransition(final int displayId,
            final boolean success) {
        DesktopTaskStateStore.finishFullscreenTransition(displayId, success);
        final DesktopTaskController controller = sActiveController.get();
        if (controller != null && controller.mRunning
                && controller.mDisplayId == displayId) {
            controller.scheduleRefresh(0);
        }
    }

    static synchronized void forgetVisibleFreeformTasks(final int displayId) {
        DesktopTaskStateStore.forgetVisibleTasks(displayId);
    }

    static void focusStack(final List<TaskRepository.TaskEntry> topFirstTasks,
            final TaskRepository.TaskEntry topTask,
            final TaskRepository.ActionCallback callback) {
        final DesktopTaskController controller = getActiveController();
        if (controller == null || !controller.mRunning
                || !controller.mTaskWatcherReady) {
            TaskRepository.bringStackToFront(
                    topFirstTasks, topTask, callback);
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
                controller.mDisplayId,
                new ArrayList<>(orderedTaskIds),
                trackedCallback);
    }

    static void focusDesktopTask(
            final int displayId,
            final int taskId,
            final TaskRepository.ActionCallback callback) {
        final DesktopTaskController controller = getActiveController();
        if (controller == null || !controller.mRunning
                || !controller.mTaskWatcherReady
                || controller.mDisplayId != displayId) {
            TaskRepository.bringTaskToFront(displayId, taskId, callback);
            return;
        }
        controller.mTaskWatcher.sendFocusStack(
                displayId,
                Collections.singletonList(Integer.valueOf(taskId)),
                callback);
    }

    static boolean handleActiveTaskShortcut(final int shortcut) {
        final DesktopTaskController controller = getActiveController();
        if (controller == null || !controller.mRunning) {
            return false;
        }
        controller.mHandler.post(() -> controller.handleActiveTaskShortcutInternal(shortcut));
        return true;
    }

    static void noteManualFreeformTransition(final int taskId) {
        final DesktopTaskController controller = getActiveController();
        if (controller != null && controller.mRunning) {
            controller.mWindowTransitions.noteManualFreeformTransition(taskId);
        }
    }

    static void beginExplicitWindowedLaunch(final int taskId) {
        final DesktopTaskController controller = getActiveController();
        if (controller != null && controller.mRunning) {
            controller.mWindowTransitions.beginExplicitWindowedLaunch(taskId);
        }
    }

    static void finishExplicitWindowedLaunch(final int taskId) {
        final DesktopTaskController controller = getActiveController();
        if (controller != null && controller.mRunning) {
            controller.mWindowTransitions.finishExplicitWindowedLaunch(taskId);
        }
    }

    static void expectTouchpadDisplacement() {
        final DesktopTaskController controller = getActiveController();
        if (controller != null && controller.mRunning) {
            controller.mPhoneUiReconciler.expectTouchpadDisplacement();
            controller.mTaskWatcher.setPhoneTouchpadPreservation(true);
        }
    }

    static void finishTouchpadPreservation() {
        final DesktopTaskController controller = getActiveController();
        if (controller != null && controller.mRunning) {
            controller.mTaskWatcher.setPhoneTouchpadPreservation(false);
            controller.mPhoneUiReconciler.finishTouchpadPreservation();
        }
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
            final boolean supportsFullscreenTask =
                    DesktopWindowTransitionController
                            .supportsFullscreenTask(shortcut);
            final TaskRepository.TaskEntry task = supportsFullscreenTask
                    ? findTopVisibleAppTask(snapshot.tasks)
                    : findTopVisibleFreeformTask(snapshot.tasks);
            if (task == null) {
                if (shortcut == SHORTCUT_RESTORE) {
                    mWindowTransitions.restoreTopFullscreenTask();
                } else {
                    Log.w(TAG, "no active task for shortcut=" + shortcut
                            + " display=" + displayId);
                }
                return;
            }
            final TaskRepository.TaskEntry minimizeFocusTask =
                    shortcut == SHORTCUT_RESTORE
                            ? findFocusAfterMinimize(snapshot.tasks, task.taskId)
                            : null;
            mWindowTransitions.applyShortcut(
                    task, shortcut, minimizeFocusTask);
        }));
    }

    private static TaskRepository.TaskEntry findFocusAfterMinimize(
            final List<TaskRepository.TaskEntry> tasks,
            final int minimizedTaskId) {
        if (tasks == null) {
            return null;
        }
        TaskRepository.TaskEntry desktopHost = null;
        for (final TaskRepository.TaskEntry task : tasks) {
            if (isDesktopHostTask(task)) {
                desktopHost = task;
                break;
            }
            if (task != null
                    && task.taskId != minimizedTaskId
                    && task.visible
                    && task.isFreeform()
                    && !task.home
                    && !MAGICDESK_PACKAGE.equals(task.packageName)) {
                return task;
            }
        }
        return desktopHost;
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

    private static synchronized DesktopTaskController getActiveController() {
        return sActiveController.get();
    }

    private static synchronized void setActiveController(
            final DesktopTaskController controller) {
        sActiveController = new WeakReference<>(controller);
    }

    private static synchronized void clearActiveController(
            final DesktopTaskController controller) {
        if (sActiveController.get() == controller) {
            sActiveController.clear();
        }
    }

    private static boolean isFocusableTask(final TaskRepository.TaskEntry task) {
        return task != null && task.taskId >= 0 && !task.home
                && !MAGICDESK_PACKAGE.equals(task.packageName);
    }

    private static void completeFocusCallback(final TaskRepository.ActionCallback callback,
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
        mTaskWatcher.configure(
                mDisplayId, displayBounds, workAreaBounds);
    }

    private void applySnapshot(final TaskRepository.Snapshot snapshot) {
        if (!snapshot.available) {
            Log.w(TAG, "task snapshot unavailable: " + snapshot.error);
            return;
        }
        final boolean shouldRestoreLocalDesktop =
                PlatformDrivers.current().phoneUi()
                        .shouldRestoreLocalDesktopHost(
                        mDisplayId,
                        snapshot.tasks,
                        MAGICDESK_PACKAGE);
        if (shouldRestoreLocalDesktop) {
            if (!mRestoringLocalDesktop) {
                final TaskRepository.TaskEntry desktopHost =
                        findDesktopHostTask(snapshot.tasks);
                if (desktopHost != null) {
                    restoreLocalDesktop(desktopHost);
                }
            }
            if (mRestoringLocalDesktop) {
                return;
            }
        }
        mNativeWindowBounds.reconcile(
                snapshot.tasks,
                mWindowTransitions.fullscreenTransitionTasks(),
                mWindowTransitions.fullscreenRestoreBounds());
        DesktopRuntimeBridge.syncTaskbarWithSnapshot(mDisplayId, snapshot);
        final List<TaskRepository.TaskEntry> visibleTasks = new ArrayList<>();
        final Set<Integer> visibleAppTaskIds = new HashSet<>();
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
                    && !task.home && !MAGICDESK_PACKAGE.equals(task.packageName)) {
                hasVisibleAppTask = true;
                visibleAppTaskIds.add(Integer.valueOf(task.taskId));
            }
        }
        final int focusingTaskId = mFocusingTaskId;
        if (mDisplayId != Display.DEFAULT_DISPLAY) {
            mPhoneUiReconciler.reconcile(
                    mDisplayId,
                    snapshot.phoneTasks,
                    visibleAppTaskIds,
                    focusingTaskId >= 0);
        }
        if (focusingTaskId >= 0) {
            final TaskRepository.TaskEntry focusingTask =
                    findTask(snapshot.tasks, focusingTaskId);
            if (focusingTask == null || focusingTask.active) {
                mFocusingTaskId = -1;
            }
        }
        DesktopTaskStateStore.publish(
                mDisplayId, visibleTasks, hasVisibleAppTask);
        mWindowTransitions.reconcile(snapshot.tasks, visibleTasks);
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

    private static TaskRepository.TaskEntry findDesktopHostTask(
            final List<TaskRepository.TaskEntry> tasks) {
        if (tasks == null) {
            return null;
        }
        for (final TaskRepository.TaskEntry task : tasks) {
            if (isDesktopHostTask(task)) {
                return task;
            }
        }
        return null;
    }

    private void restoreLocalDesktop(
            final TaskRepository.TaskEntry desktopHost) {
        final int generation = mGeneration;
        mRestoringLocalDesktop = true;
        Log.i(TAG, "restoring local desktop after system Home became active");
        TaskRepository.configureDesktopHost(desktopHost, result ->
                mHandler.post(() -> {
                    if (!mRunning || generation != mGeneration) {
                        return;
                    }
                    mRestoringLocalDesktop = false;
                    if (!result.success) {
                        Log.w(TAG, "local desktop restore failed: "
                                + result.message);
                    }
                    scheduleRefresh(0);
                }));
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

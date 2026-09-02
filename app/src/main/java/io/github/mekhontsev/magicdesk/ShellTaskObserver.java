package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.view.Display;

import java.io.Closeable;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
final class ShellTaskObserver extends TaskStackListener implements Closeable {
    private static final String TAG = "MagicDeskTasks";
    private static final int ACTIVITY_TYPE_STANDARD = 1;
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final ComponentName PHONE_TOUCHPAD_ACTIVITY =
            new ComponentName(
                    "io.github.mekhontsev.magicdesk",
                    "io.github.mekhontsev.magicdesk.MagicDeskTouchpadActivity");
    private final Object mService;
    private final ITaskObserverCallback mCallback;
    private final Runnable mCallbackFailure;
    private final PlatformWindowingDriver mWindowing;
    private final AtomicBoolean mCallbackFailed = new AtomicBoolean();
    private final ShellFreeformTaskCleanup mFreeformCleanup;
    private final ShellDesktopFocusController mFocusController;
    private final FrameworkInputWindowObservationSource
            mInputWindowObservations;
    private final ShellSystemDialogTracker mSystemDialogTracker;
    private final ShellDesktopWorkspaceCoordinator mWorkspaceCoordinator;
    private final ShellExternalTaskMigrationGuard mMigrationGuard;
    private final ShellProcessFailureTracker mProcessFailureTracker;
    private final ShellTaskActivityModeGuard mTaskActivityModeGuard;
    private final ShellPhoneOverviewRouter mPhoneOverviewRouter;
    private final ShellSecondaryHomeStartPolicy mSecondaryHomeStartPolicy =
            new ShellSecondaryHomeStartPolicy();
    private final ShellActivityStartController mActivityStartController;
    private final FrameworkTaskObservationSource mTaskObservations;
    private final ShellDesktopTaskOwnership mDesktopOwnership =
            new ShellDesktopTaskOwnership();
    private final ShellTaskLauncher mTaskLauncher;
    private final ShellFullscreenTaskArea mFullscreenTaskArea =
            new ShellFullscreenTaskArea(mDesktopOwnership);
    private final ShellDesktopTaskArea mDesktopTaskArea;
    private final ShellDesktopTaskbarPlane mDesktopTaskbarPlane;
    private final ShellSelfTestTaskStackGuard mSelfTestTaskStackGuard;

    private volatile boolean mClosed;
    private boolean mRegistered;
    private boolean mPreservePhoneTouchpad;
    private boolean mPhoneTouchpadRequested;
    private boolean mRestoringPhoneTouchpad;
    private int mPhoneTouchpadTaskId = -1;
    private Boolean mDesktopTaskAreaForeground;
    private DesktopTaskAreaPolicy mTaskAreaPolicy =
            DesktopTaskAreaPolicy.UNCONFIGURED;
    private volatile int mConfiguredDisplayId = Display.INVALID_DISPLAY;
    private int mReportedOwnershipDisplayId = Display.INVALID_DISPLAY;
    private int[] mReportedDesktopTaskIds = new int[0];
    private final Object mPostRemovalFocusLock = new Object();
    private int mPendingRemovedDesktopTaskId = -1;
    private int mPendingRemovalDisplayId = Display.INVALID_DISPLAY;

    ShellTaskObserver(
            final Context context,
            final ITaskObserverCallback callback,
            final IActivityLaunchCallback activityLauncher,
            final Runnable callbackFailure,
            final PlatformWindowingDriver windowing,
            final PlatformPhoneUiDriver phoneUi)
            throws ReflectiveOperationException {
        if (callback == null) {
            throw new IllegalArgumentException("missing task observer callback");
        }
        if (windowing == null) {
            throw new IllegalArgumentException("missing platform task policy");
        }
        if (phoneUi == null) {
            throw new IllegalArgumentException("missing platform phone UI policy");
        }
        mService = HiddenTaskApi.getService();
        mCallback = callback;
        mCallbackFailure = callbackFailure;
        mTaskActivityModeGuard = new ShellTaskActivityModeGuard(
                mService,
                new ShellTaskActivityModeGuard.Listener() {
                    @Override
                    public void onTaskCorrected(
                            final int taskId,
                            final String activityName,
                            final String restoredMode) {
                        callCallback(() ->
                                mCallback.onTaskActivityModeCorrected(
                                        taskId,
                                        activityName,
                                        restoredMode));
                    }

                    @Override
                    public void onError(final String error) {
                        callCallback(() -> mCallback.onObserverError(error));
                    }
                },
                windowing.requiresNativeFullscreenCaptionRefresh());
        mTaskLauncher = new ShellTaskLauncher(
                mService,
                context,
                context.getPackageManager(),
                mDesktopOwnership,
                mTaskActivityModeGuard,
                activityLauncher);
        mDesktopTaskArea = new ShellDesktopTaskArea(
                mService, mDesktopOwnership, mTaskLauncher);
        mDesktopTaskbarPlane = new ShellDesktopTaskbarPlane(mService);
        mSelfTestTaskStackGuard = new ShellSelfTestTaskStackGuard(mService);
        mWindowing = windowing;
        mSystemDialogTracker = new ShellSystemDialogTracker(
                ShellSystemDialogPolicy.create(context.getPackageManager()),
                (displayId, visible) -> callCallback(() ->
                        mCallback.onSystemDialogVisibilityChanged(
                                displayId, visible)));
        mInputWindowObservations =
                new FrameworkInputWindowObservationSource(
                        mSystemDialogTracker::onInputWindowsChanged);
        mFocusController = new ShellDesktopFocusController(
                mService,
                windowing.requiresDesktopInputFocusSynchronization(),
                mInputWindowObservations,
                taskId -> callCallback(() ->
                        mCallback.onInputFocusRefreshRequired(taskId)));
        mMigrationGuard = new ShellExternalTaskMigrationGuard(
                mService,
                windowing.requiresNativeFullscreenCaptionRefresh(),
                new ShellExternalTaskMigrationGuard.Listener() {
                    @Override
                    public void onError(final String error) {
                        callCallback(() ->
                                mCallback.onObserverError(error));
                    }

                    @Override
                    public void onPhoneTaskNormalized(final int taskId) {
                        callCallback(() ->
                                mCallback.onPhoneTaskNormalized(taskId));
                    }
                });
        mProcessFailureTracker = new ShellProcessFailureTracker(
                new ShellProcessFailureTracker.Listener() {
                    @Override
                    public void onDesktopProcessFailure(
                            final int type,
                            final String processName,
                            final int pid,
                            final int taskId,
                            final int displayId,
                            final int windowingMode,
                            final String topActivity,
                            final String reason) {
                        callCallback(() ->
                                mCallback.onDesktopProcessFailure(
                                        type,
                                        processName,
                                        pid,
                                        taskId,
                                        displayId,
                                        windowingMode,
                                        topActivity,
                                        reason));
                    }
                });
        mPhoneOverviewRouter = new ShellPhoneOverviewRouter(
                context,
                mService,
                phoneUi.requiresLauncherOwnedOverview(),
                activityLauncher,
                error -> callCallback(() -> mCallback.onObserverError(error)));
        mActivityStartController = new ShellActivityStartController(
                mService,
                error -> callCallback(() -> mCallback.onObserverError(error)),
                mProcessFailureTracker,
                null,
                mPhoneOverviewRouter,
                mSecondaryHomeStartPolicy,
                mMigrationGuard,
                mTaskActivityModeGuard);
        // The platform policy decides whether stale phone-side freeform
        // Recents entries require active cleanup.
        mFreeformCleanup = new ShellFreeformTaskCleanup(
                mService,
                error -> callCallback(() -> mCallback.onObserverError(error)));
        mTaskObservations = new FrameworkTaskObservationSource(
                context,
                mService,
                windowing.requiresNativeFullscreenCaptionRefresh(),
                new FrameworkTaskObservationSource.Listener() {
                    @Override
                    public void onTasksSampled(
                            final int displayId,
                            final java.util.List<?> tasks,
                            final java.util.List<
                                    FrameworkTaskSnapshot>
                                    taskSnapshots) {
                        mProcessFailureTracker.observeTasks(
                                displayId, taskSnapshots);
                        mTaskActivityModeGuard.observeTasks(
                                displayId, taskSnapshots);
                        mFocusController.onTasksSampled(taskSnapshots);
                        for (final Integer taskId
                                : mDesktopOwnership.observeTasks(
                                        displayId, tasks)) {
                            restoreUnexpectedPhoneFreeform(
                                    displayId, taskId.intValue());
                        }
                        reconcileFocusAfterTaskRemoval(
                                displayId, taskSnapshots);
                        reportDesktopTaskOwnership();
                        mDesktopTaskArea.removeOrphanedTransientTasks(
                                displayId, tasks);
                        mFreeformCleanup.observeTasks(displayId, tasks);
                    }

                    @Override
                    public void onTaskStackChanged() {
                        // TaskStackListener can miss focus/Z-order callbacks
                        // for organizer children. The framework observation
                        // source reports only actual sampled changes.
                        callCallback(mCallback::onTasksChanged);
                    }

                    @Override
                    public void onImmersiveRequest(
                            final int taskId,
                            final boolean requesting,
                            final boolean initialSample,
                            final boolean foreground) {
                        callCallback(() -> mCallback.onImmersiveRequest(
                                taskId,
                                requesting,
                                initialSample,
                                foreground));
                    }

                    @Override
                    public void onWindowingModeChanged(
                            final int displayId,
                            final int taskId,
                            final int previousMode,
                            final int currentMode,
                            final int previousCaptionSourceId,
                            final boolean focused) {
                        final boolean backgroundAppFullscreenReleased =
                                mFullscreenTaskArea.onWindowingModeChanged(
                                        displayId,
                                        taskId,
                                        currentMode,
                                        focused);
                        mSelfTestTaskStackGuard.sample("windowing-mode");
                        callCallback(() -> mCallback.onWindowingModeChanged(
                                taskId,
                                previousMode,
                                currentMode,
                                previousCaptionSourceId,
                                backgroundAppFullscreenReleased));
                    }

                    @Override
                    public void onFreeformBoundsChanged(
                            final int taskId,
                            final String stateKey,
                            final int displayId,
                            final Rect bounds) {
                        callCallback(() -> mCallback.onFreeformBoundsChanged(
                                taskId,
                                stateKey,
                                displayId,
                                bounds.left,
                                bounds.top,
                                bounds.right,
                                bounds.bottom));
                    }

                    @Override
                    public void onError(final String error) {
                        callCallback(() -> mCallback.onObserverError(error));
                    }
                });
        mWorkspaceCoordinator = new ShellDesktopWorkspaceCoordinator(
                mService,
                mFullscreenTaskArea,
                mDesktopOwnership,
                mFocusController,
                new ShellDesktopWorkspaceCoordinator.ForegroundReporter() {
                    @Override
                    public void reportForTask(final int taskId)
                            throws ReflectiveOperationException {
                        reportDesktopTaskAreaForeground(taskId);
                    }
                },
                mTaskObservations::requestSample);
    }

    void refreshTaskCaption(
            final int displayId,
            final int taskId,
            final int sourceId) throws ReflectiveOperationException {
        // The mode is already fullscreen. Only force the application client
        // to discard the caption source retained by Nubia. A removed task
        // already satisfies that end state, so the late callback is a no-op.
        if (!TaskCaptionInsetsRefresher.refreshTask(
                mService, displayId, taskId, sourceId)) {
            Log.d(TAG, "skipped caption refresh for removed task=" + taskId);
            return;
        }
        Log.d(TAG, "refreshed native fullscreen caption task=" + taskId);
    }

    void start() throws ReflectiveOperationException {
        try {
            HiddenTaskApi.registerTaskStackListener(mService, this);
            mRegistered = true;
            mInputWindowObservations.start();
            mTaskObservations.start();
        } catch (ReflectiveOperationException | RuntimeException error) {
            mActivityStartController.close();
            if (mRegistered) {
                mRegistered = false;
                try {
                    HiddenTaskApi.unregisterTaskStackListener(mService, this);
                } catch (ReflectiveOperationException
                        | RuntimeException cleanupError) {
                    Log.w(TAG, "failed to roll back task observer", cleanupError);
                }
            }
            throw error;
        }
    }

    void configure(
            final int displayId,
            final Rect displayBounds,
            final Rect workAreaBounds,
            final Rect taskbarBounds,
            final int taskAreaPolicyValue,
            final int desktopHostTaskId) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        if (displayId < 0) {
            // IActivityController can cancel starts system-wide. Keep task
            // observation alive between sessions, but never retain launch
            // interception after the desktop configuration is cleared.
            mPhoneOverviewRouter.stop();
            mActivityStartController.close();
            mConfiguredDisplayId = Display.INVALID_DISPLAY;
            clearPendingPostRemovalFocus();
            mTaskAreaPolicy = DesktopTaskAreaPolicy.UNCONFIGURED;
            mFocusController.configure(-1);
            mSystemDialogTracker.configure(
                    Display.INVALID_DISPLAY,
                    mInputWindowObservations.latestSnapshot());
            mSecondaryHomeStartPolicy.configure(Display.INVALID_DISPLAY);
            mMigrationGuard.configure(-1, false);
            mFreeformCleanup.configure(-1);
            mTaskActivityModeGuard.configure(Display.INVALID_DISPLAY);
            mProcessFailureTracker.configure(Display.INVALID_DISPLAY);
            mTaskObservations.clearConfiguration();
            mDesktopTaskbarPlane.close();
            // Release reusable fullscreen slots before the workspace task
            // area is torn down.
            mFullscreenTaskArea.configure(
                    Display.INVALID_DISPLAY,
                    DesktopTaskAreaPolicy.UNCONFIGURED);
            mDesktopTaskArea.configure(
                    Display.INVALID_DISPLAY,
                    DesktopTaskAreaPolicy.UNCONFIGURED,
                    -1);
            mDesktopOwnership.configure(Display.INVALID_DISPLAY);
            reportDesktopTaskOwnership();
            mDesktopTaskAreaForeground = null;
            return;
        }
        mProcessFailureTracker.configure(displayId);
        try {
            mActivityStartController.start();
            mPhoneOverviewRouter.start();
        } catch (ReflectiveOperationException | RuntimeException error) {
            mPhoneOverviewRouter.stop();
            mActivityStartController.close();
            mProcessFailureTracker.configure(Display.INVALID_DISPLAY);
            throw new IllegalStateException(
                    "cannot enable desktop activity-start observation: "
                            + usefulMessage(error),
                    error);
        }
        mDesktopOwnership.configure(displayId);
        if (desktopHostTaskId >= 0) {
            mDesktopOwnership.markDesktopHost(desktopHostTaskId);
        }
        final DesktopTaskAreaPolicy taskAreaPolicy =
                DesktopTaskAreaPolicy.fromWireValue(taskAreaPolicyValue);
        if (!mDesktopTaskArea.matchesConfiguration(
                displayId,
                taskAreaPolicy,
                desktopHostTaskId)) {
            // Release any previous fullscreen parent before changing the
            // workspace task-area configuration.
            mFullscreenTaskArea.configure(
                    Display.INVALID_DISPLAY,
                    DesktopTaskAreaPolicy.UNCONFIGURED);
            mDesktopTaskArea.configure(
                    Display.INVALID_DISPLAY,
                    DesktopTaskAreaPolicy.UNCONFIGURED,
                    -1);
        }
        mDesktopTaskArea.configure(
                displayId,
                taskAreaPolicy,
                desktopHostTaskId);
        mTaskAreaPolicy = taskAreaPolicy;
        mFullscreenTaskArea.configure(
                displayId,
                mTaskAreaPolicy);
        mDesktopTaskbarPlane.configure(displayId, taskbarBounds);
        if (!taskAreaPolicy.usesManagedHostArea()) {
            mDesktopTaskAreaForeground = null;
        }
        mConfiguredDisplayId = displayId;
        clearPendingPostRemovalFocus();
        mFocusController.configure(displayId);
        mSystemDialogTracker.configure(
                displayId, mInputWindowObservations.latestSnapshot());
        mSecondaryHomeStartPolicy.configure(displayId);
        mMigrationGuard.configure(displayId, false);
        // External tasks must remain outside phone-side Recents cleanup.
        mFreeformCleanup.configure(
                mWindowing.requiresStalePhoneFreeformTaskCleanup()
                        && displayId == Display.DEFAULT_DISPLAY
                                ? displayId : -1);
        mTaskActivityModeGuard.configure(displayId);
        mTaskObservations.configure(
                displayId,
                displayBounds,
                workAreaBounds);
        reportDesktopTaskOwnership();
    }

    boolean clearConfiguration(final int expectedDisplayId) {
        final int managedAreaDisplayId =
                mDesktopTaskArea.managedDisplayId();
        if (!DesktopTaskConfigurationGuard.canClear(
                expectedDisplayId,
                mConfiguredDisplayId,
                managedAreaDisplayId)) {
            Log.i(TAG, "ignored stale task observer clear expectedDisplay="
                    + expectedDisplayId
                    + " configuredDisplay=" + mConfiguredDisplayId
                    + " managedAreaDisplay=" + managedAreaDisplayId);
            return false;
        }
        configure(
                Display.INVALID_DISPLAY,
                new Rect(),
                new Rect(),
                new Rect(),
                DesktopTaskAreaPolicy.UNCONFIGURED.wireValue(),
                -1);
        return true;
    }

    void setExternalTaskMigrationProtection(final boolean enabled) {
        mMigrationGuard.configure(mConfiguredDisplayId, enabled);
    }

    void focusStack(
            final long sequence,
            final int displayId,
            final int[] taskIds) {
        ShellDesktopWorkspaceCoordinator.Result result;
        try {
            final int targetTaskId = taskIds == null || taskIds.length == 0
                    ? -1 : taskIds[taskIds.length - 1];
            result = executeWorkspaceCommand(DesktopWorkspaceCommand.create(
                    DesktopWorkspaceCommand.ACTIVATE,
                    displayId,
                    targetTaskId,
                    taskIds));
        } catch (RuntimeException error) {
            result = ShellDesktopWorkspaceCoordinator.Result.failure(
                    0, usefulMessage(error));
        }
        signalFocusStackResult(
                sequence, result.success, result.taskCount, result.error);
    }

    void executeWorkspaceCommand(
            final long sequence,
            final DesktopWorkspaceCommand command) {
        final ShellDesktopWorkspaceCoordinator.Result result =
                executeWorkspaceCommand(command);
        signalDesktopWorkspaceCommandResult(
                sequence, result.success, result.taskCount, result.error);
    }

    void notifyInputFocusRefreshComplete(final int taskId) {
        mFocusController.onInputFocusRefreshCompleted(taskId);
    }

    private ShellDesktopWorkspaceCoordinator.Result executeWorkspaceCommand(
            final DesktopWorkspaceCommand command) {
        if (mClosed) {
            return ShellDesktopWorkspaceCoordinator.Result.failure(
                    0, "task observer is closed");
        }
        if (command == null) {
            return ShellDesktopWorkspaceCoordinator.Result.failure(
                    0, "missing desktop workspace command");
        }
        if (command.displayId != mConfiguredDisplayId) {
            return ShellDesktopWorkspaceCoordinator.Result.failure(
                    0, "stale workspace display " + command.displayId
                            + "; configured=" + mConfiguredDisplayId);
        }
        final ShellDesktopWorkspaceCoordinator.Result result =
                mWorkspaceCoordinator.execute(command);
        if (result.success) {
            mDesktopTaskbarPlane.raise();
        }
        return result;
    }

    void updateDesktopTaskbarBounds(
            final int displayId,
            final Rect bounds) {
        if (displayId != mConfiguredDisplayId) {
            throw new IllegalStateException(
                    "stale taskbar display " + displayId
                            + "; configured=" + mConfiguredDisplayId);
        }
        mDesktopTaskbarPlane.updateBounds(displayId, bounds);
    }

    void configureDesktopTaskbarInput(
            final int displayId,
            final IBinder activityToken) {
        if (displayId != mConfiguredDisplayId) {
            throw new IllegalStateException(
                    "stale taskbar input display " + displayId
                            + "; configured=" + mConfiguredDisplayId);
        }
        mDesktopTaskbarPlane.configureActivityInput(activityToken);
    }

    TaskWindowSnapshot inspectTaskWindow(
            final int displayId,
            final int taskId) {
        try {
            final Object task = HiddenTaskApi.findTask(
                    mService, Display.INVALID_DISPLAY, taskId);
            if (task == null
                    || HiddenTaskApi.getTaskDisplayId(task) != displayId) {
                return null;
            }
            boolean visibilityKnown = true;
            boolean visible = false;
            try {
                visible = HiddenTaskApi.isTaskVisible(task);
            } catch (ReflectiveOperationException error) {
                visibilityKnown = false;
            }
            boolean focusKnown = true;
            boolean focused = false;
            try {
                focused = HiddenTaskApi.isTaskFocused(task);
            } catch (ReflectiveOperationException error) {
                focusKnown = false;
            }
            return new TaskWindowSnapshot(
                    taskId,
                    displayId,
                    HiddenTaskApi.getTaskWindowingMode(task),
                    visible,
                    visibilityKnown,
                    focused,
                    focusKnown);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(
                    "cannot inspect task window: " + usefulMessage(error),
                    error);
        }
    }

    int launchDesktopHost(
            final int displayId,
            final String intentUri,
            final int taskAreaPolicyValue) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        try {
            final int taskId = mDesktopTaskArea.launchHost(
                    displayId,
                    intentUri,
                    DesktopTaskAreaPolicy.fromWireValue(
                            taskAreaPolicyValue));
            reportDesktopTaskOwnership();
            reportDesktopTaskAreaForeground(true);
            return taskId;
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(
                    "cannot launch desktop host: " + usefulMessage(error),
                    error);
        }
    }

    boolean concealFullscreenTaskPlanes(final int displayId) {
        return displayId == mConfiguredDisplayId
                && mFullscreenTaskArea.concealForShowDesktop(displayId);
    }

    boolean restoreFullscreenTask(
            final int displayId,
            final int taskId,
            final Rect bounds) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        return mFullscreenTaskArea.restoreTask(
                mService, displayId, taskId, bounds);
    }

    boolean beginAppFullscreenTask(
            final int displayId,
            final int taskId,
            final Rect restoreBounds) {
        if (mClosed || displayId != mConfiguredDisplayId) {
            return false;
        }
        return mFullscreenTaskArea.beginAppFullscreen(
                mService, displayId, taskId, restoreBounds);
    }

    boolean beginFullscreenTask(
            final int displayId,
            final int taskId) {
        if (mClosed || displayId != mConfiguredDisplayId) {
            return false;
        }
        return mFullscreenTaskArea.beginFullscreen(
                mService,
                displayId,
                taskId,
                mWindowing.requiresNativeFullscreenCaptionRefresh());
    }

    boolean protectExplicitFullscreenTask(
            final int displayId,
            final int taskId) {
        if (mClosed || displayId != mConfiguredDisplayId || taskId < 0) {
            return false;
        }
        try {
            final Object task = HiddenTaskApi.requireTask(
                    mService, displayId, taskId);
            return mTaskActivityModeGuard.onExplicitFullscreenTaskIdentified(
                    taskId,
                    HiddenTaskApi.getTaskComponent(task),
                    displayId);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not protect explicit fullscreen task="
                    + taskId, error);
            return false;
        }
    }

    boolean closeDesktopTask(
            final int displayId,
            final int taskId,
            final int focusTaskId) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        return mWorkspaceCoordinator.closeTask(
                displayId, taskId, focusTaskId);
    }

    boolean removeDesktopPackageTasks(
            final int displayId,
            final String packageName,
            final int focusTaskId) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        return mDesktopTaskArea.removePackageTasks(
                displayId, packageName, focusTaskId);
    }

    int launchWindowedTask(
            final int displayId,
            final Intent intent,
            final Rect bounds) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        if (displayId != mConfiguredDisplayId) {
            throw new IllegalArgumentException(
                    "display is not configured: " + displayId);
        }
        try {
            final boolean managedApplicationArea =
                    mDesktopTaskArea.ownsApplicationArea(displayId);
            final int taskId = managedApplicationArea
                    ? mDesktopTaskArea.launchSessionWindowedTask(
                            displayId, intent, bounds)
                    : mTaskLauncher.launchWindowed(
                            displayId, intent, bounds, null);
            reportDesktopTaskOwnership();
            if (managedApplicationArea) {
                reportDesktopTaskAreaForeground(true);
            }
            return taskId;
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(
                    "cannot launch windowed task: "
                            + usefulMessage(error),
                    error);
        }
    }

    int launchFullscreenTaskInManagedSession(
            final int displayId,
            final Intent intent) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        if (displayId != mConfiguredDisplayId
                || !mDesktopTaskArea.ownsApplicationArea(displayId)) {
            throw new IllegalArgumentException(
                    "session task area is not configured: " + displayId);
        }
        try {
            final int taskId = mDesktopTaskArea.launchSessionFullscreenTask(
                    displayId, intent);
            reportDesktopTaskOwnership();
            reportDesktopTaskAreaForeground(true);
            return taskId;
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(
                    "cannot launch fullscreen task: "
                            + usefulMessage(error),
                    error);
        }
    }

    int launchFullscreenTask(
            final int displayId,
            final Intent intent) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        if (displayId != mConfiguredDisplayId) {
            throw new IllegalArgumentException(
                    "display is not configured: " + displayId);
        }
        try {
            final int taskId = mTaskLauncher.launchFullscreen(
                    displayId, intent);
            reportDesktopTaskOwnership();
            return taskId;
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(
                    "cannot launch fullscreen task: "
                            + usefulMessage(error),
                    error);
        }
    }

    int launchAppShortcut(
            final int displayId,
            final String packageName,
            final String shortcutId,
            final UserHandle user,
            final int windowingMode,
            final Rect bounds,
            final int existingTaskId) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        if (displayId != mConfiguredDisplayId) {
            throw new IllegalArgumentException(
                    "display is not configured: " + displayId);
        }
        try {
            final int taskId;
            if (existingTaskId >= 0) {
                mTaskLauncher.launchShortcutInTask(
                        displayId,
                        existingTaskId,
                        packageName,
                        shortcutId,
                        user);
                taskId = existingTaskId;
            } else if (windowingMode == FrameworkTaskSnapshot.WINDOWING_MODE_FREEFORM) {
                if (bounds == null || bounds.isEmpty()) {
                    throw new IllegalArgumentException(
                            "windowed shortcut requires bounds");
                }
                taskId = mDesktopTaskArea.ownsApplicationArea(displayId)
                        ? mDesktopTaskArea.launchSessionWindowedShortcut(
                                displayId,
                                packageName,
                                shortcutId,
                                user,
                                bounds)
                        : mTaskLauncher.launchShortcutWindowed(
                                displayId,
                                packageName,
                                shortcutId,
                                user,
                                bounds,
                                null,
                                true);
            } else if (windowingMode
                    == FrameworkTaskSnapshot.WINDOWING_MODE_FULLSCREEN) {
                taskId = mDesktopTaskArea.ownsApplicationArea(displayId)
                        ? mDesktopTaskArea.launchSessionFullscreenShortcut(
                                displayId, packageName, shortcutId, user)
                        : mTaskLauncher.launchShortcutFullscreen(
                                displayId,
                                packageName,
                                shortcutId,
                                user,
                                null);
            } else {
                throw new IllegalArgumentException(
                        "unsupported shortcut windowing mode: " + windowingMode);
            }
            reportDesktopTaskOwnership();
            if (mDesktopTaskArea.ownsApplicationArea(displayId)) {
                reportDesktopTaskAreaForeground(true);
            }
            return taskId;
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(
                    "cannot launch app shortcut: " + usefulMessage(error), error);
        }
    }

    void launchTaskAction(
            final int displayId,
            final int taskId,
            final Intent intent) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        if (displayId != mConfiguredDisplayId) {
            throw new IllegalArgumentException(
                    "display is not configured: " + displayId);
        }
        try {
            TaskDisplayAreaLaunchCommand.launchTaskAction(
                    mService, displayId, taskId, intent);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(
                    "cannot launch task action: " + usefulMessage(error),
                    error);
        }
    }

    void placeWindowedTaskInManagedSession(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final Rect bounds) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        try {
            mDesktopTaskArea.placeSessionWindowedTask(
                    taskId, sourceDisplayId, targetDisplayId, bounds);
            reportDesktopTaskOwnership();
            reportDesktopTaskAreaForeground(true);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(
                    "cannot place task in desktop area: "
                            + usefulMessage(error),
                    error);
        }
    }

    void placeFullscreenTaskInManagedSession(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        try {
            mDesktopTaskArea.placeSessionFullscreenTask(
                    taskId, sourceDisplayId, targetDisplayId);
            reportDesktopTaskOwnership();
            reportDesktopTaskAreaForeground(true);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(
                    "cannot place fullscreen task in desktop area: "
                            + usefulMessage(error),
                    error);
        }
    }

    void startSelfTestTaskStackGuard(
            final int displayId,
            final int hostTaskId,
            final String stage) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        mSelfTestTaskStackGuard.start(
                displayId,
                hostTaskId,
                stage);
    }

    void setSelfTestTaskStackGuardStage(final String stage) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        mSelfTestTaskStackGuard.stage(stage);
    }

    SelfTestTaskStackReport stopSelfTestTaskStackGuard() {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        return mSelfTestTaskStackGuard.stop();
    }

    synchronized void setPhoneTouchpadPreservation(
            final boolean enabled) {
        mPreservePhoneTouchpad = enabled;
        if (enabled && mPhoneTouchpadTaskId < 0) {
            mPhoneTouchpadTaskId = findPhoneTouchpadTaskId();
        }
    }

    synchronized void setPhoneTouchpadRequested(final boolean requested) {
        mPhoneTouchpadRequested = requested;
        if (requested && mPhoneTouchpadTaskId < 0) {
            mPhoneTouchpadTaskId = findPhoneTouchpadTaskId();
        }
    }

    @Override
    public void onTaskStackChanged() {
        mFullscreenTaskArea.onTaskStackChanged();
        mMigrationGuard.onTaskStackChanged();
        signalChange("stack-changed");
    }

    @Override
    public void onTaskCreated(
            final int taskId,
            final ComponentName componentName) {
        mTaskLauncher.onTaskCreated(taskId, componentName);
        if (PHONE_TOUCHPAD_ACTIVITY.equals(componentName)) {
            synchronized (this) {
                mPhoneTouchpadTaskId = taskId;
            }
        }
        signalChange("task-created");
    }

    @Override
    public void onTaskRemovalStarted(
            final ActivityManager.RunningTaskInfo taskInfo) {
        if (mClosed || taskInfo == null) {
            return;
        }
        mFullscreenTaskArea.onTaskRemovalStarted(
                mService, taskInfo.taskId);
        mSelfTestTaskStackGuard.sample("task-removal-started");
        signalChange("task-removal-started");
    }

    @Override
    public void onTaskRemoved(final int taskId) {
        if (!mClosed) {
            rememberDesktopTaskRemoval(taskId);
            synchronized (this) {
                if (mPhoneTouchpadTaskId == taskId) {
                    mPhoneTouchpadTaskId = -1;
                }
            }
            mMigrationGuard.forget(taskId);
            mTaskActivityModeGuard.onTaskRemoved(taskId);
            mDesktopTaskArea.onTaskRemoved(taskId);
            mFullscreenTaskArea.onTaskRemoved(taskId);
            mDesktopOwnership.forget(taskId);
            reportDesktopTaskOwnership();
            callCallback(() -> mCallback.onTaskGone(taskId));
            signalChange("task-removed");
        }
    }

    private void rememberDesktopTaskRemoval(final int taskId) {
        final int displayId = mConfiguredDisplayId;
        if (displayId == Display.INVALID_DISPLAY
                || !mDesktopOwnership.isRememberedDesktopTask(taskId)) {
            return;
        }
        synchronized (mPostRemovalFocusLock) {
            mPendingRemovedDesktopTaskId = taskId;
            mPendingRemovalDisplayId = displayId;
        }
    }

    private void reconcileFocusAfterTaskRemoval(
            final int displayId,
            final java.util.List<FrameworkTaskSnapshot> tasks) {
        final int removedTaskId;
        synchronized (mPostRemovalFocusLock) {
            if (mPendingRemovalDisplayId != displayId
                    || mPendingRemovedDesktopTaskId < 0) {
                return;
            }
            removedTaskId = mPendingRemovedDesktopTaskId;
        }

        boolean removedTaskStillPresent = false;
        FrameworkTaskSnapshot candidate = null;
        for (final FrameworkTaskSnapshot task : tasks) {
            if (task.taskId == removedTaskId) {
                removedTaskStillPresent = true;
                continue;
            }
            if (!isPostRemovalFocusCandidate(displayId, task)) {
                continue;
            }
            if (candidate == null || task.focused) {
                candidate = task;
            }
            if (task.focused) {
                break;
            }
        }
        if (candidate == null && removedTaskStillPresent) {
            return;
        }
        synchronized (mPostRemovalFocusLock) {
            if (mPendingRemovalDisplayId != displayId
                    || mPendingRemovedDesktopTaskId != removedTaskId) {
                return;
            }
            mPendingRemovedDesktopTaskId = -1;
            mPendingRemovalDisplayId = Display.INVALID_DISPLAY;
        }
        if (candidate == null) {
            return;
        }

        final int focusedTaskId = candidate.taskId;
        // Removing the focused task can expose a survivor without producing a
        // framework focus callback. Reuse the normal focus path once the typed
        // root hierarchy confirms which desktop task is now in front.
        mFocusController.requestFocusReconciliation(focusedTaskId);
        callCallback(() -> mCallback.onTaskFocusChanged(
                focusedTaskId, displayId, true));
        Log.i(TAG, "reconciled desktop focus after task removal removed="
                + removedTaskId + " survivor=" + focusedTaskId
                + " display=" + displayId);
    }

    private boolean isPostRemovalFocusCandidate(
            final int displayId,
            final FrameworkTaskSnapshot task) {
        if (task == null
                || task.displayId != displayId
                || !task.visible
                || task.activityType != ACTIVITY_TYPE_STANDARD
                || task.taskId == mDesktopOwnership.desktopHostTaskId()
                || DesktopTaskbarActivity.isTaskbarComponent(
                        task.rootComponent)
                || DesktopTaskbarActivity.isTaskbarComponent(
                        task.topComponent)
                || TaskAreaBackstopActivity.isBackstopComponent(
                        task.rootComponent)
                || TaskAreaBackstopActivity.isBackstopComponent(
                        task.topComponent)) {
            return false;
        }
        return task.task != null && mDesktopOwnership.isDesktopTask(task.task);
    }

    private void clearPendingPostRemovalFocus() {
        synchronized (mPostRemovalFocusLock) {
            mPendingRemovedDesktopTaskId = -1;
            mPendingRemovalDisplayId = Display.INVALID_DISPLAY;
        }
    }

    @Override
    public void onTaskMovedToFront(
            final ActivityManager.RunningTaskInfo taskInfo) {
        if (taskInfo != null) {
            final int displayId = HiddenTaskApi.getTaskDisplayId(taskInfo);
            mTaskLauncher.onTaskMovedToFront(
                    taskInfo.taskId,
                    HiddenTaskApi.getTaskTopComponent(taskInfo));
            mFullscreenTaskArea.onTaskMovedToFront(
                    displayId, taskInfo.taskId);
            mDesktopOwnership.observeTask(taskInfo);
            reportDesktopTaskOwnership();
            reportDesktopTaskAreaForeground(taskInfo);
            mMigrationGuard.onTaskMovedToFront(taskInfo);
            if (isPhoneTouchpadTask(taskInfo)) {
                synchronized (this) {
                    mPhoneTouchpadTaskId = taskInfo.taskId;
                }
            } else if (displayId == Display.DEFAULT_DISPLAY) {
                preservePhoneTouchpad();
            }
        }
        signalChange("task-front");
    }

    @Override
    public void onTaskMovedToBack(
            final ActivityManager.RunningTaskInfo taskInfo) {
        signalChange("task-back");
    }

    @Override
    public void onTaskDisplayChanged(
            final int taskId,
            final int newDisplayId) {
        mFullscreenTaskArea.onTaskDisplayChanged(taskId, newDisplayId);
        mDesktopTaskArea.onTaskDisplayChanged(taskId, newDisplayId);
        mTaskActivityModeGuard.onTaskDisplayChanged(taskId, newDisplayId);
        mMigrationGuard.onTaskDisplayChanged(taskId, newDisplayId);
        signalChange("display-changed");
    }

    @Override
    public void onTaskFocusChanged(
            final int taskId,
            final boolean focused) {
        if (focused && mFullscreenTaskArea.recoverAnchorFocus(
                mService, taskId)) {
            mSelfTestTaskStackGuard.sample("anchor-focus-recovered");
            signalChange("anchor-focus-recovered");
            return;
        }
        mFocusController.onTaskFocusChanged(taskId, focused);
        if (focused) {
            reportTaskFocus(taskId);
        }
        signalChange("focus-changed");
    }

    private void reportTaskFocus(final int taskId) {
        try {
            final Object task = HiddenTaskApi.findTask(
                    mService, Display.INVALID_DISPLAY, taskId);
            if (task == null) {
                return;
            }
            final int displayId = HiddenTaskApi.getTaskDisplayId(task);
            callCallback(() -> mCallback.onTaskFocusChanged(
                    taskId, displayId, true));
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not resolve focused task", error);
        }
    }

    @Override
    public void onActivityRequestedOrientationChanged(
            final int taskId,
            final int requestedOrientation) {
        reportRequestedOrientation(
                taskId, requestedOrientation, "activity-orientation");
    }

    @Override
    public void onTaskRequestedOrientationChanged(
            final int taskId,
            final int requestedOrientation) {
        reportRequestedOrientation(
                taskId, requestedOrientation, "task-orientation");
    }

    private void reportRequestedOrientation(
            final int taskId,
            final int requestedOrientation,
            final String reason) {
        callCallback(() -> mCallback.onTaskRequestedOrientationChanged(
                taskId, requestedOrientation));
        signalChange(reason);
    }

    @Override
    public void close() {
        if (mClosed) {
            return;
        }
        mClosed = true;
        final boolean registered = mRegistered;
        mRegistered = false;
        synchronized (this) {
            mPreservePhoneTouchpad = false;
            mPhoneTouchpadRequested = false;
        }
        closeSafely("focus controller", mFocusController::close);
        closeSafely("input-window observations",
                mInputWindowObservations::close);
        closeSafely("process failure tracker", () ->
                mProcessFailureTracker.configure(Display.INVALID_DISPLAY));
        mPhoneOverviewRouter.stop();
        closeSafely("activity start controller",
                mActivityStartController::close);
        closeSafely("phone Overview router", mPhoneOverviewRouter::close);
        closeSafely("migration guard", mMigrationGuard::close);
        closeSafely("freeform cleanup", mFreeformCleanup::close);
        closeSafely("framework task observations", mTaskObservations::close);
        closeSafely("desktop taskbar plane", mDesktopTaskbarPlane::close);
        closeSafely("fullscreen task area", mFullscreenTaskArea::close);
        closeSafely("desktop task area", mDesktopTaskArea::close);
        closeSafely("self-test task stack guard",
                mSelfTestTaskStackGuard::close);
        if (registered) {
            try {
                HiddenTaskApi.unregisterTaskStackListener(mService, this);
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.w(TAG, "failed to unregister task observer", error);
            }
        }
    }

    private static void closeSafely(
            final String component,
            final Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException error) {
            Log.w(TAG, "failed to close " + component, error);
        }
    }

    private void reportDesktopTaskAreaForeground(
            final ActivityManager.RunningTaskInfo taskInfo) {
        final Boolean foreground = mDesktopTaskArea
                .foregroundAfterTaskMovedToFront(taskInfo);
        if (foreground != null) {
            // Ownership spans both organizer siblings. Only actual membership
            // in the session area determines which desktop plane is on top.
            reportDesktopTaskAreaForeground(foreground.booleanValue());
        }
    }

    private void reportDesktopTaskAreaForeground(final int taskId)
            throws ReflectiveOperationException {
        final Object task = HiddenTaskApi.requireTask(
                mService, Display.INVALID_DISPLAY, taskId);
        final int displayId = HiddenTaskApi.getTaskDisplayId(task);
        final Boolean foreground = mDesktopTaskArea.foregroundForTask(
                displayId, taskId);
        if (foreground != null) {
            reportDesktopTaskAreaForeground(foreground.booleanValue());
        }
    }

    private void restoreUnexpectedPhoneFreeform(
            final int displayId,
            final int taskId) {
        if (displayId != Display.DEFAULT_DISPLAY) {
            return;
        }
        try {
            final Object task = HiddenTaskApi.findTask(
                    mService, Display.DEFAULT_DISPLAY, taskId);
            if (task != null
                    && TaskWindowingCommand.normalizeFullscreenTask(
                            mService,
                            Display.DEFAULT_DISPLAY,
                            task,
                            mWindowing.requiresNativeFullscreenCaptionRefresh())) {
                Log.i(TAG, "restored unexpected phone freeform task="
                        + taskId);
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not restore phone fullscreen task="
                    + taskId, error);
        }
    }

    private synchronized void reportDesktopTaskAreaForeground(
            final boolean foreground) {
        try {
            mDesktopTaskArea.setSessionForeground(foreground);
        } catch (ReflectiveOperationException | RuntimeException error) {
            final String message = usefulMessage(error);
            Log.w(TAG, "could not reorder desktop task area: "
                    + message, error);
            callCallback(() -> mCallback.onObserverError(
                    "desktop task area ordering unavailable: " + message));
            return;
        }
        publishDesktopTaskAreaForeground(foreground);
    }

    private synchronized void reportCommittedDesktopTaskAreaForeground(
            final boolean foreground) {
        mDesktopTaskArea.noteCommittedSessionForeground(foreground);
        publishDesktopTaskAreaForeground(foreground);
    }

    private void publishDesktopTaskAreaForeground(final boolean foreground) {
        if (mDesktopTaskAreaForeground != null
                && mDesktopTaskAreaForeground.booleanValue() == foreground) {
            return;
        }
        mDesktopTaskAreaForeground = Boolean.valueOf(foreground);
        callCallback(() -> mCallback
                .onDesktopTaskAreaForegroundChanged(foreground));
    }

    private synchronized void reportDesktopTaskOwnership() {
        final int displayId = mConfiguredDisplayId;
        final int[] taskIds = mDesktopOwnership.desktopTaskIds();
        if (displayId == mReportedOwnershipDisplayId
                && Arrays.equals(taskIds, mReportedDesktopTaskIds)) {
            return;
        }
        mReportedOwnershipDisplayId = displayId;
        mReportedDesktopTaskIds = taskIds;
        callCallback(() -> mCallback.onDesktopTaskOwnershipChanged(
                displayId, taskIds));
    }

    private void preservePhoneTouchpad() {
        final int taskId;
        synchronized (this) {
            if (mClosed
                    || !mPreservePhoneTouchpad
                    || mRestoringPhoneTouchpad
                    || mPhoneTouchpadTaskId < 0) {
                return;
            }
            mRestoringPhoneTouchpad = true;
            taskId = mPhoneTouchpadTaskId;
        }
        try {
            TaskWindowingCommand.focusFullscreenTask(
                    mService, Display.DEFAULT_DISPLAY, taskId);
            Log.i(TAG, "preserved phone touchpad inside task transition");
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not preserve phone touchpad", error);
        } finally {
            synchronized (this) {
                mRestoringPhoneTouchpad = false;
            }
        }
    }

    private int findPhoneTouchpadTaskId() {
        try {
            for (final Object task : HiddenTaskApi.getTasks(
                    mService, Display.DEFAULT_DISPLAY)) {
                if (task instanceof ActivityManager.RunningTaskInfo
                        && isPhoneTouchpadTask(
                                (ActivityManager.RunningTaskInfo) task)) {
                    return ((ActivityManager.RunningTaskInfo) task).taskId;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not find phone touchpad task", error);
        }
        return -1;
    }

    private static boolean isPhoneTouchpadTask(
            final ActivityManager.RunningTaskInfo taskInfo) {
        return PHONE_TOUCHPAD_ACTIVITY.equals(taskInfo.topActivity)
                || PHONE_TOUCHPAD_ACTIVITY.equals(taskInfo.baseActivity);
    }

    private void signalChange(final String reason) {
        if (!mClosed) {
            mSelfTestTaskStackGuard.sample(reason);
            mTaskObservations.requestSample();
            callCallback(mCallback::onTasksChanged);
        }
    }

    private void signalFocusStackResult(
            final long sequence,
            final boolean success,
            final int taskCount,
            final String error) {
        callCallback(() -> mCallback.onFocusStackResult(
                sequence, success, taskCount, error));
    }

    private void signalDesktopWorkspaceCommandResult(
            final long sequence,
            final boolean success,
            final int taskCount,
            final String error) {
        callCallback(() -> mCallback.onDesktopWorkspaceCommandResult(
                sequence, success, taskCount, error));
    }

    private void callCallback(final RemoteCallback callback) {
        if (mClosed || mCallbackFailed.get()) {
            return;
        }
        try {
            callback.call();
        } catch (RemoteException error) {
            if (mCallbackFailed.compareAndSet(false, true)) {
                Log.i(TAG, "task observer callback disconnected");
                if (mCallbackFailure != null) {
                    mCallbackFailure.run();
                }
            }
        }
    }

    private static String usefulMessage(final Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        final String message = cause.getMessage();
        return message == null || message.isEmpty()
                ? cause.getClass().getSimpleName() : message;
    }

    @FunctionalInterface
    private interface RemoteCallback {
        void call() throws RemoteException;
    }
}

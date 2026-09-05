package io.github.mekhontsev.magicdesk;

import android.app.PendingIntent;
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
    private final ShellPhoneDesktopWallpaperPolicy mPhoneWallpaperPolicy;
    private final ShellSecondaryHomeStartPolicy mSecondaryHomeStartPolicy =
            new ShellSecondaryHomeStartPolicy();
    private final ShellActivityStartController mActivityStartController;
    private final FrameworkTaskObservationSource mTaskObservations;
    private final ShellDesktopTaskOwnership mDesktopOwnership =
            new ShellDesktopTaskOwnership();
    private final ShellTaskLauncher mTaskLauncher;
    private final ShellFullscreenTaskArea mFullscreenTaskArea;
    private final ShellDesktopHostLauncher mDesktopHostLauncher;
    private final ShellDesktopChromeHost mDesktopChromeHost;
    private final ShellDesktopSurfaceOrder mSurfaceOrder =
            new ShellDesktopSurfaceOrder();
    private final ShellSelfTestTaskStackGuard mSelfTestTaskStackGuard;

    private volatile boolean mClosed;
    private boolean mRegistered;
    private boolean mPreservePhoneTouchpad;
    private boolean mPhoneTouchpadRequested;
    private boolean mRestoringPhoneTouchpad;
    private int mPhoneTouchpadTaskId = -1;
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
        mDesktopHostLauncher = new ShellDesktopHostLauncher(
                mService, mDesktopOwnership);
        mFullscreenTaskArea = new ShellFullscreenTaskArea(
                mDesktopOwnership, mSurfaceOrder);
        mDesktopChromeHost = new ShellDesktopChromeHost(mService, mSurfaceOrder);
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
        mPhoneWallpaperPolicy = new ShellPhoneDesktopWallpaperPolicy(
                mService,
                error -> callCallback(() -> mCallback.onObserverError(error)));
        mActivityStartController = new ShellActivityStartController(
                mService,
                error -> callCallback(() -> mCallback.onObserverError(error)),
                mProcessFailureTracker,
                null,
                mPhoneOverviewRouter,
                mPhoneWallpaperPolicy,
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
                        if (!mDesktopOwnership.isRememberedDesktopTask(
                                taskId)) {
                            return;
                        }
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
                        if (!mDesktopOwnership.isRememberedDesktopTask(
                                taskId)) {
                            return;
                        }
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
                        if (!mDesktopOwnership.isRememberedDesktopTask(
                                taskId)) {
                            return;
                        }
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
                mFocusController,
                mTaskObservations::requestSample,
                mSurfaceOrder);
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
            final int desktopHostTaskId) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        if (displayId < 0) {
            // IActivityController can cancel starts system-wide. Keep task
            // observation alive between sessions, but never retain launch
            // interception after the desktop configuration is cleared.
            mPhoneOverviewRouter.stop();
            mPhoneWallpaperPolicy.configure(Display.INVALID_DISPLAY);
            mActivityStartController.close();
            mConfiguredDisplayId = Display.INVALID_DISPLAY;
            clearPendingPostRemovalFocus();
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
            mDesktopChromeHost.close();
            mFullscreenTaskArea.configure(Display.INVALID_DISPLAY);
            mDesktopOwnership.configure(Display.INVALID_DISPLAY);
            reportDesktopTaskOwnership();
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
        mFullscreenTaskArea.configure(displayId);
        mDesktopChromeHost.configure(displayId);
        mConfiguredDisplayId = displayId;
        mPhoneWallpaperPolicy.configure(displayId);
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
        if (expectedDisplayId < 0
                || mConfiguredDisplayId != expectedDisplayId) {
            Log.i(TAG, "ignored stale task observer clear expectedDisplay="
                    + expectedDisplayId
                    + " configuredDisplay=" + mConfiguredDisplayId);
            return false;
        }
        resetDesktopTaskDensities();
        configure(
                Display.INVALID_DISPLAY,
                new Rect(),
                new Rect(),
                -1);
        return true;
    }

    void setExternalTaskMigrationProtection(final boolean enabled) {
        mMigrationGuard.configure(mConfiguredDisplayId, enabled);
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
        return mWorkspaceCoordinator.execute(command);
    }

    void configureDesktopActivityInput(
            final int displayId,
            final IBinder activityToken) {
        if (displayId != mConfiguredDisplayId) {
            throw new IllegalStateException(
                    "stale desktop activity display " + displayId
                            + "; configured=" + mConfiguredDisplayId);
        }
        if (activityToken == null) {
            throw new IllegalArgumentException("missing activity token");
        }
        FrameworkActivityInputApi.setRecordInputSinkEnabled(
                activityToken, false);
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
            final String intentUri) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        try {
            final int taskId = mDesktopHostLauncher.launch(
                    displayId, intentUri);
            reportDesktopTaskOwnership();
            return taskId;
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(
                    "cannot launch desktop host: " + usefulMessage(error),
                    error);
        }
    }

    int prepareDesktopChromeHost(final int displayId) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        if (displayId != mConfiguredDisplayId) {
            throw new IllegalStateException(
                    "stale panel display " + displayId
                            + "; configured=" + mConfiguredDisplayId);
        }
        try {
            return mDesktopChromeHost.prepare(displayId);
        } catch (RuntimeException error) {
            throw new IllegalStateException(
                    "cannot prepare desktop chrome host: "
                            + usefulMessage(error),
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
            final Rect bounds,
            final int densityDpi) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        return mSurfaceOrder.complete(mFullscreenTaskArea.restoreTask(
                mService, displayId, taskId, bounds, densityDpi));
    }

    boolean beginAppFullscreenTask(
            final int displayId,
            final int taskId,
            final Rect restoreBounds,
            final int densityDpi) {
        if (mClosed || displayId != mConfiguredDisplayId) {
            return false;
        }
        mDesktopOwnership.markDesktop(taskId);
        final boolean entered = mFullscreenTaskArea.beginAppFullscreen(
                mService,
                displayId,
                taskId,
                restoreBounds,
                densityDpi);
        reportDesktopTaskOwnership();
        return mSurfaceOrder.complete(entered);
    }

    boolean beginFullscreenTask(
            final int displayId,
            final int taskId,
            final int densityDpi) {
        if (mClosed || displayId != mConfiguredDisplayId) {
            return false;
        }
        mDesktopOwnership.markDesktop(taskId);
        final boolean entered = mFullscreenTaskArea.beginFullscreen(
                mService,
                displayId,
                taskId,
                mWindowing.requiresNativeFullscreenCaptionRefresh(),
                densityDpi);
        reportDesktopTaskOwnership();
        return mSurfaceOrder.complete(entered);
    }

    boolean beginWindowedTask(
            final int displayId,
            final int taskId,
            final Rect bounds,
            final int densityDpi) {
        if (mClosed || displayId != mConfiguredDisplayId
                || taskId < 0 || bounds == null || bounds.isEmpty()) {
            return false;
        }
        try {
            HiddenTaskApi.requireTask(mService, displayId, taskId);
            // Claim before submitting the transition. On display 0 the same
            // observer otherwise treats a previously fullscreen phone task as
            // an accidental WMShell migration and immediately restores it.
            mDesktopOwnership.markDesktop(taskId);
            ShellPreparedTaskTransition.applyFreeform(
                    mService,
                    displayId,
                    taskId,
                    new Rect(bounds),
                    densityDpi);
            reportDesktopTaskOwnership();
            return mSurfaceOrder.complete(true);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(
                    "cannot attach windowed task: "
                            + usefulMessage(error),
                    error);
        }
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
        final ShellFullscreenTaskArea.CloseResult result =
                mFullscreenTaskArea.closeTask(
                        mService, displayId, taskId, focusTaskId);
        return mSurfaceOrder.complete(
                result == ShellFullscreenTaskArea.CloseResult.SUCCEEDED);
    }

    int launchWindowedTask(
            final int displayId,
            final Intent intent,
            final Rect bounds,
            final int densityDpi) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        if (displayId != mConfiguredDisplayId) {
            throw new IllegalArgumentException(
                    "display is not configured: " + displayId);
        }
        try {
            final int taskId = mTaskLauncher.launchWindowed(
                    displayId, intent, bounds, null, true, densityDpi);
            return finishTaskLaunch(displayId, taskId);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(
                    "cannot launch windowed task: "
                            + usefulMessage(error),
                    error);
        }
    }

    int launchFullscreenTask(
            final int displayId,
            final Intent intent,
            final int densityDpi) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        if (displayId != mConfiguredDisplayId) {
            throw new IllegalArgumentException(
                    "display is not configured: " + displayId);
        }
        try {
            final int taskId = mFullscreenTaskArea.launchFullscreen(
                    mService,
                    displayId,
                    taskAreaToken -> mTaskLauncher.launchFullscreen(
                            displayId, intent, taskAreaToken),
                    densityDpi);
            return finishTaskLaunch(displayId, taskId);
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
            final int densityDpi,
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
                taskId = mTaskLauncher.launchShortcutWindowed(
                        displayId,
                        packageName,
                        shortcutId,
                        user,
                        bounds,
                        null,
                        true,
                        densityDpi);
            } else if (windowingMode
                    == FrameworkTaskSnapshot.WINDOWING_MODE_FULLSCREEN) {
                taskId = mFullscreenTaskArea.launchFullscreen(
                        mService,
                        displayId,
                        taskAreaToken -> mTaskLauncher
                                .launchShortcutFullscreen(
                                        displayId,
                                        packageName,
                                        shortcutId,
                                        user,
                                        taskAreaToken),
                        densityDpi);
            } else {
                throw new IllegalArgumentException(
                        "unsupported shortcut windowing mode: " + windowingMode);
            }
            return finishTaskLaunch(displayId, taskId);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(
                    "cannot launch app shortcut: " + usefulMessage(error), error);
        }
    }

    int launchPendingActivity(
            final int displayId,
            final String expectedPackage,
            final ComponentName expectedComponent,
            final PendingIntent pendingIntent,
            final int windowingMode,
            final Rect bounds,
            final int densityDpi,
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
                mTaskLauncher.launchPendingActivityInTask(
                        displayId,
                        existingTaskId,
                        expectedPackage,
                        expectedComponent,
                        pendingIntent);
                taskId = existingTaskId;
            } else if (windowingMode
                    == FrameworkTaskSnapshot.WINDOWING_MODE_FREEFORM) {
                if (bounds == null || bounds.isEmpty()) {
                    throw new IllegalArgumentException(
                            "windowed pending Activity requires bounds");
                }
                taskId = mTaskLauncher.launchPendingActivityWindowed(
                        displayId,
                        expectedPackage,
                        expectedComponent,
                        pendingIntent,
                        bounds,
                        null,
                        true,
                        densityDpi);
            } else if (windowingMode
                    == FrameworkTaskSnapshot.WINDOWING_MODE_FULLSCREEN) {
                taskId = mFullscreenTaskArea.launchFullscreen(
                        mService,
                        displayId,
                        taskAreaToken -> mTaskLauncher
                                .launchPendingActivityFullscreen(
                                        displayId,
                                        expectedPackage,
                                        expectedComponent,
                                        pendingIntent,
                                        taskAreaToken),
                        densityDpi);
            } else {
                throw new IllegalArgumentException(
                        "unsupported pending Activity windowing mode: "
                                + windowingMode);
            }
            return finishTaskLaunch(displayId, taskId);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(
                    "cannot launch pending Activity: "
                            + usefulMessage(error),
                    error);
        }
    }

    private int finishTaskLaunch(final int displayId, final int taskId) {
        reportDesktopTaskOwnership();
        return mSurfaceOrder.complete(taskId);
    }

    boolean setDesktopTaskDensity(
            final int displayId,
            final int[] taskIds,
            final int densityDpi) {
        if (mClosed || displayId != mConfiguredDisplayId
                || taskIds == null || taskIds.length == 0
                || densityDpi == DesktopTaskDensity.UNCHANGED) {
            return false;
        }
        try {
            final FrameworkWindowingApi windowing =
                    FrameworkRuntime.current().windowing();
            final Class<?> transactionClass = windowing.transactionClass();
            final Object transaction = windowing.newTransaction();
            int applied = 0;
            for (final int taskId : taskIds) {
                final Object task = HiddenTaskApi.requireTask(
                        mService, displayId, taskId);
                if (mDesktopOwnership.isDesktopHostTask(taskId)
                        || !mDesktopOwnership.isDesktopTask(task)) {
                    throw new IllegalArgumentException(
                            "task is outside the desktop workspace: "
                                    + taskId);
                }
                DesktopTaskDensity.apply(
                        windowing,
                        transaction,
                        HiddenTaskApi.getTaskToken(task),
                        densityDpi);
                mFullscreenTaskArea.addDensityOperation(
                        windowing, transaction, taskId, densityDpi);
                applied++;
            }
            if (applied == 0) {
                return false;
            }
            ShellWindowTransitionExecutor.applyAtomic(
                    mService, transactionClass, transaction);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(
                    "cannot apply desktop task density: "
                            + usefulMessage(error),
                    error);
        }
    }

    private void resetDesktopTaskDensities() {
        final FrameworkWindowingApi windowing =
                FrameworkRuntime.current().windowing();
        if (!windowing.supportsDensityOverride()) {
            return;
        }
        try {
            final Class<?> transactionClass = windowing.transactionClass();
            final Object transaction = windowing.newTransaction();
            int resetCount = 0;
            for (final int taskId : mDesktopOwnership.desktopTaskIds()) {
                if (mDesktopOwnership.isDesktopHostTask(taskId)) {
                    continue;
                }
                final Object task = HiddenTaskApi.findTask(
                        mService, Display.INVALID_DISPLAY, taskId);
                if (task == null) {
                    continue;
                }
                DesktopTaskDensity.apply(
                        windowing,
                        transaction,
                        HiddenTaskApi.getTaskToken(task),
                        DesktopTaskDensity.INHERIT);
                mFullscreenTaskArea.addDensityOperation(
                        windowing,
                        transaction,
                        taskId,
                        DesktopTaskDensity.INHERIT);
                resetCount++;
            }
            if (resetCount > 0) {
                ShellWindowTransitionExecutor.applyAtomic(
                        mService, transactionClass, transaction);
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not reset desktop task densities", error);
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
                || task.activityType != FrameworkTaskSnapshot.ACTIVITY_TYPE_STANDARD
                || task.taskId == mDesktopOwnership.desktopHostTaskId()
                || DesktopInfrastructureTasks.isComponent(
                        task.rootComponent)
                || DesktopInfrastructureTasks.isComponent(
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
            mDesktopOwnership.observeTask(taskInfo);
            reportDesktopTaskOwnership();
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
        resetDepartedTaskDensity(taskId, newDisplayId);
        mFullscreenTaskArea.onTaskDisplayChanged(taskId, newDisplayId);
        mTaskActivityModeGuard.onTaskDisplayChanged(taskId, newDisplayId);
        mMigrationGuard.onTaskDisplayChanged(taskId, newDisplayId);
        signalChange("display-changed");
    }

    private void resetDepartedTaskDensity(
            final int taskId,
            final int newDisplayId) {
        if (mConfiguredDisplayId < 0
                || newDisplayId == mConfiguredDisplayId
                || !mDesktopOwnership.isRememberedDesktopTask(taskId)
                || mDesktopOwnership.isDesktopHostTask(taskId)) {
            return;
        }
        // Task density belongs to its desktop-display residency. Clear it at
        // the framework move callback before the task resumes elsewhere.
        try {
            final FrameworkWindowingApi windowing =
                    FrameworkRuntime.current().windowing();
            if (!windowing.supportsDensityOverride()) {
                return;
            }
            final Object task = HiddenTaskApi.findTask(
                    mService, Display.INVALID_DISPLAY, taskId);
            if (task == null) {
                return;
            }
            final Class<?> transactionClass = windowing.transactionClass();
            final Object transaction = windowing.newTransaction();
            DesktopTaskDensity.apply(
                    windowing,
                    transaction,
                    HiddenTaskApi.getTaskToken(task),
                    DesktopTaskDensity.INHERIT);
            ShellWindowTransitionExecutor.applyAtomic(
                    mService, transactionClass, transaction);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not reset density for departed task="
                    + taskId, error);
        }
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
        final FocusedTask focusedTask = focused
                ? resolveFocusedTask(taskId) : null;
        if (focusedTask != null && focusedTask.infrastructure) {
            // Infrastructure windows can legitimately own input while the
            // user operates desktop chrome. They are not application focus
            // targets and must not enter the application focus-repair path.
            signalChange("infrastructure-focus-changed");
            return;
        }
        mFocusController.onTaskFocusChanged(taskId, focused);
        if (focusedTask != null) {
            callCallback(() -> mCallback.onTaskFocusChanged(
                    taskId, focusedTask.displayId, true));
        }
        signalChange("focus-changed");
    }

    private FocusedTask resolveFocusedTask(final int taskId) {
        try {
            final Object task = HiddenTaskApi.findTask(
                    mService, Display.INVALID_DISPLAY, taskId);
            if (task == null) {
                return null;
            }
            return new FocusedTask(
                    HiddenTaskApi.getTaskDisplayId(task),
                    DesktopInfrastructureTasks.isComponent(
                            HiddenTaskApi.getTaskComponent(task))
                            || DesktopInfrastructureTasks.isComponent(
                                    HiddenTaskApi.getTaskTopComponent(task)));
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not classify focused task=" + taskId, error);
            return null;
        }
    }

    private static final class FocusedTask {
        final int displayId;
        final boolean infrastructure;

        FocusedTask(final int displayId, final boolean infrastructure) {
            this.displayId = displayId;
            this.infrastructure = infrastructure;
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
        if (mDesktopOwnership.isRememberedDesktopTask(taskId)) {
            callCallback(() -> mCallback.onTaskRequestedOrientationChanged(
                    taskId, requestedOrientation));
        }
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
        closeSafely("desktop chrome host", mDesktopChromeHost::close);
        closeSafely("fullscreen task area", mFullscreenTaskArea::close);
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

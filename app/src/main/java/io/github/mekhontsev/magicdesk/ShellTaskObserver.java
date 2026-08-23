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
import android.util.Log;
import android.view.Display;

import java.io.Closeable;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
final class ShellTaskObserver extends TaskStackListener implements Closeable {
    private static final String TAG = "MagicDeskTasks";
    private static final ComponentName PHONE_TOUCHPAD_ACTIVITY =
            new ComponentName(
                    "io.github.mekhontsev.magicdesk",
                    "io.github.mekhontsev.magicdesk.MagicDeskTouchpadActivity");

    private final Object mService;
    private final ITaskObserverCallback mCallback;
    private final Runnable mCallbackFailure;
    private final IBinder mOwnerToken;
    private final PlatformWindowingDriver mWindowing;
    private final PlatformPhoneUiDriver mPhoneUi;
    private final PlatformPhoneUiDriver.NavigationGuard mNavigationGuard;
    private final AtomicBoolean mCallbackFailed = new AtomicBoolean();
    private final ShellFreeformTaskCleanup mFreeformCleanup;
    private final ShellDesktopFocusController mFocusController;
    private final ShellExternalTaskMigrationGuard mMigrationGuard;
    private final ShellProcessFailureTracker mProcessFailureTracker;
    private final PhoneHomeComponents mPhoneHome;
    private final ShellPhoneLauncherCircuitBreaker mPhoneLauncherCircuitBreaker;
    private final ShellTaskActivityModeGuard mTaskActivityModeGuard;
    private final ShellActivityStartController mActivityStartController;
    private final PlatformPhoneUiDriver.TaskEventGuard mInputPanelGuard;
    private final ShellTaskStateMonitor mStateMonitor;
    private final ShellDesktopTaskOwnership mDesktopOwnership =
            new ShellDesktopTaskOwnership();
    private final ShellTaskLauncher mTaskLauncher;
    private final ShellFullscreenTaskArea mFullscreenTaskArea =
            new ShellFullscreenTaskArea(mDesktopOwnership);
    private final ShellDesktopTaskArea mDesktopTaskArea;
    private final ShellSelfTestTaskStackGuard mSelfTestTaskStackGuard;

    private volatile boolean mClosed;
    private boolean mRegistered;
    private boolean mPreservePhoneTouchpad;
    private boolean mPhoneTouchpadRequested;
    private boolean mRestoringPhoneTouchpad;
    private boolean mExternalNavigationGuardActive;
    private int mPhoneTouchpadTaskId = -1;
    private Boolean mDesktopTaskAreaForeground;
    private volatile int mConfiguredDisplayId = Display.INVALID_DISPLAY;
    private int mReportedOwnershipDisplayId = Display.INVALID_DISPLAY;
    private int[] mReportedDesktopTaskIds = new int[0];

    ShellTaskObserver(
            final Context context,
            final ITaskObserverCallback callback,
            final Runnable callbackFailure,
            final IBinder ownerToken,
            final PlatformWindowingDriver windowing,
            final PlatformPhoneUiDriver phoneUi,
            final PlatformPhoneUiDriver.NavigationGuard navigationGuard,
            final PlatformPhoneUiDriver.InputOwner inputOwner)
            throws ReflectiveOperationException {
        if (callback == null) {
            throw new IllegalArgumentException("missing task observer callback");
        }
        if (windowing == null || phoneUi == null) {
            throw new IllegalArgumentException("missing platform task policy");
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
                context.getPackageManager(),
                mDesktopOwnership,
                mTaskActivityModeGuard);
        mDesktopTaskArea = new ShellDesktopTaskArea(
                mService, mDesktopOwnership, mTaskLauncher);
        mSelfTestTaskStackGuard = new ShellSelfTestTaskStackGuard(mService);
        mOwnerToken = ownerToken;
        mWindowing = windowing;
        mPhoneUi = phoneUi;
        mNavigationGuard = navigationGuard;
        mFocusController = new ShellDesktopFocusController(
                mService,
                windowing.requiresMirrorInputFocusSynchronization(),
                () -> callCallback(
                        mCallback::onInputFocusRefreshRequired));
        mInputPanelGuard = phoneUi.createInputPanelGuard(
                mService, inputOwner);
        mPhoneHome = PhoneHomeComponents.resolve(context);
        mPhoneLauncherCircuitBreaker =
                new ShellPhoneLauncherCircuitBreaker(
                        phoneUi.protectsPhoneLauncherAfterCrash());
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

                    @Override
                    public void onPhoneLauncherEvent(
                            final int type,
                            final String processName,
                            final int pid,
                            final String reason) {
                        final boolean protectionActivated =
                                mPhoneLauncherCircuitBreaker
                                        .noteLauncherFailure(type);
                        callCallback(() ->
                                mCallback.onPhoneLauncherEvent(
                                        type,
                                        processName,
                                        pid,
                                        reason,
                                        protectionActivated));
                    }
                },
                mPhoneHome);
        mActivityStartController = new ShellActivityStartController(
                mService,
                error -> callCallback(() -> mCallback.onObserverError(error)),
                mProcessFailureTracker,
                mProcessFailureTracker,
                this::allowPhoneUiStart,
                mMigrationGuard,
                mTaskActivityModeGuard);
        // The platform policy decides whether stale phone-side freeform
        // Recents entries require active cleanup.
        mFreeformCleanup = new ShellFreeformTaskCleanup(
                mService,
                error -> callCallback(() -> mCallback.onObserverError(error)));
        mStateMonitor = new ShellTaskStateMonitor(
                context,
                mService,
                windowing.requiresNativeFullscreenCaptionRefresh(),
                new ShellTaskStateMonitor.Listener() {
                    @Override
                    public void onTasksSampled(
                            final int displayId,
                            final java.util.List<?> tasks,
                            final java.util.List<
                                    ShellTaskStateMonitor.TaskWindowState>
                                    windowStates) {
                        mProcessFailureTracker.observeTasks(
                                displayId, windowStates);
                        mTaskActivityModeGuard.observeTasks(
                                displayId, windowStates);
                        for (final Integer taskId
                                : mDesktopOwnership.observeTasks(
                                        displayId, tasks)) {
                            restoreUnexpectedPhoneFreeform(
                                    displayId, taskId.intValue());
                        }
                        reportDesktopTaskOwnership();
                        mDesktopTaskArea.removeOrphanedTransientTasks(
                                displayId, tasks);
                        mFreeformCleanup.observeTasks(displayId, tasks);
                    }

                    @Override
                    public void onImmersiveRequest(
                            final int taskId,
                            final boolean requesting,
                            final boolean initialSample) {
                        final boolean restoredByObserver = !initialSample
                                && !requesting
                                && mFullscreenTaskArea.restoreAppFullscreen(
                                        mService,
                                        mConfiguredDisplayId,
                                        taskId);
                        callCallback(() -> mCallback.onImmersiveRequest(
                                taskId,
                                requesting,
                                initialSample,
                                restoredByObserver));
                    }

                    @Override
                    public void onWindowingModeChanged(
                            final int displayId,
                            final int taskId,
                            final int previousMode,
                            final int currentMode,
                            final int previousCaptionSourceId) {
                        mFullscreenTaskArea.onWindowingModeChanged(
                                displayId, taskId, currentMode);
                        mSelfTestTaskStackGuard.sample("windowing-mode");
                        callCallback(() -> mCallback.onWindowingModeChanged(
                                taskId,
                                previousMode,
                                currentMode,
                                previousCaptionSourceId));
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
    }

    void refreshTaskCaption(
            final int displayId,
            final int taskId,
            final int sourceId) throws ReflectiveOperationException {
        // The mode is already fullscreen. Only force the application client
        // to discard the caption source retained by Nubia.
        TaskCaptionInsetsRefresher.refreshTask(
                mService, displayId, taskId, sourceId);
        Log.d(TAG, "refreshed native fullscreen caption task=" + taskId);
    }

    void start() throws ReflectiveOperationException {
        try {
            HiddenTaskApi.registerTaskStackListener(mService, this);
            mRegistered = true;
            mStateMonitor.start();
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
            final boolean managedTaskArea,
            final int desktopHostTaskId) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        if (displayId < 0) {
            // IActivityController can cancel starts system-wide. Keep task
            // observation alive between sessions, but never retain launch
            // interception after the desktop configuration is cleared.
            mActivityStartController.close();
            mPhoneLauncherCircuitBreaker.configure(false);
            updateExternalNavigationGuard(false);
            mConfiguredDisplayId = Display.INVALID_DISPLAY;
            mFocusController.configure(-1);
            mMigrationGuard.configure(-1, false);
            mFreeformCleanup.configure(-1);
            mInputPanelGuard.configure(-1);
            mTaskActivityModeGuard.configure(Display.INVALID_DISPLAY);
            mProcessFailureTracker.configure(Display.INVALID_DISPLAY);
            mStateMonitor.clearConfiguration();
            // The fullscreen area is a sibling of the phone session area.
            // Delete it after draining its tasks into the session, before
            // session tasks are reparented to the default task container.
            // An empty task area makes affected WMS priority traversal fail.
            mFullscreenTaskArea.configure(
                    Display.INVALID_DISPLAY,
                    DesktopTaskAreaPolicy.DEFAULT,
                    0,
                    null);
            mDesktopTaskArea.configure(
                    Display.INVALID_DISPLAY, false, -1);
            mDesktopOwnership.configure(Display.INVALID_DISPLAY);
            reportDesktopTaskOwnership();
            mDesktopTaskAreaForeground = null;
            return;
        }
        mPhoneLauncherCircuitBreaker.configure(true);
        mProcessFailureTracker.configure(displayId);
        try {
            mActivityStartController.start();
        } catch (ReflectiveOperationException error) {
            mPhoneLauncherCircuitBreaker.configure(false);
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
        if (!mDesktopTaskArea.matchesConfiguration(
                displayId,
                managedTaskArea,
                desktopHostTaskId)) {
            // The fullscreen sibling releases tasks into the current session
            // and is deleted before that session becomes a reparent target.
            mFullscreenTaskArea.configure(
                    Display.INVALID_DISPLAY,
                    DesktopTaskAreaPolicy.DEFAULT,
                    0,
                    null);
            mDesktopTaskArea.configure(
                    Display.INVALID_DISPLAY, false, -1);
        }
        mDesktopTaskArea.configure(
                displayId,
                managedTaskArea,
                desktopHostTaskId);
        mFullscreenTaskArea.configure(
                displayId,
                managedTaskArea
                        ? DesktopTaskAreaPolicy.SESSION
                        : DesktopTaskAreaPolicy.DEFAULT,
                mDesktopTaskArea.fullscreenAreaParentFeatureId(),
                mDesktopTaskArea.fullscreenTaskReleaseParentToken(
                        displayId));
        if (!managedTaskArea) {
            mDesktopTaskAreaForeground = null;
        }
        updateExternalNavigationGuard(displayId != Display.DEFAULT_DISPLAY);
        mConfiguredDisplayId = displayId;
        mFocusController.configure(displayId);
        mMigrationGuard.configure(displayId, false);
        // External tasks must remain outside phone-side Recents cleanup.
        mFreeformCleanup.configure(
                mWindowing.requiresStalePhoneFreeformTaskCleanup()
                        && displayId == Display.DEFAULT_DISPLAY
                                ? displayId : -1);
        mInputPanelGuard.configure(displayId);
        mTaskActivityModeGuard.configure(displayId);
        mStateMonitor.configure(displayId, displayBounds, workAreaBounds);
        reportDesktopTaskOwnership();
    }

    void setExternalTaskMigrationProtection(final boolean enabled) {
        mMigrationGuard.configure(mConfiguredDisplayId, enabled);
    }

    void focusStack(
            final long sequence,
            final int displayId,
            final int[] taskIds) {
        int appliedTaskCount = 0;
        try {
            if (sequence < 0 || displayId < 0
                    || taskIds == null || taskIds.length == 0) {
                throw new IllegalArgumentException(
                        "invalid task stack focus request");
            }
            final int[] liveTaskIds = new int[taskIds.length];
            for (int index = 0; index < taskIds.length; index++) {
                final int taskId = taskIds[index];
                if (taskId < 0) {
                    throw new IllegalArgumentException("invalid task id");
                }
                if (HiddenTaskApi.findTask(mService, displayId, taskId) == null) {
                    if (index == taskIds.length - 1) {
                        throw new IllegalStateException(
                                "task " + taskId
                                        + " not found on display " + displayId);
                    }
                    Log.w(TAG, "task focus skipped stale task=" + taskId);
                    continue;
                }
                liveTaskIds[appliedTaskCount++] = taskId;
            }
            if (appliedTaskCount == 0) {
                throw new IllegalStateException("no live tasks to focus");
            }
            final int[] focusTaskIds =
                    Arrays.copyOf(liveTaskIds, appliedTaskCount);
            final ShellFullscreenTaskArea.FocusResult focusResult =
                    mFullscreenTaskArea.focusStack(
                            mService, displayId, focusTaskIds);
            if (focusResult
                    == ShellFullscreenTaskArea.FocusResult.NOT_HANDLED) {
                TaskWindowingCommand.focusTasks(
                        mService, displayId, focusTaskIds);
                reportDesktopTaskAreaForeground(
                        focusTaskIds[focusTaskIds.length - 1]);
            } else {
                // The fullscreen owner knows the destination hierarchy before
                // WMS applies its queued transition. Do not infer foreground
                // from the task's still-stale parent in that interval.
                reportDesktopTaskAreaForeground(
                        focusResult
                                == ShellFullscreenTaskArea.FocusResult
                                        .SESSION_FOREGROUND);
            }
            signalFocusStackResult(
                    sequence, true, appliedTaskCount, "");
        } catch (ReflectiveOperationException | RuntimeException error) {
            final String message = usefulMessage(error);
            signalFocusStackResult(
                    sequence, false, appliedTaskCount, message);
            Log.w(TAG, "task stack focus failed: " + message, error);
        }
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
                visible = HiddenTaskApi.getBooleanField(task, "isVisible");
            } catch (ReflectiveOperationException error) {
                visibilityKnown = false;
            }
            boolean focusKnown = true;
            boolean focused = false;
            try {
                focused = HiddenTaskApi.getBooleanField(task, "isFocused");
            } catch (ReflectiveOperationException error) {
                focusKnown = false;
            }
            return new TaskWindowSnapshot(
                    taskId,
                    displayId,
                    HiddenTaskApi.getWindowConfigurationValue(
                            task, "getWindowingMode"),
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
            final int taskId = mDesktopTaskArea.launchHost(
                    displayId, intentUri);
            reportDesktopTaskOwnership();
            reportDesktopTaskAreaForeground(true);
            return taskId;
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(
                    "cannot launch desktop host: " + usefulMessage(error),
                    error);
        }
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

    boolean closeFullscreenTask(
            final int displayId,
            final int taskId) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        return mFullscreenTaskArea.closeTask(
                mService, displayId, taskId);
    }

    boolean closeDesktopTask(
            final int displayId,
            final int taskId,
            final int focusTaskId) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        return mDesktopTaskArea.closeTask(
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
            final String intentUri,
            final Rect bounds) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        if (displayId != mConfiguredDisplayId) {
            throw new IllegalArgumentException(
                    "display is not configured: " + displayId);
        }
        try {
            final boolean managedArea = mDesktopTaskArea.manages(displayId);
            final int taskId = managedArea
                    ? mDesktopTaskArea.launch(displayId, intentUri, bounds)
                    : mTaskLauncher.launchWindowed(
                            displayId, intentUri, bounds, null);
            reportDesktopTaskOwnership();
            if (managedArea) {
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

    int launchFullscreenTaskInDesktopArea(
            final int displayId,
            final String intentUri) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        if (displayId != mConfiguredDisplayId
                || !mDesktopTaskArea.manages(displayId)) {
            throw new IllegalArgumentException(
                    "session task area is not configured: " + displayId);
        }
        try {
            final int taskId = mDesktopTaskArea.launchFullscreen(
                    displayId, intentUri);
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
            final String intentUri) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        if (displayId != mConfiguredDisplayId) {
            throw new IllegalArgumentException(
                    "display is not configured: " + displayId);
        }
        try {
            final int taskId = mTaskLauncher.launchFullscreen(
                    displayId, intentUri);
            reportDesktopTaskOwnership();
            return taskId;
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(
                    "cannot launch fullscreen task: "
                            + usefulMessage(error),
                    error);
        }
    }

    void launchTaskAction(
            final int displayId,
            final int taskId,
            final String intentUri) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        if (displayId != mConfiguredDisplayId) {
            throw new IllegalArgumentException(
                    "display is not configured: " + displayId);
        }
        try {
            TaskDisplayAreaLaunchCommand.launchTaskAction(
                    mService, displayId, taskId, intentUri);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(
                    "cannot launch task action: " + usefulMessage(error),
                    error);
        }
    }

    void placeTaskInDesktopArea(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final Rect bounds) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        try {
            mDesktopTaskArea.placeTask(
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

    void placeFullscreenTaskInDesktopArea(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        try {
            mDesktopTaskArea.placeFullscreenTask(
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
        mSelfTestTaskStackGuard.start(displayId, hostTaskId, stage);
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
        mInputPanelGuard.onTaskAppeared(taskId, componentName);
        signalChange("task-created");
    }

    @Override
    public void onTaskRemoved(final int taskId) {
        if (!mClosed) {
            synchronized (this) {
                if (mPhoneTouchpadTaskId == taskId) {
                    mPhoneTouchpadTaskId = -1;
                }
            }
            mInputPanelGuard.onTaskRemoved(taskId);
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

    @Override
    public void onTaskMovedToFront(
            final ActivityManager.RunningTaskInfo taskInfo) {
        if (taskInfo != null) {
            final int displayId = HiddenTaskApi.getTaskDisplayId(taskInfo);
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
            mInputPanelGuard.onTaskAppeared(
                    taskInfo.taskId, taskInfo.topActivity);
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
        mFocusController.onTaskFocusChanged(taskId, focused);
        signalChange("focus-changed");
    }

    @Override
    public void onActivityRequestedOrientationChanged(
            final int taskId,
            final int requestedOrientation) {
        signalChange("activity-orientation");
    }

    @Override
    public void onTaskRequestedOrientationChanged(
            final int taskId,
            final int requestedOrientation) {
        signalChange("task-orientation");
    }

    @Override
    public void close() {
        if (mClosed) {
            return;
        }
        mClosed = true;
        mPhoneLauncherCircuitBreaker.configure(false);
        final boolean registered = mRegistered;
        mRegistered = false;
        closeSafely("navigation guard", () ->
                updateExternalNavigationGuard(false));
        synchronized (this) {
            mPreservePhoneTouchpad = false;
            mPhoneTouchpadRequested = false;
        }
        closeSafely("focus controller", mFocusController::close);
        closeSafely("input panel guard", mInputPanelGuard::close);
        closeSafely("process failure tracker", () ->
                mProcessFailureTracker.configure(Display.INVALID_DISPLAY));
        closeSafely("activity start controller",
                mActivityStartController::close);
        closeSafely("migration guard", mMigrationGuard::close);
        closeSafely("freeform cleanup", mFreeformCleanup::close);
        closeSafely("state monitor", mStateMonitor::close);
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

    private void updateExternalNavigationGuard(final boolean enabled) {
        if (enabled == mExternalNavigationGuardActive) {
            return;
        }
        try {
            if (enabled) {
                // Nubia Quickstep crashes while binding live external
                // freeform tasks. Keep Home available but block Overview.
                mNavigationGuard.acquire(
                        mOwnerToken,
                        PlatformPhoneUiDriver.NavigationGuard.Scope
                                .EXTERNAL_DESKTOP);
                mExternalNavigationGuardActive = true;
                Log.i(TAG, "guarded phone Recents for external desktop");
            } else {
                mNavigationGuard.release(mOwnerToken);
                mExternalNavigationGuardActive = false;
                Log.i(TAG, "restored phone Recents after external desktop");
            }
        } catch (RuntimeException error) {
            if (!enabled) {
                mExternalNavigationGuardActive = false;
            }
            final String message = usefulMessage(error);
            Log.w(TAG, "could not update external navigation guard: "
                    + message, error);
            callCallback(() -> mCallback.onObserverError(
                    "external navigation guard unavailable: " + message));
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

    private void reportDesktopTaskAreaForeground(final int taskId) {
        try {
            final Object task = HiddenTaskApi.findTask(
                    mService, Display.INVALID_DISPLAY, taskId);
            if (task == null) {
                return;
            }
            final Boolean foreground = mDesktopTaskArea.foregroundForTask(
                    HiddenTaskApi.getTaskDisplayId(task), taskId);
            if (foreground != null) {
                reportDesktopTaskAreaForeground(foreground.booleanValue());
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not resolve focused task area", error);
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

    private boolean allowPhoneUiStart(
            final Intent intent,
            final String packageName) {
        // ActivityTaskManager invokes this callback synchronously while it can
        // hold its global lock. Read only volatile/local state here: calling a
        // synchronized task-area method can deadlock against a shell command
        // that holds that monitor while waiting for ActivityTaskManager.
        final boolean suppressLocalHome = !mClosed
                && mConfiguredDisplayId == Display.DEFAULT_DISPLAY
                && isHomeIntent(intent);
        final boolean suppressCrashedPhoneLauncher =
                !mPhoneLauncherCircuitBreaker.allowActivityStart(
                        mPhoneHome.isPrimaryHomeStart(intent, packageName));
        final boolean suppressExternalSecondaryHome;
        synchronized (this) {
            // Back on the last local desktop client can make Android launch its
            // ordinary HOME task even though the session host remains alive in
            // our task area. Reject that fallback before it can cover the host
            // or a surviving client. Session shutdown releases the observer
            // before intentionally returning to the phone launcher.
            // Nubia starts its secondary launcher on the phone while removing
            // an external task. Reject it before it can cover the requested
            // touchpad; post-start task correction still produces a blink.
            suppressExternalSecondaryHome = !mClosed
                    && mPhoneTouchpadRequested
                    && mConfiguredDisplayId > Display.DEFAULT_DISPLAY
                    && mPhoneUi.isTransientSecondaryHomeIntent(intent);
        }
        if (suppressLocalHome) {
            Log.i(TAG, "suppressed HOME fallback inside local desktop session");
        } else if (suppressCrashedPhoneLauncher) {
            Log.i(TAG, "suppressed HOME after phone launcher crash");
        } else if (suppressExternalSecondaryHome) {
            Log.i(TAG, "suppressed transient secondary HOME while phone "
                    + "touchpad is requested");
        }
        return !suppressLocalHome
                && !suppressCrashedPhoneLauncher
                && !suppressExternalSecondaryHome;
    }

    private static boolean isHomeIntent(final Intent intent) {
        return intent != null
                && Intent.ACTION_MAIN.equals(intent.getAction())
                && intent.hasCategory(Intent.CATEGORY_HOME);
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
            mStateMonitor.requestSample();
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

package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Context;
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
    private final PlatformPhoneUiDriver.NavigationGuard mNavigationGuard;
    private final AtomicBoolean mCallbackFailed = new AtomicBoolean();
    private final ShellFreeformTaskCleanup mFreeformCleanup;
    private final ShellDesktopFocusController mFocusController;
    private final ShellExternalTaskMigrationGuard mMigrationGuard;
    private final PlatformPhoneUiDriver.TaskEventGuard mInputPanelGuard;
    private final ShellTaskStateMonitor mStateMonitor;
    private final ShellTransientTaskBoundsController mTransientBounds;
    private final ShellFullscreenTaskArea mFullscreenTaskArea =
            new ShellFullscreenTaskArea();
    private final ShellSelfTestTaskStackGuard mSelfTestTaskStackGuard;

    private volatile boolean mClosed;
    private boolean mRegistered;
    private boolean mPreservePhoneTouchpad;
    private boolean mRestoringPhoneTouchpad;
    private boolean mExternalNavigationGuardActive;
    private int mPhoneTouchpadTaskId = -1;
    private int mConfiguredDisplayId = Display.INVALID_DISPLAY;

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
        mSelfTestTaskStackGuard = new ShellSelfTestTaskStackGuard(mService);
        mCallback = callback;
        mCallbackFailure = callbackFailure;
        mOwnerToken = ownerToken;
        mWindowing = windowing;
        mNavigationGuard = navigationGuard;
        mFocusController = new ShellDesktopFocusController(
                mService,
                windowing.requiresMirrorInputFocusSynchronization(),
                () -> callCallback(
                        mCallback::onInputFocusRefreshRequired));
        mInputPanelGuard = phoneUi.createInputPanelGuard(
                mService, inputOwner);
        mMigrationGuard = new ShellExternalTaskMigrationGuard(
                mService,
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
        mTransientBounds = new ShellTransientTaskBoundsController(mService);
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
                            final java.util.List<?> tasks) {
                        mTransientBounds.observeTasks(displayId, tasks);
                        mFreeformCleanup.observeTasks(displayId, tasks);
                    }

                    @Override
                    public void onImmersiveRequest(
                            final int taskId,
                            final boolean requesting,
                            final boolean initialSample) {
                        callCallback(() -> mCallback.onImmersiveRequest(
                                taskId, requesting, initialSample));
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
                            final String packageName,
                            final int displayId,
                            final Rect bounds) {
                        callCallback(() -> mCallback.onFreeformBoundsChanged(
                                taskId,
                                packageName,
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
        HiddenTaskApi.registerTaskStackListener(mService, this);
        mRegistered = true;
        mStateMonitor.start();
    }

    void configure(
            final int displayId,
            final Rect displayBounds,
            final Rect workAreaBounds) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        mFullscreenTaskArea.configure(displayId);
        if (displayId < 0) {
            updateExternalNavigationGuard(false);
            mConfiguredDisplayId = Display.INVALID_DISPLAY;
            mFocusController.configure(-1);
            mMigrationGuard.configure(-1, false);
            mFreeformCleanup.configure(-1);
            mInputPanelGuard.configure(-1);
            mTransientBounds.clearConfiguration();
            mStateMonitor.clearConfiguration();
            return;
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
        mTransientBounds.configure(displayId, displayBounds);
        mStateMonitor.configure(displayId, displayBounds, workAreaBounds);
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
            if (!mFullscreenTaskArea.focusStack(
                    mService, displayId, focusTaskIds)) {
                TaskWindowingCommand.focusTasks(
                        mService, displayId, focusTaskIds);
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

    boolean releaseFullscreenTask(
            final int displayId,
            final int taskId) {
        if (mClosed) {
            throw new IllegalStateException("task observer is closed");
        }
        return mFullscreenTaskArea.releaseTask(
                mService, displayId, taskId);
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

    @Override
    public void onTaskStackChanged() {
        mMigrationGuard.onTaskStackChanged();
        signalChange("stack-changed");
    }

    @Override
    public void onTaskCreated(
            final int taskId,
            final ComponentName componentName) {
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
            mTransientBounds.forget(taskId);
            mFullscreenTaskArea.onTaskRemoved(taskId);
            callCallback(() -> mCallback.onTaskGone(taskId));
            signalChange("task-removed");
        }
    }

    @Override
    public void onTaskMovedToFront(
            final ActivityManager.RunningTaskInfo taskInfo) {
        if (taskInfo != null) {
            mMigrationGuard.onTaskMovedToFront(taskInfo);
            if (isPhoneTouchpadTask(taskInfo)) {
                synchronized (this) {
                    mPhoneTouchpadTaskId = taskInfo.taskId;
                }
            } else if (HiddenTaskApi.getTaskDisplayId(taskInfo)
                    == Display.DEFAULT_DISPLAY) {
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
    public void close() {
        if (mClosed) {
            return;
        }
        updateExternalNavigationGuard(false);
        mClosed = true;
        synchronized (this) {
            mPreservePhoneTouchpad = false;
        }
        mFocusController.close();
        mInputPanelGuard.close();
        mMigrationGuard.close();
        mFreeformCleanup.close();
        mTransientBounds.close();
        mStateMonitor.close();
        mFullscreenTaskArea.close();
        mSelfTestTaskStackGuard.close();
        if (!mRegistered) {
            return;
        }
        mRegistered = false;
        try {
            HiddenTaskApi.unregisterTaskStackListener(mService, this);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "failed to unregister task observer", error);
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

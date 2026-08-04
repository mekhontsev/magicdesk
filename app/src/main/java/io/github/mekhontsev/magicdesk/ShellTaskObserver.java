package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Rect;
import android.os.RemoteException;
import android.util.Log;

import java.io.Closeable;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
final class ShellTaskObserver extends TaskStackListener implements Closeable {
    private static final String TAG = "MagicDeskTasks";

    private final Object mService;
    private final ITaskObserverCallback mCallback;
    private final Runnable mCallbackFailure;
    private final AtomicBoolean mCallbackFailed = new AtomicBoolean();
    private final ShellFreeformTaskCleanup mFreeformCleanup;
    private final ShellTaskStateMonitor mStateMonitor;
    private final ShellTransientTaskBoundsController mTransientBounds;

    private volatile boolean mClosed;
    private boolean mRegistered;

    ShellTaskObserver(
            final Context context,
            final ITaskObserverCallback callback,
            final Runnable callbackFailure)
            throws ReflectiveOperationException {
        if (callback == null) {
            throw new IllegalArgumentException("missing task observer callback");
        }
        mService = HiddenTaskApi.getService();
        mCallback = callback;
        mCallbackFailure = callbackFailure;
        mTransientBounds = new ShellTransientTaskBoundsController(mService);
        // Nubia's launcher crashes while binding a DesktopTaskView when a
        // finished freeform task remains in Recents and DesktopRepository.
        mFreeformCleanup = new ShellFreeformTaskCleanup(
                mService,
                error -> callCallback(() -> mCallback.onObserverError(error)));
        mStateMonitor = new ShellTaskStateMonitor(
                context,
                mService,
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
                    public void onNativeMaximizeChanged(
                            final int taskId,
                            final boolean enteredFullscreen) {
                        callCallback(() -> mCallback.onNativeMaximizeChanged(
                                taskId, enteredFullscreen));
                    }

                    @Override
                    public void onError(final String error) {
                        callCallback(() -> mCallback.onObserverError(error));
                    }
                });
    }

    void start() throws ReflectiveOperationException {
        final Class<?> listenerClass =
                Class.forName("android.app.ITaskStackListener");
        mService.getClass()
                .getMethod("registerTaskStackListener", listenerClass)
                .invoke(mService, this);
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
        mFreeformCleanup.configure(displayId);
        mTransientBounds.configure(displayId, displayBounds);
        mStateMonitor.configure(displayId, displayBounds, workAreaBounds);
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
                TaskControlCommand.setFocusedTask(mService, taskId);
                appliedTaskCount++;
            }
            if (appliedTaskCount == 0) {
                throw new IllegalStateException("no live tasks to focus");
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

    @Override
    public void onTaskStackChanged() {
        signalChange();
    }

    @Override
    public void onTaskCreated(
            final int taskId,
            final ComponentName componentName) {
        signalChange();
    }

    @Override
    public void onTaskRemoved(final int taskId) {
        if (!mClosed) {
            mTransientBounds.forget(taskId);
            callCallback(() -> mCallback.onTaskGone(taskId));
            signalChange();
        }
    }

    @Override
    public void onTaskMovedToFront(
            final ActivityManager.RunningTaskInfo taskInfo) {
        signalChange();
    }

    @Override
    public void onTaskMovedToBack(
            final ActivityManager.RunningTaskInfo taskInfo) {
        signalChange();
    }

    @Override
    public void onTaskDisplayChanged(
            final int taskId,
            final int newDisplayId) {
        signalChange();
    }

    @Override
    public void onTaskFocusChanged(
            final int taskId,
            final boolean focused) {
        signalChange();
    }

    @Override
    public void close() {
        if (mClosed) {
            return;
        }
        mClosed = true;
        mFreeformCleanup.close();
        mTransientBounds.close();
        mStateMonitor.close();
        if (!mRegistered) {
            return;
        }
        mRegistered = false;
        try {
            final Class<?> listenerClass =
                    Class.forName("android.app.ITaskStackListener");
            mService.getClass()
                    .getMethod("unregisterTaskStackListener", listenerClass)
                    .invoke(mService, this);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "failed to unregister task observer", error);
        }
    }

    private void signalChange() {
        if (!mClosed) {
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

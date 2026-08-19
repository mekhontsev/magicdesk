package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.os.IBinder;
import android.util.Log;

/** Launches a task in its initial freeform transition. */
final class ShellWindowedTaskLauncher {
    interface Listener {
        void onWindowedLaunchStarting(ComponentName component);
        void onWindowedTaskIdentified(
                int taskId,
                ComponentName component,
                int displayId,
                Rect bounds);
        void onWindowedLaunchFinished(ComponentName component);
    }

    private static final String TAG = "MagicDeskWindowLaunch";
    private static final int WINDOWING_MODE_FREEFORM = 5;

    private final Object mService;
    private final ShellDesktopTaskOwnership mOwnership;
    private final Listener mListener;

    private volatile PendingLaunch mPendingLaunch;

    ShellWindowedTaskLauncher(
            final Object service,
            final ShellDesktopTaskOwnership ownership,
            final Listener listener) {
        mService = service;
        mOwnership = ownership;
        mListener = listener;
    }

    synchronized int launch(
            final int displayId,
            final String intentUri,
            final Rect bounds,
            final Object taskAreaToken) throws ReflectiveOperationException {
        if (displayId < 0 || bounds == null || bounds.isEmpty()) {
            throw new IllegalArgumentException(
                    "windowed launch requires a display and bounds");
        }
        final Intent intent = TaskDisplayAreaLaunchCommand.createAppIntent(
                intentUri);
        final PendingLaunch pending = new PendingLaunch(
                intent.getComponent(), displayId, bounds);
        if (mPendingLaunch != null) {
            throw new IllegalStateException(
                    "another windowed task launch is in progress");
        }
        mPendingLaunch = pending;
        if (mListener != null) {
            mListener.onWindowedLaunchStarting(intent.getComponent());
        }
        try {
            final Class<?> tokenClass = taskAreaToken == null
                    ? null
                    : Class.forName("android.window.WindowContainerToken");
            final int taskId = TaskDisplayAreaLaunchCommand.launchTask(
                    mService,
                    displayId,
                    intent,
                    intent.getComponent().getPackageName(),
                    bounds,
                    tokenClass,
                    taskAreaToken,
                    false,
                    pending::onTransitionStarted);
            pending.complete(taskId);
            // WindowManager may enlarge bounds for an application's minimum
            // size, but the launch contract still requires freeform mode.
            TaskDisplayAreaLaunchCommand.waitForTaskWindowingMode(
                    mService,
                    displayId,
                    taskId,
                    WINDOWING_MODE_FREEFORM);
            return taskId;
        } finally {
            if (mListener != null) {
                mListener.onWindowedLaunchFinished(intent.getComponent());
            }
            if (mPendingLaunch == pending) {
                mPendingLaunch = null;
            }
        }
    }

    void onTaskCreated(
            final int taskId,
            final ComponentName componentName) {
        final PendingLaunch pending = mPendingLaunch;
        if (pending != null) {
            pending.onTaskCreated(taskId, componentName);
        }
    }

    private final class PendingLaunch {
        private final ComponentName mComponent;
        private final int mDisplayId;
        private final Rect mBounds;
        private int mObservedTaskId = -1;
        private IBinder mTransitionToken;
        private boolean mApplied;
        private boolean mObservedByCallback;
        private boolean mIdentified;

        PendingLaunch(
                final ComponentName component,
                final int displayId,
                final Rect bounds) {
            mComponent = component;
            mDisplayId = displayId;
            mBounds = new Rect(bounds);
        }

        synchronized void onTaskCreated(
                final int taskId,
                final ComponentName componentName) {
            if (!mApplied
                    && mObservedTaskId < 0
                    && mComponent.equals(componentName)) {
                mObservedTaskId = taskId;
                mObservedByCallback = true;
                identify(taskId);
            }
        }

        synchronized void onTransitionStarted(final IBinder transitionToken)
                throws ReflectiveOperationException {
            mTransitionToken = transitionToken;
            if (mObservedTaskId >= 0) {
                apply(mObservedTaskId);
            }
        }

        synchronized void complete(final int taskId)
                throws ReflectiveOperationException {
            if (mObservedTaskId < 0) {
                mObservedTaskId = taskId;
                identify(taskId);
            } else if (mObservedTaskId != taskId) {
                throw new IllegalStateException(
                        "created task does not match launched task: observed="
                                + mObservedTaskId + ", launched=" + taskId);
            }
            apply(taskId);
        }

        private void apply(final int taskId)
                throws ReflectiveOperationException {
            if (mApplied) {
                return;
            }
            if (mTransitionToken == null) {
                throw new IllegalStateException(
                        "launch transition token is unavailable");
            }
            ShellPreparedTaskTransition.joinOpenAsFreeform(
                    mService,
                    mDisplayId,
                    taskId,
                    mBounds,
                    mTransitionToken);
            mApplied = true;
            Log.i(TAG, "joined initial transition task=" + taskId
                    + " early=" + mObservedByCallback);
        }

        private void identify(final int taskId) {
            if (mIdentified) {
                return;
            }
            mIdentified = true;
            mOwnership.markDesktop(taskId);
            if (mListener != null) {
                mListener.onWindowedTaskIdentified(
                        taskId, mComponent, mDisplayId, mBounds);
            }
        }
    }
}

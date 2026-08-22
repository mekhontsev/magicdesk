package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.IBinder;
import android.util.Log;

/** Launches a task with its requested mode known before the task appears. */
final class ShellTaskLauncher {
    interface Listener {
        void onTaskLaunchStarting(
                ComponentName component, int windowingMode);
        void onTaskIdentified(
                int taskId,
                ComponentName component,
                int displayId,
                Rect bounds,
                int windowingMode);
        void onTaskLaunchFinished(
                ComponentName component, int windowingMode);
    }

    private static final String TAG = "MagicDeskWindowLaunch";
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;

    private final Object mService;
    private final PackageManager mPackageManager;
    private final ShellDesktopTaskOwnership mOwnership;
    private final Listener mListener;

    private volatile PendingLaunch mPendingLaunch;

    ShellTaskLauncher(
            final Object service,
            final PackageManager packageManager,
            final ShellDesktopTaskOwnership ownership,
            final Listener listener) {
        mService = service;
        mPackageManager = packageManager;
        mOwnership = ownership;
        mListener = listener;
    }

    synchronized int launchWindowed(
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
        final LaunchActivityIdentity activityIdentity =
                LaunchActivityIdentity.resolve(
                        mPackageManager, intent.getComponent());
        final PendingLaunch pending = new PendingLaunch(
                activityIdentity,
                displayId,
                bounds,
                WINDOWING_MODE_FREEFORM);
        if (mPendingLaunch != null) {
            throw new IllegalStateException(
                    "another windowed task launch is in progress");
        }
        mPendingLaunch = pending;
        if (mListener != null) {
            mListener.onTaskLaunchStarting(
                    intent.getComponent(), WINDOWING_MODE_FREEFORM);
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
                mListener.onTaskLaunchFinished(
                        intent.getComponent(), WINDOWING_MODE_FREEFORM);
            }
            if (mPendingLaunch == pending) {
                mPendingLaunch = null;
            }
        }
    }

    synchronized int launchFullscreen(
            final int displayId,
            final String intentUri) throws ReflectiveOperationException {
        if (displayId < 0) {
            throw new IllegalArgumentException(
                    "fullscreen launch requires a display");
        }
        final Intent intent = TaskDisplayAreaLaunchCommand.createAppIntent(
                intentUri);
        final LaunchActivityIdentity activityIdentity =
                LaunchActivityIdentity.resolve(
                        mPackageManager, intent.getComponent());
        final PendingLaunch pending = new PendingLaunch(
                activityIdentity,
                displayId,
                new Rect(),
                WINDOWING_MODE_FULLSCREEN);
        if (mPendingLaunch != null) {
            throw new IllegalStateException(
                    "another task launch is in progress");
        }
        mPendingLaunch = pending;
        if (mListener != null) {
            mListener.onTaskLaunchStarting(
                    intent.getComponent(), WINDOWING_MODE_FULLSCREEN);
        }
        try {
            final int taskId =
                    TaskDisplayAreaLaunchCommand.launchFullscreenTask(
                            mService,
                            displayId,
                            intent,
                            intent.getComponent().getPackageName());
            pending.complete(taskId);
            TaskDisplayAreaLaunchCommand.waitForTaskWindowingMode(
                    mService,
                    displayId,
                    taskId,
                    WINDOWING_MODE_FULLSCREEN);
            return taskId;
        } finally {
            if (mListener != null) {
                mListener.onTaskLaunchFinished(
                        intent.getComponent(), WINDOWING_MODE_FULLSCREEN);
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
        private final LaunchActivityIdentity mActivityIdentity;
        private final int mDisplayId;
        private final Rect mBounds;
        private final int mWindowingMode;
        private int mObservedTaskId = -1;
        private IBinder mTransitionToken;
        private boolean mApplied;
        private boolean mObservedByCallback;
        private boolean mIdentified;

        PendingLaunch(
                final LaunchActivityIdentity activityIdentity,
                final int displayId,
                final Rect bounds,
                final int windowingMode) {
            mActivityIdentity = activityIdentity;
            mDisplayId = displayId;
            mBounds = new Rect(bounds);
            mWindowingMode = windowingMode;
        }

        synchronized void onTaskCreated(
                final int taskId,
                final ComponentName componentName) {
            final boolean matches = mActivityIdentity.matches(componentName);
            if (!mApplied
                    && mObservedTaskId < 0
                    && matches) {
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
            if (mWindowingMode == WINDOWING_MODE_FULLSCREEN) {
                mApplied = true;
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
                mListener.onTaskIdentified(
                        taskId,
                        mActivityIdentity.requestedComponent(),
                        mDisplayId,
                        mBounds,
                        mWindowingMode);
            }
        }
    }
}

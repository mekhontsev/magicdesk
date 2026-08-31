package io.github.mekhontsev.magicdesk;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.graphics.Rect;
import android.os.UserHandle;

/** Launches a task with its requested mode known before the task appears. */
final class ShellTaskLauncher {
    interface Listener {
        void onTaskLaunchStarting(
                LaunchActivityIdentity identity, int windowingMode);
        void onTaskIdentified(
                int taskId,
                ComponentName component,
                int displayId,
                Rect bounds,
                int windowingMode);
        void onTaskLaunchFinished(
                LaunchActivityIdentity identity, int windowingMode);
    }

    private interface TaskStarter {
        int start(TaskDisplayAreaLaunchCommand.TaskIdSource taskIdSource)
                throws ReflectiveOperationException;
    }

    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;

    private final Object mService;
    private final Context mContext;
    private final PackageManager mPackageManager;
    private final ShellDesktopTaskOwnership mOwnership;
    private final Listener mListener;
    private final IActivityLaunchCallback mActivityLauncher;

    private volatile PendingLaunch mPendingLaunch;

    ShellTaskLauncher(
            final Object service,
            final Context context,
            final PackageManager packageManager,
            final ShellDesktopTaskOwnership ownership,
            final Listener listener,
            final IActivityLaunchCallback activityLauncher) {
        mService = service;
        mContext = context;
        mPackageManager = packageManager;
        mOwnership = ownership;
        mListener = listener;
        mActivityLauncher = activityLauncher;
    }

    synchronized int launchWindowed(
            final int displayId,
            final Intent intent,
            final Rect bounds,
            final Object taskAreaToken) throws ReflectiveOperationException {
        return launchWindowed(
                displayId,
                intent,
                bounds,
                taskAreaToken,
                taskAreaToken == null);
    }

    synchronized int launchWindowed(
            final int displayId,
            final Intent sourceIntent,
            final Rect bounds,
            final Object taskAreaToken,
            final boolean stagedReveal) throws ReflectiveOperationException {
        if (displayId < 0 || bounds == null || bounds.isEmpty()) {
            throw new IllegalArgumentException(
                    "windowed launch requires a display and bounds");
        }
        final Intent intent = requireExplicitIntent(sourceIntent);
        return launchPreparedTask(
                intent.getComponent(),
                displayId,
                bounds,
                WINDOWING_MODE_FREEFORM,
                stagedReveal,
                taskIdSource -> TaskDisplayAreaLaunchCommand.launchTask(
                        mService,
                        displayId,
                        intent,
                        intent.getComponent().getPackageName(),
                        bounds,
                        taskAreaToken,
                        stagedReveal,
                        taskIdSource));
    }

    synchronized int launchFullscreen(
            final int displayId,
            final Intent intent) throws ReflectiveOperationException {
        return launchFullscreen(displayId, intent, null);
    }

    synchronized int launchShortcutWindowed(
            final int displayId,
            final String packageName,
            final String shortcutId,
            final UserHandle user,
            final Rect bounds,
            final Object taskAreaToken,
            final boolean stagedReveal) throws ReflectiveOperationException {
        if (displayId < 0 || bounds == null || bounds.isEmpty()) {
            throw new IllegalArgumentException(
                    "windowed shortcut launch requires a display and bounds");
        }
        final ShortcutInfo shortcut = ShellShortcutGateway.require(
                mContext, packageName, shortcutId, user);
        final ComponentName component = ShellShortcutGateway.targetComponent(
                shortcut);
        final PendingIntent pendingIntent =
                ShellShortcutGateway.launchIntent(mContext, shortcut);
        return launchPreparedTask(
                LaunchActivityIdentity.packageScoped(
                        shortcut.getPackage(), component),
                displayId,
                bounds,
                WINDOWING_MODE_FREEFORM,
                stagedReveal,
                taskIdSource ->
                        TaskDisplayAreaLaunchCommand.launchPendingIntentTask(
                                mService,
                                displayId,
                                shortcut.getPackage(),
                                pendingIntent,
                                bounds,
                                taskAreaToken,
                                stagedReveal,
                                taskIdSource,
                                mActivityLauncher));
    }

    synchronized int launchShortcutFullscreen(
            final int displayId,
            final String packageName,
            final String shortcutId,
            final UserHandle user,
            final Object taskAreaToken) throws ReflectiveOperationException {
        if (displayId < 0) {
            throw new IllegalArgumentException(
                    "fullscreen shortcut launch requires a display");
        }
        final ShortcutInfo shortcut = ShellShortcutGateway.require(
                mContext, packageName, shortcutId, user);
        final ComponentName component = ShellShortcutGateway.targetComponent(
                shortcut);
        final PendingIntent pendingIntent =
                ShellShortcutGateway.launchIntent(mContext, shortcut);
        return launchPreparedTask(
                LaunchActivityIdentity.packageScoped(
                        shortcut.getPackage(), component),
                displayId,
                new Rect(),
                WINDOWING_MODE_FULLSCREEN,
                false,
                taskIdSource -> TaskDisplayAreaLaunchCommand
                        .launchFullscreenPendingIntentTask(
                                mService,
                                displayId,
                                shortcut.getPackage(),
                                pendingIntent,
                                taskAreaToken,
                                taskIdSource,
                                mActivityLauncher));
    }

    synchronized void launchShortcutInTask(
            final int displayId,
            final int taskId,
            final String packageName,
            final String shortcutId,
            final UserHandle user) throws ReflectiveOperationException {
        final Object task = HiddenTaskApi.findTask(mService, displayId, taskId);
        if (task == null || !packageName.equals(HiddenTaskApi.getTaskPackage(task))) {
            throw new IllegalArgumentException(
                    "shortcut task is unavailable or belongs to another app");
        }
        final ShortcutInfo shortcut = ShellShortcutGateway.require(
                mContext, packageName, shortcutId, user);
        TaskDisplayAreaLaunchCommand.launchPendingIntentTaskAction(
                displayId,
                taskId,
                ShellShortcutGateway.launchIntent(mContext, shortcut),
                mActivityLauncher);
    }

    synchronized int launchFullscreen(
            final int displayId,
            final Intent sourceIntent,
            final Object taskAreaToken) throws ReflectiveOperationException {
        if (displayId < 0) {
            throw new IllegalArgumentException(
                    "fullscreen launch requires a display");
        }
        final Intent intent = requireExplicitIntent(sourceIntent);
        return launchPreparedTask(
                intent.getComponent(),
                displayId,
                new Rect(),
                WINDOWING_MODE_FULLSCREEN,
                false,
                taskIdSource -> TaskDisplayAreaLaunchCommand
                        .launchFullscreenTask(
                            mService,
                            displayId,
                            intent,
                            intent.getComponent().getPackageName(),
                            taskAreaToken));
    }

    void onTaskCreated(
            final int taskId,
            final ComponentName componentName) {
        final PendingLaunch pending = mPendingLaunch;
        if (pending != null) {
            pending.onTaskObserved(taskId, componentName);
        }
    }

    void onTaskMovedToFront(
            final int taskId,
            final ComponentName componentName) {
        final PendingLaunch pending = mPendingLaunch;
        if (pending != null) {
            pending.onTaskObserved(taskId, componentName);
        }
    }

    private int launchPreparedTask(
            final ComponentName component,
            final int displayId,
            final Rect bounds,
            final int windowingMode,
            final boolean stagedReveal,
            final TaskStarter starter) throws ReflectiveOperationException {
        return launchPreparedTask(
                LaunchActivityIdentity.resolve(mPackageManager, component),
                displayId,
                bounds,
                windowingMode,
                stagedReveal,
                starter);
    }

    private int launchPreparedTask(
            final LaunchActivityIdentity identity,
            final int displayId,
            final Rect bounds,
            final int windowingMode,
            final boolean stagedReveal,
            final TaskStarter starter) throws ReflectiveOperationException {
        final PendingLaunch pending = beginLaunch(
                identity, displayId, bounds, windowingMode);
        try {
            final int taskId = starter.start(pending::awaitObservedTaskId);
            pending.complete(taskId, observedComponent(displayId, taskId));
            if (stagedReveal) {
                // A new task has no stable token until the start callback.
                ShellPreparedTaskTransition.revealFreeform(
                        mService, displayId, taskId, bounds);
            }
            // WindowManager may adjust bounds for minimum-size constraints,
            // but the requested mode is part of the launch contract.
            TaskDisplayAreaLaunchCommand.waitForTaskWindowingMode(
                    mService, displayId, taskId, windowingMode);
            return taskId;
        } finally {
            finishLaunch(pending);
        }
    }

    private PendingLaunch beginLaunch(
            final LaunchActivityIdentity identity,
            final int displayId,
            final Rect bounds,
            final int windowingMode) {
        if (mPendingLaunch != null) {
            throw new IllegalStateException("another task launch is in progress");
        }
        final PendingLaunch pending = new PendingLaunch(
                identity,
                displayId,
                bounds,
                windowingMode);
        mPendingLaunch = pending;
        if (mListener != null) {
            mListener.onTaskLaunchStarting(identity, windowingMode);
        }
        return pending;
    }

    private void finishLaunch(final PendingLaunch pending) {
        if (mListener != null) {
            mListener.onTaskLaunchFinished(
                    pending.mActivityIdentity,
                    pending.mWindowingMode);
        }
        if (mPendingLaunch == pending) {
            mPendingLaunch = null;
        }
    }

    private static Intent requireExplicitIntent(final Intent source) {
        return TaskDisplayAreaLaunchCommand.createAppIntent(source);
    }

    private ComponentName observedComponent(
            final int displayId,
            final int taskId) throws ReflectiveOperationException {
        final FrameworkTaskSnapshot task = FrameworkTaskSnapshotSource.findTask(
                mService, displayId, taskId);
        if (task == null) {
            return null;
        }
        return task.topComponent == null
                ? task.rootComponent : task.topComponent;
    }

    private final class PendingLaunch {
        private final LaunchActivityIdentity mActivityIdentity;
        private final int mDisplayId;
        private final Rect mBounds;
        private final int mWindowingMode;
        private int mObservedTaskId = -1;
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

        synchronized void onTaskObserved(
                final int taskId,
                final ComponentName componentName) {
            final boolean matches = mActivityIdentity.matches(componentName);
            if (mObservedTaskId < 0 && matches) {
                mObservedTaskId = taskId;
                notifyAll();
                identify(taskId, componentName);
            }
        }

        synchronized void complete(
                final int taskId,
                final ComponentName componentName) {
            if (mObservedTaskId < 0) {
                mObservedTaskId = taskId;
                notifyAll();
                identify(taskId, componentName);
            } else if (mObservedTaskId != taskId) {
                throw new IllegalStateException(
                        "created task does not match launched task: observed="
                                + mObservedTaskId + ", launched=" + taskId);
            }
        }

        synchronized int awaitObservedTaskId(final long timeoutMillis) {
            if (mObservedTaskId < 0) {
                try {
                    EventDrivenWaits.await(
                            this,
                            EventDrivenWaits.Reason.TASK_CREATION,
                            timeoutMillis);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
            return mObservedTaskId;
        }

        private void identify(
                final int taskId,
                final ComponentName observedComponent) {
            if (mIdentified) {
                return;
            }
            mIdentified = true;
            mOwnership.markDesktop(taskId);
            if (mListener != null) {
                mListener.onTaskIdentified(
                        taskId,
                        observedComponent == null
                                ? mActivityIdentity.requestedComponent()
                                : observedComponent,
                        mDisplayId,
                        mBounds,
                        mWindowingMode);
            }
        }
    }
}

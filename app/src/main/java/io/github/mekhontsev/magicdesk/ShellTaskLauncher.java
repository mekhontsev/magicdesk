package io.github.mekhontsev.magicdesk;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.graphics.Rect;
import android.os.UserHandle;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

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
                taskAreaToken == null,
                DesktopTaskDensity.UNCHANGED);
    }

    synchronized int launchWindowed(
            final int displayId,
            final Intent sourceIntent,
            final Rect bounds,
            final Object taskAreaToken,
            final boolean stagedReveal,
            final int densityDpi) throws ReflectiveOperationException {
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
                densityDpi,
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
            final boolean stagedReveal,
            final int densityDpi) throws ReflectiveOperationException {
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
        return launchPendingActivityWindowed(
                LaunchActivityIdentity.packageScoped(
                        shortcut.getPackage(), component),
                displayId,
                shortcut.getPackage(),
                pendingIntent,
                bounds,
                taskAreaToken,
                stagedReveal,
                densityDpi,
                false);
    }

    synchronized int launchPendingActivityWindowed(
            final int displayId,
            final String expectedPackage,
            final ComponentName expectedComponent,
            final PendingIntent pendingIntent,
            final Rect bounds,
            final Object taskAreaToken,
            final boolean stagedReveal,
            final int densityDpi) throws ReflectiveOperationException {
        return launchPendingActivityWindowed(
                pendingActivityIdentity(
                        expectedPackage, expectedComponent),
                displayId,
                expectedPackage,
                pendingIntent,
                bounds,
                taskAreaToken,
                stagedReveal,
                densityDpi,
                true);
    }

    private int launchPendingActivityWindowed(
            final LaunchActivityIdentity identity,
            final int displayId,
            final String expectedPackage,
            final PendingIntent pendingIntent,
            final Rect bounds,
            final Object taskAreaToken,
            final boolean stagedReveal,
            final int densityDpi,
            final boolean creatorAuthorized)
            throws ReflectiveOperationException {
        if (displayId < 0 || expectedPackage == null
                || expectedPackage.isEmpty() || pendingIntent == null
                || bounds == null || bounds.isEmpty()) {
            throw new IllegalArgumentException(
                    "windowed pending Activity requires target and bounds");
        }
        return launchPreparedTask(
                identity,
                displayId,
                bounds,
                WINDOWING_MODE_FREEFORM,
                stagedReveal,
                densityDpi,
                taskIdSource -> creatorAuthorized
                        ? TaskDisplayAreaLaunchCommand
                                .launchCreatorAuthorizedPendingIntentTask(
                                        mService,
                                        displayId,
                                        expectedPackage,
                                        pendingIntent,
                                        bounds,
                                        taskAreaToken,
                                        stagedReveal,
                                        taskIdSource)
                        : TaskDisplayAreaLaunchCommand.launchPendingIntentTask(
                                        mService,
                                        displayId,
                                        expectedPackage,
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
        return launchPendingActivityFullscreen(
                LaunchActivityIdentity.packageScoped(
                        shortcut.getPackage(), component),
                displayId,
                shortcut.getPackage(),
                pendingIntent,
                taskAreaToken,
                false);
    }

    synchronized int launchPendingActivityFullscreen(
            final int displayId,
            final String expectedPackage,
            final ComponentName expectedComponent,
            final PendingIntent pendingIntent,
            final Object taskAreaToken) throws ReflectiveOperationException {
        return launchPendingActivityFullscreen(
                pendingActivityIdentity(
                        expectedPackage, expectedComponent),
                displayId,
                expectedPackage,
                pendingIntent,
                taskAreaToken,
                true);
    }

    private int launchPendingActivityFullscreen(
            final LaunchActivityIdentity identity,
            final int displayId,
            final String expectedPackage,
            final PendingIntent pendingIntent,
            final Object taskAreaToken,
            final boolean creatorAuthorized)
            throws ReflectiveOperationException {
        if (displayId < 0 || expectedPackage == null
                || expectedPackage.isEmpty() || pendingIntent == null) {
            throw new IllegalArgumentException(
                    "fullscreen pending Activity requires a target");
        }
        return launchPreparedTask(
                identity,
                displayId,
                new Rect(),
                WINDOWING_MODE_FULLSCREEN,
                false,
                DesktopTaskDensity.UNCHANGED,
                taskIdSource -> creatorAuthorized
                        ? TaskDisplayAreaLaunchCommand
                                .launchFullscreenCreatorAuthorizedPendingIntentTask(
                                        mService,
                                        displayId,
                                        expectedPackage,
                                        pendingIntent,
                                        taskAreaToken,
                                        taskIdSource)
                        : TaskDisplayAreaLaunchCommand
                                .launchFullscreenPendingIntentTask(
                                        mService,
                                        displayId,
                                        expectedPackage,
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
        final ShortcutInfo shortcut = ShellShortcutGateway.require(
                mContext, packageName, shortcutId, user);
        launchPendingActivityInTask(
                displayId,
                taskId,
                LaunchActivityIdentity.packageScoped(
                        packageName,
                        ShellShortcutGateway.targetComponent(shortcut)),
                ShellShortcutGateway.launchIntent(mContext, shortcut),
                false);
    }

    synchronized void launchPendingActivityInTask(
            final int displayId,
            final int taskId,
            final String expectedPackage,
            final ComponentName expectedComponent,
            final PendingIntent pendingIntent)
            throws ReflectiveOperationException {
        final LaunchActivityIdentity identity = pendingActivityIdentity(
                expectedPackage, expectedComponent);
        launchPendingActivityInTask(
                displayId,
                taskId,
                identity,
                pendingIntent,
                true);
    }

    private void launchPendingActivityInTask(
            final int displayId,
            final int taskId,
            final LaunchActivityIdentity identity,
            final PendingIntent pendingIntent,
            final boolean creatorAuthorized)
            throws ReflectiveOperationException {
        final Object task = HiddenTaskApi.findTask(mService, displayId, taskId);
        if (task == null || !identity.matchesPackage(
                HiddenTaskApi.getTaskPackage(task))) {
            throw new IllegalArgumentException(
                    "pending Activity task is unavailable or belongs"
                            + " to another app");
        }
        if (creatorAuthorized) {
            TaskDisplayAreaLaunchCommand
                    .launchCreatorAuthorizedPendingIntentTaskAction(
                            displayId, taskId, pendingIntent);
        } else {
            TaskDisplayAreaLaunchCommand.launchPendingIntentTaskAction(
                    displayId,
                    taskId,
                    pendingIntent,
                    mActivityLauncher);
        }
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
                DesktopTaskDensity.UNCHANGED,
                taskIdSource -> TaskDisplayAreaLaunchCommand
                        .launchFullscreenTask(
                            mService,
                            displayId,
                            intent,
                            intent.getComponent().getPackageName(),
                            taskAreaToken,
                            taskIdSource));
    }

    void onTaskCreated(
            final int taskId,
            final ComponentName componentName) {
        final PendingLaunch pending = mPendingLaunch;
        if (pending != null) {
            pending.onTaskObserved(taskId, componentName, true);
        }
    }

    void onTaskMovedToFront(
            final int taskId,
            final ComponentName componentName) {
        final PendingLaunch pending = mPendingLaunch;
        if (pending != null) {
            pending.onTaskObserved(taskId, componentName, false);
        }
    }

    private int launchPreparedTask(
            final ComponentName component,
            final int displayId,
            final Rect bounds,
            final int windowingMode,
            final boolean stagedReveal,
            final int densityDpi,
            final TaskStarter starter) throws ReflectiveOperationException {
        return launchPreparedTask(
                LaunchActivityIdentity.resolve(mPackageManager, component),
                displayId,
                bounds,
                windowingMode,
                stagedReveal,
                densityDpi,
                starter);
    }

    private int launchPreparedTask(
            final LaunchActivityIdentity identity,
            final int displayId,
            final Rect bounds,
            final int windowingMode,
            final boolean stagedReveal,
            final int densityDpi,
            final TaskStarter starter) throws ReflectiveOperationException {
        final PendingLaunch pending = beginLaunch(
                identity, displayId, bounds, windowingMode);
        int launchedTaskId = -1;
        try {
            final int taskId = starter.start(pending);
            launchedTaskId = taskId;
            pending.complete(taskId, observedComponent(displayId, taskId));
            if (stagedReveal) {
                // A new task has no stable token until the start callback.
                ShellPreparedTaskTransition.revealFreeform(
                        mService,
                        displayId,
                        taskId,
                        bounds,
                        densityDpi);
            }
            // WindowManager may adjust bounds for minimum-size constraints,
            // but the requested mode is part of the launch contract.
            TaskDisplayAreaLaunchCommand.waitForTaskWindowingMode(
                    mService, displayId, taskId, windowingMode);
            final FrameworkTaskSnapshot snapshot =
                    FrameworkTaskSnapshotSource.findTask(
                            mService, displayId, taskId);
            // A relay may already have replaced the top Activity. Launch
            // identity accepts its root transport while this check still owns
            // the complete task contract and any safe rollback.
            final String violation = launchContractViolation(
                    snapshot,
                    pending.mActivityIdentity,
                    displayId,
                    windowingMode);
            if (!violation.isEmpty()) {
                throw new IllegalStateException(violation);
            }
            return taskId;
        } catch (ReflectiveOperationException | RuntimeException error) {
            rollbackFailedLaunch(
                    pending,
                    launchedTaskId >= 0
                            ? launchedTaskId : pending.observedTaskId(),
                    error);
            throw error;
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

    private LaunchActivityIdentity pendingActivityIdentity(
            final String expectedPackage,
            final ComponentName expectedComponent) {
        if (expectedPackage == null || expectedPackage.isEmpty()
                || (expectedComponent != null
                        && !expectedPackage.equals(
                                expectedComponent.getPackageName()))) {
            throw new IllegalArgumentException(
                    "invalid pending Activity identity");
        }
        return expectedComponent == null
                ? LaunchActivityIdentity.packageScoped(expectedPackage, null)
                : LaunchActivityIdentity.resolve(
                        mPackageManager, expectedComponent);
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

    static String launchTopologyViolation(
            final FrameworkTaskSnapshot task,
            final int displayId,
            final int windowingMode) {
        if (task == null) {
            return "launched task is unavailable";
        }
        if (task.displayId != displayId) {
            return "launched task entered display=" + task.displayId
                    + " instead of display=" + displayId;
        }
        if (task.activityType != FrameworkTaskSnapshot.ACTIVITY_TYPE_STANDARD) {
            return "launched task activityType=" + task.activityType
                    + " instead of STANDARD";
        }
        if (task.windowingMode != windowingMode) {
            return "launched task windowingMode=" + task.windowingMode
                    + " instead of mode=" + windowingMode;
        }
        return "";
    }

    static String launchContractViolation(
            final FrameworkTaskSnapshot task,
            final LaunchActivityIdentity identity,
            final int displayId,
            final int windowingMode) {
        final String topology = launchTopologyViolation(
                task, displayId, windowingMode);
        if (!topology.isEmpty()) {
            return topology;
        }
        if (identity == null || !identity.matchesTask(task)) {
            return "launched task identity does not match the request";
        }
        return "";
    }

    static boolean shouldRollbackTask(
            final int taskId,
            final Set<Integer> existingTaskIds,
            final Set<Integer> createdTaskIds) {
        return TaskDisplayAreaLaunchCommand.isFreshTaskId(
                        taskId, existingTaskIds)
                && createdTaskIds != null
                && createdTaskIds.contains(Integer.valueOf(taskId));
    }

    private void rollbackFailedLaunch(
            final PendingLaunch pending,
            final int taskId,
            final Throwable launchError) {
        if (pending.identified(taskId)) {
            mOwnership.forget(taskId);
        }
        if (!pending.canRemove(taskId)) {
            return;
        }
        try {
            if (!TaskControlCommand.removeTask(mService, taskId)) {
                launchError.addSuppressed(new IllegalStateException(
                        "could not remove rejected fresh task=" + taskId));
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            launchError.addSuppressed(error);
        }
    }

    private final class PendingLaunch
            implements TaskDisplayAreaLaunchCommand.TaskIdSource {
        private final LaunchActivityIdentity mActivityIdentity;
        private final int mDisplayId;
        private final Rect mBounds;
        private final int mWindowingMode;
        private int mObservedTaskId = -1;
        private int mIdentifiedTaskId = -1;
        private boolean mIdentified;
        private boolean mPrepared;
        private Set<Integer> mTargetDisplayTaskIds = Collections.emptySet();
        private Set<Integer> mExistingTaskIds = Collections.emptySet();
        private final Set<Integer> mCreatedTaskIds = new HashSet<>();

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
            final ComponentName componentName,
            final boolean created) {
            final boolean matches = mActivityIdentity.matches(componentName);
            if (mPrepared && created) {
                mCreatedTaskIds.add(Integer.valueOf(taskId));
            }
            if (mPrepared
                    && mObservedTaskId < 0
                    && !mTargetDisplayTaskIds.contains(Integer.valueOf(taskId))
                    && matches) {
                mObservedTaskId = taskId;
                notifyAll();
                identify(taskId, componentName);
            }
        }

        synchronized void complete(
                final int taskId,
                final ComponentName componentName) {
            if (!mPrepared) {
                throw new IllegalStateException(
                        "launch task source was not prepared");
            }
            if (mTargetDisplayTaskIds.contains(Integer.valueOf(taskId))) {
                throw new IllegalStateException(
                        "launch returned a task already present on target"
                                + " display: task=" + taskId);
            }
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

        @Override
        public synchronized void onBeforeLaunch(
                final TaskDisplayAreaLaunchCommand.TaskLaunchBaseline baseline) {
            if (baseline == null) {
                throw new IllegalArgumentException(
                        "pre-launch task baseline is required");
            }
            if (mObservedTaskId >= 0) {
                throw new IllegalStateException(
                        "task was observed before launch preparation");
            }
            mTargetDisplayTaskIds = baseline.targetDisplayTaskIds;
            mExistingTaskIds = baseline.allTaskIds;
            mPrepared = true;
        }

        @Override
        public synchronized int awaitTaskId(final long timeoutMillis) {
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

        synchronized int observedTaskId() {
            return mObservedTaskId;
        }

        synchronized boolean identified(final int taskId) {
            return mIdentified && mIdentifiedTaskId == taskId;
        }

        synchronized boolean canRemove(final int taskId) {
            return mPrepared && shouldRollbackTask(
                    taskId, mExistingTaskIds, mCreatedTaskIds);
        }

        private void identify(
                final int taskId,
                final ComponentName observedComponent) {
            if (mIdentified) {
                return;
            }
            mIdentified = true;
            mIdentifiedTaskId = taskId;
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

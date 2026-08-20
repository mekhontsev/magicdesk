package io.github.mekhontsev.magicdesk;

import android.app.ActivityManager;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Owns the desktop session inside a display's default task container. */
final class ShellDesktopTaskArea implements AutoCloseable {
    private static final String TAG = "MagicDeskDesktopArea";
    private static final String HOST_PACKAGE =
            "io.github.mekhontsev.magicdesk";
    private static final String HOST_CLASS = HOST_PACKAGE
            + ".DesktopActivity";
    private static final int FEATURE_DEFAULT_TASK_CONTAINER = 1;
    private static final int ACTIVITY_TYPE_HOME = 2;
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final long HIERARCHY_TIMEOUT_MILLIS = 3_000L;
    private static final long HIERARCHY_POLL_MILLIS = 20L;

    private final Object mService;
    private final ShellDesktopTaskOwnership mOwnership;
    private final ShellWindowedTaskLauncher mWindowedTaskLauncher;
    private final Set<Integer> mTaskIds = new LinkedHashSet<>();

    private TaskDisplayAreaHandle mArea;
    private int mDisplayId = -1;
    private int mHostTaskId = -1;
    private boolean mEnabled;
    private Boolean mAreaAtTop;

    ShellDesktopTaskArea(
            final Object service,
            final ShellDesktopTaskOwnership ownership,
            final ShellWindowedTaskLauncher windowedTaskLauncher) {
        mService = service;
        mOwnership = ownership;
        mWindowedTaskLauncher = windowedTaskLauncher;
    }

    synchronized void configure(
            final int displayId,
            final boolean enabled,
            final int hostTaskId) {
        if (enabled && (displayId < 0 || hostTaskId < 0)) {
            throw new IllegalArgumentException(
                    "managed task area requires a display and host task");
        }
        if (mEnabled == enabled
                && (!enabled || (mDisplayId == displayId
                        && mHostTaskId == hostTaskId))) {
            return;
        }
        releaseTasks();
        if (!enabled) {
            mEnabled = false;
            mDisplayId = -1;
            return;
        }
        mEnabled = true;
        mDisplayId = displayId;
        try {
            ensureArea();
            attachHost(hostTaskId);
        } catch (ReflectiveOperationException | RuntimeException error) {
            releaseTasks();
            mEnabled = false;
            mDisplayId = -1;
            throw new IllegalStateException(
                    "cannot prepare desktop task area", error);
        }
    }

    synchronized int launchHost(
            final int displayId,
            final String intentUri) throws ReflectiveOperationException {
        if (displayId < 0) {
            throw new IllegalArgumentException(
                    "desktop host requires a display");
        }
        releaseTasks();
        mEnabled = true;
        mDisplayId = displayId;
        try {
            ensureArea();
            final Intent intent = TaskDisplayAreaLaunchCommand.createAppIntent(
                    intentUri);
            if (!HOST_PACKAGE.equals(
                    intent.getComponent().getPackageName())
                    || !HOST_CLASS.equals(
                            intent.getComponent().getClassName())) {
                throw new IllegalArgumentException(
                        "invalid desktop host component");
            }
            final int taskId = TaskDisplayAreaLaunchCommand
                    .launchFullscreenTask(
                            mService,
                            displayId,
                            intent,
                            intent.getComponent().getPackageName(),
                            Class.forName(
                                    "android.window.WindowContainerToken"),
                            mArea.token());
            mOwnership.markDesktopHost(taskId);
            attachHost(taskId);
            return taskId;
        } catch (ReflectiveOperationException | RuntimeException error) {
            releaseTasks();
            mEnabled = false;
            mDisplayId = -1;
            throw error;
        }
    }

    synchronized int launch(
            final int displayId,
            final String intentUri,
            final Rect bounds) throws ReflectiveOperationException {
        requireConfigured(displayId, bounds);
        ensureArea();
        final int taskId = mWindowedTaskLauncher.launch(
                displayId, intentUri, bounds, mArea.token());
        mTaskIds.add(Integer.valueOf(taskId));
        waitForTaskArea(taskId, mArea.featureId(), true);
        return taskId;
    }

    synchronized int launchFullscreen(
            final int displayId,
            final String intentUri) throws ReflectiveOperationException {
        requireConfigured(displayId);
        ensureArea();
        final Intent intent = TaskDisplayAreaLaunchCommand.createAppIntent(
                intentUri);
        final int taskId = TaskDisplayAreaLaunchCommand.launchFullscreenTask(
                mService,
                displayId,
                intent,
                intent.getComponent().getPackageName(),
                Class.forName("android.window.WindowContainerToken"),
                mArea.token());
        mOwnership.markDesktop(taskId);
        mTaskIds.add(Integer.valueOf(taskId));
        waitForTaskArea(taskId, mArea.featureId(), true);
        TaskDisplayAreaLaunchCommand.waitForTaskWindowingMode(
                mService,
                displayId,
                taskId,
                WINDOWING_MODE_FULLSCREEN);
        return taskId;
    }

    synchronized boolean manages(final int displayId) {
        return mEnabled && mDisplayId == displayId;
    }

    synchronized boolean matchesConfiguration(
            final int displayId,
            final boolean enabled,
            final int hostTaskId) {
        return mEnabled == enabled
                && (!enabled || (mDisplayId == displayId
                        && mHostTaskId == hostTaskId));
    }

    synchronized int childAreaParentFeatureId(final int displayId) {
        return manages(displayId) && mArea != null
                ? mArea.featureId() : FEATURE_DEFAULT_TASK_CONTAINER;
    }

    synchronized Object childTaskParentToken(final int displayId) {
        return manages(displayId) && mArea != null
                ? mArea.token() : null;
    }

    synchronized void placeTask(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final Rect bounds) throws ReflectiveOperationException {
        requireConfigured(targetDisplayId, bounds);
        placeTaskInArea(
                taskId,
                sourceDisplayId,
                WINDOWING_MODE_FREEFORM,
                bounds,
                false);
        TaskDisplayAreaLaunchCommand.waitForTaskFreeformBounds(
                mService, targetDisplayId, taskId, bounds);
    }

    synchronized void placeFullscreenTask(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId) throws ReflectiveOperationException {
        requireConfigured(targetDisplayId);
        placeTaskInArea(
                taskId,
                sourceDisplayId,
                WINDOWING_MODE_FULLSCREEN,
                new Rect(),
                true);
        TaskDisplayAreaLaunchCommand.waitForTaskWindowingMode(
                mService,
                targetDisplayId,
                taskId,
                WINDOWING_MODE_FULLSCREEN);
    }

    private void placeTaskInArea(
            final int taskId,
            final int sourceDisplayId,
            final int windowingMode,
            final Rect bounds,
            final boolean excludeCaptionInset)
            throws ReflectiveOperationException {
        ensureArea();
        final Object taskToken = HiddenTaskApi.requireTaskToken(
                mService, sourceDisplayId, taskId);
        final Class<?> tokenClass =
                Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass = Class.forName(
                "android.window.WindowContainerTransaction");
        final Object transaction =
                transactionClass.getConstructor().newInstance();
        transactionClass.getMethod(
                "setWindowingMode", tokenClass, Integer.TYPE)
                .invoke(
                        transaction,
                        taskToken,
                        Integer.valueOf(windowingMode));
        transactionClass.getMethod("setBounds", tokenClass, Rect.class)
                .invoke(transaction, taskToken, new Rect(bounds));
        transactionClass.getMethod(
                "setForceTranslucent", tokenClass, Boolean.TYPE)
                .invoke(transaction, taskToken, Boolean.FALSE);
        transactionClass.getMethod(
                "reparent", tokenClass, tokenClass, Boolean.TYPE)
                .invoke(transaction, taskToken, mArea.token(), Boolean.TRUE);
        TaskCaptionInsetsCommand.addCaptionInsetOperation(
                transactionClass,
                transaction,
                tokenClass,
                taskToken,
                excludeCaptionInset);
        // Mark ownership before WMShell can publish the resulting mode or
        // parent change to the long-lived task observer.
        mOwnership.markDesktop(taskId);
        TaskFullscreenTransitionCommand.startTransition(
                transactionClass, transaction);
        mTaskIds.add(Integer.valueOf(taskId));
        waitForTaskArea(taskId, mArea.featureId(), true);
    }

    synchronized void onTaskRemoved(final int taskId) {
        mTaskIds.remove(Integer.valueOf(taskId));
        if (taskId == mHostTaskId) {
            mHostTaskId = -1;
        }
    }

    synchronized void onTaskDisplayChanged(
            final int taskId,
            final int displayId) {
        if (displayId != mDisplayId) {
            onTaskRemoved(taskId);
        }
    }

    synchronized Boolean foregroundForTask(
            final int displayId,
            final int taskId) {
        if (!mEnabled || displayId != mDisplayId || taskId < 0) {
            return null;
        }
        return Boolean.valueOf(taskId == mHostTaskId
                || mTaskIds.contains(Integer.valueOf(taskId)));
    }

    synchronized Boolean foregroundAfterTaskMovedToFront(
            final ActivityManager.RunningTaskInfo taskInfo) {
        if (taskInfo == null) {
            return null;
        }
        final int displayId = HiddenTaskApi.getTaskDisplayId(taskInfo);
        final Boolean foreground = foregroundForTask(
                displayId, taskInfo.taskId);
        if (!Boolean.FALSE.equals(foreground) || !isHomeTask(taskInfo)) {
            return foreground;
        }

        // When the last child finishes, Android can focus HOME as its generic
        // fallback even though our still-visible host owns this session area.
        // Record that the system changed Z-order, but do not publish a desktop
        // departure or start another transition while the child is closing.
        mAreaAtTop = null;
        return null;
    }

    synchronized void setSessionForeground(final boolean foreground)
            throws ReflectiveOperationException {
        if (!mEnabled || mArea == null
                || (mAreaAtTop != null
                        && mAreaAtTop.booleanValue() == foreground)) {
            return;
        }

        final Class<?> tokenClass =
                Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass = Class.forName(
                "android.window.WindowContainerTransaction");
        final Object transaction =
                transactionClass.getConstructor().newInstance();
        // An organizer-created area must remain at an edge of the default
        // task container. Leaving it between ordinary root tasks breaks task
        // traversal assumptions in some ActivityTaskManager implementations.
        transactionClass.getMethod(
                "reorder", tokenClass, Boolean.TYPE)
                .invoke(transaction, mArea.token(), Boolean.valueOf(foreground));
        SyncWindowContainerTransaction.applyAsync(
                mService, transactionClass, transaction);
        mAreaAtTop = Boolean.valueOf(foreground);
        Log.d(TAG, "desktop task area foreground=" + foreground
                + " display=" + mDisplayId);
    }

    private static boolean isHomeTask(
            final ActivityManager.RunningTaskInfo taskInfo) {
        final Intent baseIntent = taskInfo.baseIntent;
        if (baseIntent != null
                && baseIntent.hasCategory(Intent.CATEGORY_HOME)) {
            return true;
        }
        try {
            return HiddenTaskApi.getWindowConfigurationValue(
                    taskInfo, "getActivityType") == ACTIVITY_TYPE_HOME;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not inspect foreground task type", error);
            return false;
        }
    }

    synchronized boolean closeTask(
            final int displayId,
            final int taskId,
            final int focusTaskId) {
        if (!mEnabled || mArea == null || displayId != mDisplayId
                || focusTaskId == taskId) {
            return false;
        }
        try {
            final Object task = HiddenTaskApi.findTask(
                    mService, displayId, taskId);
            final Object focusTask = HiddenTaskApi.findTask(
                    mService, displayId, focusTaskId);
            if (!mOwnership.isDesktopTask(task)
                    || focusTask == null
                    || (focusTaskId != mHostTaskId
                            && !mOwnership.isDesktopTask(focusTask))) {
                return false;
            }

            // Keep the handoff and removal in one WMShell transition. Two
            // transactions can overlap and make SystemUI animate the close
            // to HOME even after the desktop host became foreground.
            TaskWindowingCommand.closeDesktopTask(
                    mService, displayId, taskId, focusTaskId);
            Log.i(TAG, "closed desktop task=" + taskId
                    + " survivor=" + focusTaskId
                    + " display=" + displayId);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "desktop close handoff failed task="
                    + taskId, error);
            return false;
        }
    }

    synchronized boolean removePackageTasks(
            final int displayId,
            final String packageName,
            final int focusTaskId) {
        if (!mEnabled || mArea == null || displayId != mDisplayId
                || !PackageNameValidator.isSafe(packageName)) {
            return false;
        }
        try {
            final Object focusTask = HiddenTaskApi.findTask(
                    mService, displayId, focusTaskId);
            if (focusTask == null
                    || (focusTaskId != mHostTaskId
                            && !mOwnership.isDesktopTask(focusTask))) {
                return false;
            }

            final List<Integer> removedTaskIds = new ArrayList<>();
            for (final Integer taskId : mTaskIds) {
                if (taskId == null || taskId.intValue() == focusTaskId) {
                    continue;
                }
                final Object task = HiddenTaskApi.findTask(
                        mService, displayId, taskId.intValue());
                if (task != null
                        && mOwnership.isDesktopTask(task)
                        && packageName.equals(HiddenTaskApi.getTaskPackage(task))) {
                    removedTaskIds.add(taskId);
                }
            }
            if (removedTaskIds.isEmpty()) {
                return false;
            }

            final int[] taskIds = new int[removedTaskIds.size()];
            for (int index = 0; index < removedTaskIds.size(); index++) {
                taskIds[index] = removedTaskIds.get(index).intValue();
            }
            // Use the same WMShell close transition as an ordinary task close.
            // A synchronous organizer removal makes SystemUI launch HOME on
            // some firmware even when the host is focused in that transaction.
            // The package action already originates inside this session area,
            // so raising the survivor's parents is unnecessary and can place
            // this child area between ordinary root tasks on vendor firmware.
            TaskWindowingCommand.closeDesktopTasks(
                    mService,
                    displayId,
                    taskIds,
                    focusTaskId,
                    false);
            waitForTasksRemoved(removedTaskIds);
            mTaskIds.removeAll(removedTaskIds);
            Log.i(TAG, "removed desktop package tasks=" + removedTaskIds
                    + " survivor=" + focusTaskId + " display=" + displayId);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "desktop package task removal failed package="
                    + packageName + " survivor=" + focusTaskId, error);
            return false;
        }
    }

    synchronized void removeOrphanedTransientTasks(
            final int displayId,
            final List<?> tasks) {
        if (!mEnabled || displayId != mDisplayId || tasks == null) {
            return;
        }
        for (final Object task : tasks) {
            if (!(task instanceof ActivityManager.RunningTaskInfo)) {
                continue;
            }
            final ActivityManager.RunningTaskInfo taskInfo =
                    (ActivityManager.RunningTaskInfo) task;
            if (!mTaskIds.contains(Integer.valueOf(taskInfo.taskId))) {
                continue;
            }
            try {
                final Object topActivityInfo = HiddenTaskApi.getField(
                        taskInfo, "topActivityInfo");
                if (!(topActivityInfo instanceof ActivityInfo)
                        || !OrphanedTransientTaskPolicy.shouldRemove(
                                taskInfo, (ActivityInfo) topActivityInfo)) {
                    continue;
                }
                // A crashed requester can leave its excluded result UI as the
                // task's sole activity, causing WMS to rebuild a dead input sink.
                final boolean removed = TaskControlCommand.removeTask(
                        mService, taskInfo.taskId);
                Log.i(TAG, "removed orphaned transient task="
                        + taskInfo.taskId + " result=" + removed);
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.w(TAG, "could not remove orphaned transient task="
                        + taskInfo.taskId, error);
            }
        }
    }

    @Override
    public synchronized void close() {
        releaseTasks();
        mEnabled = false;
        mDisplayId = -1;
    }

    private void requireConfigured(
            final int displayId,
            final Rect bounds) {
        requireConfigured(displayId);
        if (bounds == null || bounds.isEmpty()) {
            throw new IllegalArgumentException("invalid task bounds");
        }
    }

    private void requireConfigured(final int displayId) {
        if (!mEnabled || displayId != mDisplayId) {
            throw new IllegalStateException(
                    "desktop task area is not configured for display "
                            + displayId);
        }
        if (mHostTaskId < 0) {
            throw new IllegalStateException(
                    "desktop task area has no host task");
        }
    }

    private void ensureArea() throws ReflectiveOperationException {
        if (mArea == null) {
            mArea = TaskDisplayAreaHandle.create(
                    mDisplayId,
                    FEATURE_DEFAULT_TASK_CONTAINER,
                    "MagicDesk desktop session");
            Log.i(TAG, "created desktop task area display=" + mDisplayId);
        }
    }

    private void attachHost(final int hostTaskId)
            throws ReflectiveOperationException {
        ensureArea();
        // Track the task before changing hierarchy so cleanup can recover it
        // even when a later transaction operation fails.
        mHostTaskId = hostTaskId;
        final Class<?> tokenClass =
                Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass = Class.forName(
                "android.window.WindowContainerTransaction");
        final Object transaction =
                transactionClass.getConstructor().newInstance();
        final Object hostToken = HiddenTaskApi.requireTaskToken(
                mService, mDisplayId, hostTaskId);
        transactionClass.getMethod(
                "setWindowingMode", tokenClass, Integer.TYPE)
                .invoke(
                        transaction,
                        hostToken,
                        Integer.valueOf(WINDOWING_MODE_FULLSCREEN));
        transactionClass.getMethod("setBounds", tokenClass, Rect.class)
                .invoke(transaction, hostToken, new Rect());
        transactionClass.getMethod(
                "setForceTranslucent", tokenClass, Boolean.TYPE)
                .invoke(transaction, hostToken, Boolean.FALSE);
        transactionClass.getMethod(
                "reparent", tokenClass, tokenClass, Boolean.TYPE)
                .invoke(transaction, hostToken, mArea.token(), Boolean.FALSE);
        TaskCaptionInsetsCommand.addCaptionInsetOperation(
                transactionClass,
                transaction,
                tokenClass,
                hostToken,
                true);
        // Keep the complete session plane above existing application tasks
        // inside the default task container. SystemUI can then add transient
        // task-decoration surfaces above this child area.
        transactionClass.getMethod(
                "reorder", tokenClass, Boolean.TYPE)
                .invoke(transaction, mArea.token(), Boolean.TRUE);
        SyncWindowContainerTransaction.applyAsync(
                mService, transactionClass, transaction);
        mAreaAtTop = Boolean.TRUE;
        waitForTaskArea(hostTaskId, mArea.featureId(), true);
        Log.i(TAG, "attached desktop host task=" + hostTaskId
                + " display=" + mDisplayId);
    }

    private void releaseTasks() {
        final TaskDisplayAreaHandle area = mArea;
        if (area == null) {
            mAreaAtTop = null;
            mTaskIds.clear();
            mHostTaskId = -1;
            return;
        }
        try {
            final Set<Integer> ownedTaskIds = new LinkedHashSet<>(mTaskIds);
            if (mHostTaskId >= 0) {
                ownedTaskIds.add(Integer.valueOf(mHostTaskId));
            }
            final Set<Integer> childTaskIds = findOwnedChildTaskIds(
                    area.featureId(), ownedTaskIds);
            normalizeChildTasks(childTaskIds);
            // Keep mode changes separate from hierarchy changes. Combining
            // them can make vendor WMS compare a task against an area whose
            // parent has already changed within the same transaction.
            area.detachChildTasks(mService, mDisplayId, childTaskIds);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not release desktop task area", error);
        } finally {
            mArea = null;
            mAreaAtTop = null;
            mTaskIds.clear();
            mHostTaskId = -1;
            if (!area.closeIfEmpty(mService, mDisplayId)) {
                Log.w(TAG, "desktop task area retained after unsafe cleanup"
                        + " feature=" + area.featureId());
            }
        }
    }

    private Set<Integer> findOwnedChildTaskIds(
            final int featureId,
            final Set<Integer> ownedTaskIds)
            throws ReflectiveOperationException {
        final Set<Integer> childTaskIds = new LinkedHashSet<>();
        for (final Object task : HiddenTaskApi.getTasks(mService, mDisplayId)) {
            final Integer taskId = Integer.valueOf(
                    HiddenTaskApi.getIntField(task, "taskId"));
            if (ownedTaskIds.contains(taskId)
                    && HiddenTaskApi.getIntField(
                            task, "displayAreaFeatureId") == featureId) {
                childTaskIds.add(taskId);
            }
        }
        return childTaskIds;
    }

    private void normalizeChildTasks(final Set<Integer> childTaskIds)
            throws ReflectiveOperationException {
        if (childTaskIds.isEmpty()) {
            return;
        }
        final Class<?> tokenClass =
                Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass = Class.forName(
                "android.window.WindowContainerTransaction");
        final Object transaction =
                transactionClass.getConstructor().newInstance();
        for (final Integer taskId : childTaskIds) {
            final Object taskToken = HiddenTaskApi.requireTaskToken(
                    mService, mDisplayId, taskId.intValue());
            transactionClass.getMethod(
                    "setWindowingMode", tokenClass, Integer.TYPE)
                    .invoke(
                            transaction,
                            taskToken,
                            Integer.valueOf(WINDOWING_MODE_FULLSCREEN));
            transactionClass.getMethod(
                    "setBounds", tokenClass, Rect.class)
                    .invoke(transaction, taskToken, new Rect());
            transactionClass.getMethod(
                    "setForceTranslucent", tokenClass, Boolean.TYPE)
                    .invoke(transaction, taskToken, Boolean.FALSE);
            TaskCaptionInsetsCommand.addCaptionInsetOperation(
                    transactionClass,
                    transaction,
                    tokenClass,
                    taskToken,
                    true);
        }
        SyncWindowContainerTransaction.apply(
                mService, transactionClass, transaction);
    }

    private void waitForTaskArea(
            final int taskId,
            final int featureId,
            final boolean expectedInside)
            throws ReflectiveOperationException {
        final long deadline = SystemClock.uptimeMillis()
                + HIERARCHY_TIMEOUT_MILLIS;
        int observedFeatureId = Integer.MIN_VALUE;
        do {
            final Object task = HiddenTaskApi.findTask(
                    mService, mDisplayId, taskId);
            if (task == null) {
                if (!expectedInside) {
                    return;
                }
            } else {
                observedFeatureId = HiddenTaskApi.getIntField(
                        task, "displayAreaFeatureId");
                if ((observedFeatureId == featureId) == expectedInside) {
                    return;
                }
            }
            SystemClock.sleep(HIERARCHY_POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IllegalStateException(
                "task " + taskId + " did not reach display area "
                        + featureId + "; observed=" + observedFeatureId);
    }

    private void waitForTasksRemoved(final List<Integer> taskIds)
            throws ReflectiveOperationException {
        final long deadline = SystemClock.uptimeMillis()
                + HIERARCHY_TIMEOUT_MILLIS;
        do {
            boolean allRemoved = true;
            for (final Integer taskId : taskIds) {
                if (HiddenTaskApi.findTask(
                        mService, mDisplayId, taskId.intValue()) != null) {
                    allRemoved = false;
                    break;
                }
            }
            if (allRemoved) {
                return;
            }
            SystemClock.sleep(HIERARCHY_POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IllegalStateException(
                "desktop package tasks were not removed: " + taskIds);
    }

}

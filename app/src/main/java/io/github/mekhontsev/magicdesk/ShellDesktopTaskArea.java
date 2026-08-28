package io.github.mekhontsev.magicdesk;

import android.app.ActivityManager;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
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
    private static final int ACTIVITY_TYPE_STANDARD = 1;
    private static final int ACTIVITY_TYPE_HOME = 2;
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final long HIERARCHY_TIMEOUT_MILLIS = 3_000L;
    private static final long HIERARCHY_POLL_MILLIS = 20L;

    private final Object mService;
    private final ShellDesktopTaskOwnership mOwnership;
    private final ShellTaskLauncher mTaskLauncher;
    private final Set<Integer> mTaskIds = new LinkedHashSet<>();

    private TaskDisplayAreaHandle mHostArea;
    private TaskDisplayAreaHandle mWorkspaceArea;
    private int mHostBackstopTaskId = -1;
    private int mWorkspaceBackstopTaskId = -1;
    private int mDisplayId = -1;
    private int mHostTaskId = -1;
    private boolean mEnabled;
    private Boolean mAreaAtTop;
    private DesktopTaskAreaPolicy mTaskAreaPolicy =
            DesktopTaskAreaPolicy.DEFAULT;

    ShellDesktopTaskArea(
            final Object service,
            final ShellDesktopTaskOwnership ownership,
            final ShellTaskLauncher taskLauncher) {
        mService = service;
        mOwnership = ownership;
        mTaskLauncher = taskLauncher;
    }

    synchronized void configure(
            final int displayId,
            final DesktopTaskAreaPolicy taskAreaPolicy,
            final int hostTaskId) {
        if (taskAreaPolicy == null) {
            throw new IllegalArgumentException(
                    "desktop task-area policy is required");
        }
        final boolean enabled = taskAreaPolicy.usesManagedWorkspaceArea();
        if (enabled && (displayId < 0 || hostTaskId < 0)) {
            throw new IllegalArgumentException(
                    "managed task area requires a display and host task");
        }
        if (mEnabled == enabled
                && (!enabled || (mDisplayId == displayId
                        && mHostTaskId == hostTaskId
                        && mTaskAreaPolicy == taskAreaPolicy))) {
            return;
        }
        releaseTasks();
        if (!enabled) {
            mEnabled = false;
            mDisplayId = -1;
            mTaskAreaPolicy = DesktopTaskAreaPolicy.DEFAULT;
            return;
        }
        mEnabled = true;
        mDisplayId = displayId;
        mTaskAreaPolicy = taskAreaPolicy;
        try {
            ensureAreas();
            attachHost(hostTaskId);
        } catch (ReflectiveOperationException | RuntimeException error) {
            releaseTasks();
            mEnabled = false;
            mDisplayId = -1;
            mTaskAreaPolicy = DesktopTaskAreaPolicy.DEFAULT;
            throw new IllegalStateException(
                    "cannot prepare desktop task area", error);
        }
    }

    synchronized int launchHost(
            final int displayId,
            final String intentUri,
            final DesktopTaskAreaPolicy taskAreaPolicy)
            throws ReflectiveOperationException {
        if (displayId < 0) {
            throw new IllegalArgumentException(
                    "desktop host requires a display");
        }
        if (taskAreaPolicy == null
                || !taskAreaPolicy.usesManagedWorkspaceArea()) {
            throw new IllegalArgumentException(
                    "desktop host requires a managed task-area policy");
        }
        final Intent intent = TaskDisplayAreaLaunchCommand.createAppIntent(
                intentUri);
        if (!HOST_PACKAGE.equals(
                intent.getComponent().getPackageName())
                || !HOST_CLASS.equals(
                        intent.getComponent().getClassName())) {
            throw new IllegalArgumentException(
                    "invalid desktop host component");
        }
        final List<Integer> existingHostTaskIds =
                findDesktopHostTaskIds(displayId);
        if (hasManagedHost(
                displayId, existingHostTaskIds, taskAreaPolicy)) {
            mOwnership.markDesktopHost(mHostTaskId);
            return mHostTaskId;
        }

        releaseTasks();
        removeStaleHostTasks(existingHostTaskIds);
        mEnabled = true;
        mDisplayId = displayId;
        mTaskAreaPolicy = taskAreaPolicy;
        try {
            ensureAreas();
            final int taskId = TaskDisplayAreaLaunchCommand
                    .launchFullscreenTask(
                            mService,
                            displayId,
                            intent,
                            intent.getComponent().getPackageName(),
                            mHostArea.token());
            mOwnership.markDesktopHost(taskId);
            attachHost(taskId);
            return taskId;
        } catch (ReflectiveOperationException | RuntimeException error) {
            releaseTasks();
            mEnabled = false;
            mDisplayId = -1;
            mTaskAreaPolicy = DesktopTaskAreaPolicy.DEFAULT;
            throw error;
        }
    }

    private boolean hasManagedHost(
            final int displayId,
            final List<Integer> existingHostTaskIds,
            final DesktopTaskAreaPolicy taskAreaPolicy)
            throws ReflectiveOperationException {
        if (!mEnabled || mDisplayId != displayId || mHostArea == null
                || mTaskAreaPolicy != taskAreaPolicy
                || mHostTaskId < 0
                || !existingHostTaskIds.contains(
                        Integer.valueOf(mHostTaskId))) {
            return false;
        }
        final Object task = HiddenTaskApi.findTask(
                mService, displayId, mHostTaskId);
        return task != null
                && HiddenTaskApi.getTaskDisplayAreaFeatureId(task)
                        == mHostArea.featureId();
    }

    private List<Integer> findDesktopHostTaskIds(final int displayId)
            throws ReflectiveOperationException {
        final List<Integer> taskIds = new ArrayList<>();
        for (final Object task : HiddenTaskApi.getTasks(mService, displayId)) {
            final android.content.ComponentName topActivity =
                    HiddenTaskApi.getTaskTopActivity(task);
            final android.content.ComponentName baseActivity =
                    HiddenTaskApi.getTaskBaseActivity(task);
            if (isDesktopHostComponent(topActivity)
                    || isDesktopHostComponent(baseActivity)) {
                taskIds.add(Integer.valueOf(HiddenTaskApi.getTaskId(task)));
            }
        }
        return taskIds;
    }

    private static boolean isDesktopHostComponent(
            final android.content.ComponentName component) {
        return component != null
                && HOST_PACKAGE.equals(component.getPackageName())
                && HOST_CLASS.equals(component.getClassName());
    }

    private void removeStaleHostTasks(final List<Integer> taskIds)
            throws ReflectiveOperationException {
        for (final Integer taskId : taskIds) {
            if (!TaskControlCommand.removeTask(
                    mService, taskId.intValue())) {
                throw new IllegalStateException(
                        "cannot remove stale desktop host task=" + taskId);
            }
        }
    }

    synchronized int launch(
            final int displayId,
            final String intentUri,
            final Rect bounds) throws ReflectiveOperationException {
        requireConfigured(displayId, bounds);
        ensureAreas();
        final TaskDisplayAreaHandle applicationArea = applicationArea();
        final int taskId = mTaskLauncher.launchWindowed(
                displayId, intentUri, bounds, applicationArea.token());
        mTaskIds.add(Integer.valueOf(taskId));
        waitForTaskArea(taskId, applicationArea.featureId(), true);
        return taskId;
    }

    synchronized int launchFullscreen(
            final int displayId,
            final String intentUri) throws ReflectiveOperationException {
        requireConfigured(displayId);
        ensureAreas();
        final TaskDisplayAreaHandle applicationArea = applicationArea();
        final int taskId = mTaskLauncher.launchFullscreen(
                displayId, intentUri, applicationArea.token());
        mTaskIds.add(Integer.valueOf(taskId));
        waitForTaskArea(taskId, applicationArea.featureId(), true);
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

    synchronized int managedDisplayId() {
        return mEnabled ? mDisplayId : -1;
    }

    synchronized boolean matchesConfiguration(
            final int displayId,
            final DesktopTaskAreaPolicy taskAreaPolicy,
            final int hostTaskId) {
        final boolean enabled = taskAreaPolicy != null
                && taskAreaPolicy.usesManagedWorkspaceArea();
        return mEnabled == enabled
                && (!enabled || (mDisplayId == displayId
                        && mHostTaskId == hostTaskId
                        && mTaskAreaPolicy == taskAreaPolicy));
    }

    synchronized int fullscreenAreaParentFeatureId() {
        // Keep organizer-created areas as siblings. Nesting the fullscreen
        // area under the phone session area leaves invalid parent links on
        // affected WindowManager implementations when the child is deleted.
        return FEATURE_DEFAULT_TASK_CONTAINER;
    }

    synchronized Object fullscreenTaskReleaseParentToken(
            final int displayId) {
        final TaskDisplayAreaHandle applicationArea = applicationAreaOrNull();
        return manages(displayId) && applicationArea != null
                ? applicationArea.token() : null;
    }

    synchronized Object fullscreenTaskHostParentToken(
            final int displayId) {
        return manages(displayId) && mHostArea != null
                ? mHostArea.token() : null;
    }

    private TaskDisplayAreaHandle applicationArea() {
        final TaskDisplayAreaHandle area = applicationAreaOrNull();
        if (area == null) {
            throw new IllegalStateException(
                    "desktop application area is unavailable");
        }
        return area;
    }

    private TaskDisplayAreaHandle applicationAreaOrNull() {
        return mTaskAreaPolicy.usesIndependentFullscreenPlanes()
                ? mWorkspaceArea : mHostArea;
    }

    private boolean isManagedAreaFeature(final int featureId) {
        return mHostArea != null && mHostArea.featureId() == featureId
                || mWorkspaceArea != null
                        && mWorkspaceArea.featureId() == featureId;
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
        ensureAreas();
        final TaskDisplayAreaHandle applicationArea = applicationArea();
        final Object taskToken = HiddenTaskApi.requireTaskToken(
                mService, sourceDisplayId, taskId);
        final FrameworkWindowingApi windowing =
                FrameworkRuntime.current().windowing();
        final Class<?> transactionClass = windowing.transactionClass();
        final Object transaction = windowing.newTransaction();
        windowing.setWindowingMode(transaction, taskToken, windowingMode);
        windowing.setBounds(transaction, taskToken, new Rect(bounds));
        windowing.setForceTranslucent(transaction, taskToken, false);
        windowing.reparent(
                transaction, taskToken, applicationArea.token(), true);
        if (mTaskAreaPolicy.usesIndependentFullscreenPlanes()) {
            windowing.reorder(transaction, applicationArea.token(), true);
        }
        TaskCaptionInsetsCommand.addCaptionInsetOperation(
                transaction,
                taskToken,
                excludeCaptionInset);
        // Mark ownership before WMShell can publish the resulting mode or
        // parent change to the long-lived task observer.
        mOwnership.markDesktop(taskId);
        ShellWindowTransitionExecutor.startForShellAdoption(
                mDisplayId,
                ShellWindowTransitionExecutor.SystemTransition.CHANGE,
                transactionClass,
                transaction,
                "place-task-in-session-area");
        mTaskIds.add(Integer.valueOf(taskId));
        waitForTaskArea(taskId, applicationArea.featureId(), true);
    }

    synchronized void onTaskRemoved(final int taskId) {
        mTaskIds.remove(Integer.valueOf(taskId));
        if (taskId == mHostBackstopTaskId) {
            mHostBackstopTaskId = -1;
        }
        if (taskId == mWorkspaceBackstopTaskId) {
            mWorkspaceBackstopTaskId = -1;
        }
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
        if (taskId == mHostTaskId) {
            return Boolean.TRUE;
        }
        if (!mTaskIds.contains(Integer.valueOf(taskId))
                || mHostArea == null) {
            return Boolean.FALSE;
        }
        try {
            final Object task = HiddenTaskApi.findTask(
                    mService, displayId, taskId);
            return Boolean.valueOf(task != null
                    && isManagedAreaFeature(
                            HiddenTaskApi.getTaskDisplayAreaFeatureId(task)));
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not inspect desktop task parent task="
                    + taskId, error);
            return null;
        }
    }

    synchronized void commitWorkspaceSurfaceForTask(
            final int displayId,
            final int taskId) throws ReflectiveOperationException {
        if (!mEnabled || displayId != mDisplayId
                || taskId < 0 || mWorkspaceArea == null) {
            return;
        }
        final Object task = HiddenTaskApi.requireTask(
                mService, displayId, taskId);
        final boolean workspaceForeground = taskId != mHostTaskId
                && HiddenTaskApi.getTaskDisplayAreaFeatureId(task)
                        == mWorkspaceArea.featureId();
        final Class<?> surfaceClass = Class.forName(
                "android.view.SurfaceControl");
        final Class<?> transactionClass = Class.forName(
                "android.view.SurfaceControl$Transaction");
        final Object transaction =
                transactionClass.getConstructor().newInstance();
        try {
            // WMS can commit task focus while retaining the organizer
            // leash's previous Z-order. Reassert only the freeform overlay;
            // fullscreen planes keep their independent ordering model.
            transactionClass.getMethod(
                    "setLayer", surfaceClass, Integer.TYPE)
                    .invoke(
                            transaction,
                            mWorkspaceArea.surfaceLeash(),
                            Integer.valueOf(workspaceForeground
                                    ? Integer.MAX_VALUE
                                    : Integer.MIN_VALUE));
            transactionClass.getMethod("apply").invoke(transaction);
        } finally {
            transactionClass.getMethod("close").invoke(transaction);
        }
    }

    synchronized Boolean foregroundAfterTaskMovedToFront(
            final ActivityManager.RunningTaskInfo taskInfo) {
        if (taskInfo == null) {
            return null;
        }
        final int displayId = HiddenTaskApi.getTaskDisplayId(taskInfo);
        final Boolean foreground;
        if (!mEnabled || displayId != mDisplayId || mHostArea == null) {
            foreground = null;
        } else if (taskInfo.taskId == mHostTaskId) {
            foreground = Boolean.TRUE;
        } else if (!mTaskIds.contains(Integer.valueOf(taskInfo.taskId))) {
            foreground = Boolean.FALSE;
        } else {
            try {
                foreground = Boolean.valueOf(isManagedAreaFeature(
                        HiddenTaskApi.getTaskDisplayAreaFeatureId(taskInfo)));
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.w(TAG, "could not inspect foreground task parent task="
                        + taskInfo.taskId, error);
                return null;
            }
        }
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
        if (!mEnabled || mHostArea == null
                || (mAreaAtTop != null
                        && mAreaAtTop.booleanValue() == foreground)) {
            return;
        }

        if (mTaskAreaPolicy.usesIndependentFullscreenPlanes()) {
            // Independent workspace commands already commit the order of the
            // host, freeform overlay, and fullscreen planes atomically. Task
            // callbacks only publish that committed state; replaying a broad
            // area reorder here would place the opaque host over fullscreen.
            mAreaAtTop = Boolean.valueOf(foreground);
            return;
        }

        final FrameworkWindowingApi windowing =
                FrameworkRuntime.current().windowing();
        final Class<?> transactionClass = windowing.transactionClass();
        final Object transaction = windowing.newTransaction();
        // An organizer-created area must remain at an edge of the default
        // task container. Leaving it between ordinary root tasks breaks task
        // traversal assumptions in some ActivityTaskManager implementations.
        windowing.reorder(transaction, mHostArea.token(), foreground);
        ShellWindowTransitionExecutor.applyAtomic(
                mService, transactionClass, transaction);
        mAreaAtTop = Boolean.valueOf(foreground);
        Log.d(TAG, "desktop task area foreground=" + foreground
                + " display=" + mDisplayId);
    }

    synchronized void noteCommittedSessionForeground(
            final boolean foreground) {
        if (mEnabled && mHostArea != null) {
            mAreaAtTop = Boolean.valueOf(foreground);
        }
    }

    private static boolean isHomeTask(
            final ActivityManager.RunningTaskInfo taskInfo) {
        final Intent baseIntent = taskInfo.baseIntent;
        if (baseIntent != null
                && baseIntent.hasCategory(Intent.CATEGORY_HOME)) {
            return true;
        }
        try {
            return HiddenTaskApi.getTaskActivityType(taskInfo) == ACTIVITY_TYPE_HOME;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not inspect foreground task type", error);
            return false;
        }
    }

    synchronized boolean closeTask(
            final int displayId,
            final int taskId,
            final int focusTaskId) {
        if (!mEnabled || mHostArea == null || displayId != mDisplayId
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
        if (!mEnabled || mHostArea == null || displayId != mDisplayId
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
                final Object topActivityInfo =
                        HiddenTaskApi.getTaskTopActivityInfo(taskInfo);
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
        mTaskAreaPolicy = DesktopTaskAreaPolicy.DEFAULT;
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

    private void ensureAreas() throws ReflectiveOperationException {
        if (mHostArea != null) {
            return;
        }
        final TaskDisplayAreaHandle hostArea = createArea(
                "MagicDesk desktop host",
                mTaskAreaPolicy.usesSessionParent() ? "session" : "host",
                ACTIVITY_TYPE_HOME);
        TaskDisplayAreaHandle workspaceArea = null;
        int workspaceBackstopTaskId = -1;
        try {
            if (mTaskAreaPolicy.usesIndependentFullscreenPlanes()) {
                workspaceArea = TaskDisplayAreaHandle.createSurfaceOrdered(
                        mDisplayId,
                        FEATURE_DEFAULT_TASK_CONTAINER,
                        "MagicDesk freeform overlay");
                workspaceArea.setIgnoreOrientationRequest(mService, true);
                workspaceBackstopTaskId = launchAreaBackstop(
                        workspaceArea,
                        "overlay",
                        ACTIVITY_TYPE_STANDARD);
                final FrameworkWindowingApi windowing =
                        FrameworkRuntime.current().windowing();
                final Class<?> transactionClass =
                        windowing.transactionClass();
                final Object transaction = windowing.newTransaction();
                windowing.reorder(transaction, hostArea.token(), true);
                windowing.reorder(transaction, workspaceArea.token(), true);
                ShellWindowTransitionExecutor.applyAtomic(
                        mService, transactionClass, transaction);
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (workspaceArea != null) {
                workspaceArea.closeIfOnlyOwnedChildren(
                        mService,
                        mDisplayId,
                        workspaceBackstopTaskId < 0
                                ? Collections.<Integer>emptySet()
                                : Collections.singleton(Integer.valueOf(
                                        workspaceBackstopTaskId)));
            }
            hostArea.closeIfOnlyOwnedChildren(
                    mService,
                    mDisplayId,
                    Collections.singleton(
                            Integer.valueOf(mHostBackstopTaskId)));
            mHostBackstopTaskId = -1;
            throw error;
        }
        mHostArea = hostArea;
        mWorkspaceArea = workspaceArea;
        mWorkspaceBackstopTaskId = workspaceBackstopTaskId;
        Log.i(TAG, "created desktop task areas display=" + mDisplayId
                + " policy=" + mTaskAreaPolicy);
    }

    private TaskDisplayAreaHandle createArea(
            final String name,
            final String instanceKind,
            final int activityType) throws ReflectiveOperationException {
        final TaskDisplayAreaHandle area = TaskDisplayAreaHandle.create(
                mDisplayId,
                FEATURE_DEFAULT_TASK_CONTAINER,
                name);
        int backstopTaskId = -1;
        try {
            // A desktop is one stable viewport. Individual activities may
            // adapt their own content, but must not rotate sibling planes.
            area.setIgnoreOrientationRequest(mService, true);
            backstopTaskId = launchAreaBackstop(
                    area, instanceKind, activityType);
            mHostBackstopTaskId = backstopTaskId;
            return area;
        } catch (ReflectiveOperationException | RuntimeException error) {
            area.closeIfOnlyOwnedChildren(
                    mService,
                    mDisplayId,
                    backstopTaskId < 0
                            ? Collections.<Integer>emptySet()
                            : Collections.singleton(
                                    Integer.valueOf(backstopTaskId)));
            throw error;
        }
    }

    private int launchAreaBackstop(
            final TaskDisplayAreaHandle area,
            final String instanceKind,
            final int activityType) throws ReflectiveOperationException {
        final int taskId = activityType == ACTIVITY_TYPE_HOME
                ? TaskDisplayAreaLaunchCommand.launchFullscreenTask(
                        mService,
                        mDisplayId,
                        TaskAreaBackstopActivity.createIntent(
                                instanceKind + ':' + mDisplayId + ':'
                                        + area.featureId()),
                        BuildConfig.APPLICATION_ID,
                        area.token(),
                        activityType)
                : TaskDisplayAreaLaunchCommand.launchFullscreenTaskBehind(
                        mService,
                        mDisplayId,
                        TaskAreaBackstopActivity.createIntent(
                                instanceKind + ':' + mDisplayId + ':'
                                        + area.featureId()),
                        BuildConfig.APPLICATION_ID,
                        area.token(),
                        activityType);
        final Object backstop = HiddenTaskApi.requireTask(
                mService, mDisplayId, taskId);
        if (!TaskAreaBackstopActivity.isBackstopComponent(
                        HiddenTaskApi.getTaskComponent(backstop))
                || HiddenTaskApi.getTaskDisplayAreaFeatureId(backstop)
                        != area.featureId()) {
            throw new IllegalStateException(
                    "structural task did not enter area="
                            + area.featureId());
        }
        final FrameworkWindowingApi windowing =
                FrameworkRuntime.current().windowing();
        final Class<?> transactionClass = windowing.transactionClass();
        final Object transaction = windowing.newTransaction();
        // Host and overlay anchors are structural only. If their tasks remain
        // focusable, ATMS can select an inert anchor after the last app closes,
        // leaving the next app with task focus but no InputDispatcher target.
        windowing.setFocusable(
                transaction,
                HiddenTaskApi.getTaskToken(backstop),
                false);
        if (activityType == ACTIVITY_TYPE_STANDARD) {
            // The workspace anchor keeps its organizer area alive, but a
            // visible fullscreen task still owns an ActivityRecord input sink
            // even when its window is not touchable. Hide the structural task
            // so empty desktop space targets the real desktop host below it.
            windowing.setHidden(
                    transaction,
                    HiddenTaskApi.getTaskToken(backstop),
                    true);
        }
        ShellWindowTransitionExecutor.applyAtomic(
                mService, transactionClass, transaction);
        return taskId;
    }

    private void attachHost(final int hostTaskId)
            throws ReflectiveOperationException {
        ensureAreas();
        // Track the task before changing hierarchy so cleanup can recover it
        // even when a later transaction operation fails.
        mHostTaskId = hostTaskId;
        final FrameworkWindowingApi windowing =
                FrameworkRuntime.current().windowing();
        final Class<?> transactionClass = windowing.transactionClass();
        final Object transaction = windowing.newTransaction();
        final Object hostToken = HiddenTaskApi.requireTaskToken(
                mService, mDisplayId, hostTaskId);
        windowing.setWindowingMode(
                transaction, hostToken, WINDOWING_MODE_FULLSCREEN);
        windowing.setBounds(transaction, hostToken, new Rect());
        windowing.setForceTranslucent(transaction, hostToken, false);
        // The structural HOME task already occupies the bottom of the new
        // area. Place the desktop host above it so neither the backstop task
        // surface nor its input sink can cover the desktop between windows.
        windowing.reparent(transaction, hostToken, mHostArea.token(), true);
        TaskCaptionInsetsCommand.addCaptionInsetOperation(
                transaction,
                hostToken,
                true);
        // Keep the complete session plane above existing application tasks
        // inside the default task container. SystemUI can then add transient
        // task-decoration surfaces above this child area.
        windowing.reorder(transaction, mHostArea.token(), true);
        if (mWorkspaceArea != null) {
            windowing.reorder(transaction, mWorkspaceArea.token(), true);
        }
        ShellWindowTransitionExecutor.applyAtomic(
                mService, transactionClass, transaction);
        mAreaAtTop = Boolean.TRUE;
        waitForTaskArea(hostTaskId, mHostArea.featureId(), true);
        Log.i(TAG, "attached desktop host task=" + hostTaskId
                + " display=" + mDisplayId);
    }

    private void releaseTasks() {
        final TaskDisplayAreaHandle hostArea = mHostArea;
        final TaskDisplayAreaHandle workspaceArea = mWorkspaceArea;
        if (hostArea == null) {
            mAreaAtTop = null;
            mTaskIds.clear();
            mHostBackstopTaskId = -1;
            mWorkspaceBackstopTaskId = -1;
            mHostTaskId = -1;
            return;
        }
        final int hostBackstopTaskId = mHostBackstopTaskId;
        final int workspaceBackstopTaskId = mWorkspaceBackstopTaskId;
        final int hostTaskId = mHostTaskId;
        try {
            final Set<Integer> ownedTaskIds = new LinkedHashSet<>(mTaskIds);
            final Set<Integer> hostChildTaskIds = findOwnedChildTaskIds(
                    hostArea.featureId(), ownedTaskIds);
            final Set<Integer> workspaceChildTaskIds = workspaceArea == null
                    ? Collections.<Integer>emptySet()
                    : findOwnedChildTaskIds(
                            workspaceArea.featureId(), ownedTaskIds);
            final Set<Integer> childTaskIds = new LinkedHashSet<>(
                    hostChildTaskIds);
            childTaskIds.addAll(workspaceChildTaskIds);
            normalizeChildTasks(childTaskIds);
            // Keep mode changes separate from hierarchy changes. Combining
            // them can make vendor WMS compare a task against an area whose
            // parent has already changed within the same transaction.
            // Closing the phone desktop parks its surviving tasks fullscreen
            // behind the UI that Android selects next. Raising every released
            // root is both unnecessary and unsafe on firmware whose priority
            // traversal still contains an organizer area being dismantled.
            if (workspaceArea != null) {
                workspaceArea.detachChildTasks(
                        mService,
                        mDisplayId,
                        workspaceChildTaskIds,
                        null,
                        false);
            }
            hostArea.detachChildTasks(
                    mService,
                    mDisplayId,
                    hostChildTaskIds,
                    null,
                    false);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not release desktop task area", error);
        } finally {
            mHostArea = null;
            mWorkspaceArea = null;
            mAreaAtTop = null;
            mTaskIds.clear();
            mHostBackstopTaskId = -1;
            mWorkspaceBackstopTaskId = -1;
            mHostTaskId = -1;
            if (workspaceArea != null
                    && !workspaceArea.closeIfOnlyOwnedChildren(
                            mService,
                            mDisplayId,
                            workspaceBackstopTaskId < 0
                                    ? Collections.<Integer>emptySet()
                                    : Collections.singleton(Integer.valueOf(
                                            workspaceBackstopTaskId)))) {
                Log.w(TAG, "freeform overlay retained after unsafe cleanup"
                        + " feature=" + workspaceArea.featureId());
            }
            if (!hostArea.closeIfOnlyOwnedChildren(
                    mService,
                    mDisplayId,
                    ownedInfrastructureTaskIds(
                            hostTaskId, hostBackstopTaskId))) {
                Log.w(TAG, "desktop task area retained after unsafe cleanup"
                        + " feature=" + hostArea.featureId());
            }
        }
    }

    private static Set<Integer> ownedInfrastructureTaskIds(
            final int hostTaskId,
            final int backstopTaskId) {
        final Set<Integer> taskIds = new LinkedHashSet<>();
        if (hostTaskId >= 0) {
            taskIds.add(Integer.valueOf(hostTaskId));
        }
        if (backstopTaskId >= 0) {
            taskIds.add(Integer.valueOf(backstopTaskId));
        }
        return taskIds;
    }

    private Set<Integer> findOwnedChildTaskIds(
            final int featureId,
            final Set<Integer> ownedTaskIds)
            throws ReflectiveOperationException {
        final Set<Integer> childTaskIds = new LinkedHashSet<>();
        for (final Object task : HiddenTaskApi.getTasks(mService, mDisplayId)) {
            final Integer taskId = Integer.valueOf(
                    HiddenTaskApi.getTaskId(task));
            if (ownedTaskIds.contains(taskId)
                    && HiddenTaskApi.getTaskDisplayAreaFeatureId(task) == featureId) {
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
        final FrameworkWindowingApi windowing =
                FrameworkRuntime.current().windowing();
        final Class<?> transactionClass = windowing.transactionClass();
        final Object transaction = windowing.newTransaction();
        for (final Integer taskId : childTaskIds) {
            final Object taskToken = HiddenTaskApi.requireTaskToken(
                    mService, mDisplayId, taskId.intValue());
            windowing.setWindowingMode(
                    transaction, taskToken, WINDOWING_MODE_FULLSCREEN);
            windowing.setBounds(transaction, taskToken, new Rect());
            windowing.setForceTranslucent(transaction, taskToken, false);
            TaskCaptionInsetsCommand.addCaptionInsetOperation(
                    transaction,
                    taskToken,
                    true);
        }
        ShellWindowTransitionExecutor.applySynchronized(
                mService, transactionClass, transaction);
    }

    private void waitForTaskArea(
            final int taskId,
            final int featureId,
            final boolean expectedInside)
            throws ReflectiveOperationException {
        final FrameworkTaskSnapshot task =
                BoundedStateAwaiter.awaitFramework(
                        BoundedStateAwaiter.Reason.TASK_HIERARCHY,
                        HIERARCHY_TIMEOUT_MILLIS,
                        HIERARCHY_POLL_MILLIS,
                        () -> FrameworkTaskSnapshotSource.findTask(
                                mService, mDisplayId, taskId),
                        current -> current == null
                                ? !expectedInside
                                : (current.displayAreaFeatureId == featureId)
                                        == expectedInside);
        if (task == null ? !expectedInside
                : (task.displayAreaFeatureId == featureId)
                        == expectedInside) {
            return;
        }
        final int observedFeatureId = task == null
                ? Integer.MIN_VALUE : task.displayAreaFeatureId;
        throw new IllegalStateException(
                "task " + taskId + " did not reach display area "
                        + featureId + "; observed=" + observedFeatureId);
    }

    private void waitForTasksRemoved(final List<Integer> taskIds)
            throws ReflectiveOperationException {
        final List<FrameworkTaskSnapshot> tasks =
                BoundedStateAwaiter.awaitFramework(
                        BoundedStateAwaiter.Reason.TASK_HIERARCHY,
                        HIERARCHY_TIMEOUT_MILLIS,
                        HIERARCHY_POLL_MILLIS,
                        () -> FrameworkTaskSnapshotSource.readWindowState(
                                mService, mDisplayId, 100),
                        current -> noneMatch(current, taskIds));
        if (noneMatch(tasks, taskIds)) {
            return;
        }
        throw new IllegalStateException(
                "desktop package tasks were not removed: " + taskIds);
    }

    private static boolean noneMatch(
            final List<FrameworkTaskSnapshot> tasks,
            final List<Integer> taskIds) {
        for (final FrameworkTaskSnapshot task : tasks) {
            if (taskIds.contains(Integer.valueOf(task.taskId))) {
                return false;
            }
        }
        return true;
    }

}

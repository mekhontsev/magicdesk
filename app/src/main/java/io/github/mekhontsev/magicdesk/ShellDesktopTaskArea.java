package io.github.mekhontsev.magicdesk;

import android.app.ActivityManager;
import android.content.Intent;
import android.os.UserHandle;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Owns the managed application session below a root HOME host. */
final class ShellDesktopTaskArea implements AutoCloseable {
    enum ForegroundState {
        HOST(true, false),
        APPLICATION(true, true),
        OUTSIDE(false, false);

        final boolean desktopSessionForeground;
        final boolean applicationAreaForeground;

        ForegroundState(
                final boolean desktopSessionForeground,
                final boolean applicationAreaForeground) {
            this.desktopSessionForeground = desktopSessionForeground;
            this.applicationAreaForeground = applicationAreaForeground;
        }
    }

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
    private final ShellTaskLauncher mTaskLauncher;
    private final Set<Integer> mTaskIds = new LinkedHashSet<>();

    private TaskDisplayAreaHandle mApplicationArea;
    private int mApplicationBackstopTaskId = -1;
    private int mDisplayId = -1;
    private int mHostTaskId = -1;
    private boolean mEnabled;
    private Boolean mApplicationAreaAtTop;
    private DesktopTaskAreaPolicy mTaskAreaPolicy =
            DesktopTaskAreaPolicy.UNCONFIGURED;

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
        final boolean enabled = taskAreaPolicy.usesManagedApplicationArea();
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
            mTaskAreaPolicy = DesktopTaskAreaPolicy.UNCONFIGURED;
            return;
        }
        mEnabled = true;
        mDisplayId = displayId;
        mTaskAreaPolicy = taskAreaPolicy;
        try {
            ensureAreas();
            // The host identifies this session but deliberately remains a
            // root HOME task outside the organizer-owned application area.
            mHostTaskId = hostTaskId;
        } catch (ReflectiveOperationException | RuntimeException error) {
            releaseTasks();
            mEnabled = false;
            mDisplayId = -1;
            mTaskAreaPolicy = DesktopTaskAreaPolicy.UNCONFIGURED;
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
                || taskAreaPolicy == DesktopTaskAreaPolicy.UNCONFIGURED) {
            throw new IllegalArgumentException(
                    "desktop host requires a task-area policy");
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
        if (!taskAreaPolicy.usesDirectRootWorkspace()) {
            throw new IllegalArgumentException(
                    "desktop HOME must launch in the display root");
        }
        return launchRootHome(displayId, intent, existingHostTaskIds);
    }

    private int launchRootHome(
            final int displayId,
            final Intent intent,
            final List<Integer> existingHostTaskIds)
            throws ReflectiveOperationException {
        releaseTasks();
        mEnabled = false;
        mDisplayId = -1;
        mTaskAreaPolicy = DesktopTaskAreaPolicy.UNCONFIGURED;
        removeStaleHostTasks(existingHostTaskIds);
        final int taskId = TaskDisplayAreaLaunchCommand.launchFullscreenTask(
                mService,
                displayId,
                intent,
                intent.getComponent().getPackageName(),
                null,
                ACTIVITY_TYPE_HOME);
        final Object task = HiddenTaskApi.requireTask(
                mService, displayId, taskId);
        if (HiddenTaskApi.getTaskActivityType(task) != ACTIVITY_TYPE_HOME) {
            TaskControlCommand.removeTask(mService, taskId);
            throw new IllegalStateException(
                    "desktop host did not become a HOME task");
        }
        mOwnership.markDesktopHost(taskId);
        return taskId;
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

    synchronized int launchSessionWindowedTask(
            final int displayId,
            final Intent intent,
            final Rect bounds) throws ReflectiveOperationException {
        final TaskDisplayAreaHandle applicationArea =
                requireApplicationArea(displayId, bounds);
        final int taskId = mTaskLauncher.launchWindowed(
                displayId,
                intent,
                bounds,
                applicationArea.token(),
                true);
        mTaskIds.add(Integer.valueOf(taskId));
        waitForTaskArea(taskId, applicationArea.featureId(), true);
        return taskId;
    }

    synchronized int launchSessionFullscreenTask(
            final int displayId,
            final Intent intent) throws ReflectiveOperationException {
        final TaskDisplayAreaHandle applicationArea =
                requireApplicationArea(displayId);
        final int taskId = mTaskLauncher.launchFullscreen(
                displayId,
                intent,
                applicationArea.token());
        mTaskIds.add(Integer.valueOf(taskId));
        waitForTaskArea(taskId, applicationArea.featureId(), true);
        TaskDisplayAreaLaunchCommand.waitForTaskWindowingMode(
                mService,
                displayId,
                taskId,
                WINDOWING_MODE_FULLSCREEN);
        return taskId;
    }

    synchronized int launchSessionWindowedShortcut(
            final int displayId,
            final String packageName,
            final String shortcutId,
            final UserHandle user,
            final Rect bounds) throws ReflectiveOperationException {
        final TaskDisplayAreaHandle applicationArea =
                requireApplicationArea(displayId, bounds);
        final int taskId = mTaskLauncher.launchShortcutWindowed(
                displayId,
                packageName,
                shortcutId,
                user,
                bounds,
                applicationArea.token(),
                true);
        mTaskIds.add(Integer.valueOf(taskId));
        waitForTaskArea(taskId, applicationArea.featureId(), true);
        return taskId;
    }

    synchronized int launchSessionFullscreenShortcut(
            final int displayId,
            final String packageName,
            final String shortcutId,
            final UserHandle user) throws ReflectiveOperationException {
        final TaskDisplayAreaHandle applicationArea =
                requireApplicationArea(displayId);
        final int taskId = mTaskLauncher.launchShortcutFullscreen(
                displayId,
                packageName,
                shortcutId,
                user,
                applicationArea.token());
        mTaskIds.add(Integer.valueOf(taskId));
        waitForTaskArea(taskId, applicationArea.featureId(), true);
        return taskId;
    }

    synchronized boolean ownsApplicationArea(final int displayId) {
        return mEnabled
                && mDisplayId == displayId
                && mApplicationArea != null;
    }

    synchronized int managedDisplayId() {
        return mEnabled ? mDisplayId : -1;
    }

    synchronized boolean matchesConfiguration(
            final int displayId,
            final DesktopTaskAreaPolicy taskAreaPolicy,
            final int hostTaskId) {
        final boolean enabled = taskAreaPolicy != null
                && taskAreaPolicy.usesManagedApplicationArea();
        return mEnabled == enabled
                && (!enabled || (mDisplayId == displayId
                        && mHostTaskId == hostTaskId
                        && mTaskAreaPolicy == taskAreaPolicy));
    }

    private boolean isManagedAreaFeature(final int featureId) {
        return mApplicationArea != null
                && mApplicationArea.featureId() == featureId;
    }

    synchronized void placeSessionWindowedTask(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final Rect bounds) throws ReflectiveOperationException {
        placeTaskInSession(
                taskId,
                sourceDisplayId,
                targetDisplayId,
                WINDOWING_MODE_FREEFORM,
                bounds,
                false);
        TaskDisplayAreaLaunchCommand.waitForTaskFreeformBounds(
                mService, targetDisplayId, taskId, bounds);
    }

    synchronized void placeSessionFullscreenTask(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId) throws ReflectiveOperationException {
        placeTaskInSession(
                taskId,
                sourceDisplayId,
                targetDisplayId,
                WINDOWING_MODE_FULLSCREEN,
                new Rect(),
                true);
        TaskDisplayAreaLaunchCommand.waitForTaskWindowingMode(
                mService,
                targetDisplayId,
                taskId,
                WINDOWING_MODE_FULLSCREEN);
    }

    private void placeTaskInSession(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final int windowingMode,
            final Rect bounds,
            final boolean excludeCaptionInset)
            throws ReflectiveOperationException {
        final TaskDisplayAreaHandle applicationArea =
                requireApplicationArea(targetDisplayId);
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
                "place-task-in-managed-area");
        mTaskIds.add(Integer.valueOf(taskId));
        waitForTaskArea(taskId, applicationArea.featureId(), true);
    }

    private TaskDisplayAreaHandle requireApplicationArea(
            final int displayId) throws ReflectiveOperationException {
        requireConfigured(displayId);
        ensureAreas();
        if (mApplicationArea == null) {
            throw new IllegalStateException(
                    "managed application session is unavailable on display "
                            + displayId);
        }
        return mApplicationArea;
    }

    private TaskDisplayAreaHandle requireApplicationArea(
            final int displayId,
            final Rect bounds) throws ReflectiveOperationException {
        requireConfigured(displayId, bounds);
        return requireApplicationArea(displayId);
    }

    synchronized void onTaskRemoved(final int taskId) {
        mTaskIds.remove(Integer.valueOf(taskId));
        if (taskId == mApplicationBackstopTaskId) {
            mApplicationBackstopTaskId = -1;
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

    synchronized ForegroundState foregroundForTask(
            final int displayId,
            final int taskId) {
        if (!mEnabled || displayId != mDisplayId || taskId < 0) {
            return null;
        }
        if (taskId == mHostTaskId) {
            return ForegroundState.HOST;
        }
        if (mApplicationArea == null) {
            return ForegroundState.OUTSIDE;
        }
        try {
            final Object task = HiddenTaskApi.findTask(
                    mService, displayId, taskId);
            return task != null
                    && mTaskIds.contains(Integer.valueOf(taskId))
                    && isManagedAreaFeature(
                            HiddenTaskApi.getTaskDisplayAreaFeatureId(task))
                    ? ForegroundState.APPLICATION
                    : ForegroundState.OUTSIDE;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not inspect desktop task parent task="
                    + taskId, error);
            return null;
        }
    }

    synchronized ForegroundState foregroundAfterTaskMovedToFront(
            final ActivityManager.RunningTaskInfo taskInfo) {
        if (taskInfo == null) {
            return null;
        }
        final int displayId = HiddenTaskApi.getTaskDisplayId(taskInfo);
        final ForegroundState foreground;
        if (!mEnabled || displayId != mDisplayId
                || mApplicationArea == null) {
            foreground = null;
        } else if (taskInfo.taskId == mHostTaskId) {
            foreground = ForegroundState.HOST;
        } else if (!mTaskIds.contains(Integer.valueOf(taskInfo.taskId))) {
            foreground = ForegroundState.OUTSIDE;
        } else {
            try {
                foreground = isManagedAreaFeature(
                        HiddenTaskApi.getTaskDisplayAreaFeatureId(taskInfo))
                        ? ForegroundState.APPLICATION
                        : ForegroundState.OUTSIDE;
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.w(TAG, "could not inspect foreground task parent task="
                        + taskInfo.taskId, error);
                return null;
            }
        }
        if (foreground != ForegroundState.OUTSIDE || !isHomeTask(taskInfo)) {
            return foreground;
        }

        // When the last child finishes, Android can focus HOME as its generic
        // fallback even though our root HOME host still owns this session.
        // Record that the system changed Z-order, but do not publish a desktop
        // departure or start another transition while the child is closing.
        mApplicationAreaAtTop = null;
        return null;
    }

    synchronized boolean focusSessionWorkspace(
            final int displayId,
            final int[] taskIds) throws ReflectiveOperationException {
        if (!mEnabled || mApplicationArea == null
                || displayId != mDisplayId || taskIds == null
                || taskIds.length == 0) {
            return false;
        }
        final int targetTaskId = taskIds[taskIds.length - 1];
        final boolean targetHost = targetTaskId == mHostTaskId;
        final List<Integer> applicationTaskIds = new ArrayList<>();
        for (final int taskId : taskIds) {
            if (taskId == mHostTaskId) {
                continue;
            }
            final Object task = HiddenTaskApi.findTask(
                    mService, displayId, taskId);
            if (task != null
                    && mTaskIds.contains(Integer.valueOf(taskId))
                    && isManagedAreaFeature(
                            HiddenTaskApi.getTaskDisplayAreaFeatureId(task))) {
                applicationTaskIds.add(Integer.valueOf(taskId));
            }
        }
        if (!targetHost && (applicationTaskIds.isEmpty()
                || applicationTaskIds.get(applicationTaskIds.size() - 1)
                        .intValue() != targetTaskId)) {
            return false;
        }
        final int[] orderedApplicationTaskIds = new int[
                applicationTaskIds.size()];
        for (int index = 0; index < applicationTaskIds.size(); index++) {
            orderedApplicationTaskIds[index] =
                    applicationTaskIds.get(index).intValue();
        }

        if (!targetHost) {
            // The application area and its selected child must cross the root
            // HOME boundary in one WCT. Reordering with parents performs that
            // hierarchy operation without restarting the existing activity or
            // reapplying a stale launch windowing mode.
            TaskWindowingCommand.focusTasks(
                    mService, displayId, orderedApplicationTaskIds);
            mApplicationAreaAtTop = Boolean.TRUE;
            return true;
        }

        final FrameworkWindowingApi windowing =
                FrameworkRuntime.current().windowing();
        final Class<?> transactionClass = windowing.transactionClass();
        final Object transaction = windowing.newTransaction();
        if (orderedApplicationTaskIds.length > 0) {
            TaskWindowingCommand.addFocusTasksWithinCurrentParent(
                    mService,
                    displayId,
                    orderedApplicationTaskIds,
                    transactionClass,
                    transaction);
        }
        // The application sibling and root HOME are one workspace. Commit
        // their relative order with the selected child so no callback can
        // observe or restore an intermediate hierarchy.
        windowing.reorder(
                transaction, mApplicationArea.token(), false);
        windowing.reorder(
                transaction,
                HiddenTaskApi.requireTaskToken(
                        mService, displayId, mHostTaskId),
                true,
                false);
        ShellWindowTransitionExecutor.applyAtomic(
                mService, transactionClass, transaction);
        mApplicationAreaAtTop = Boolean.FALSE;
        return true;
    }

    synchronized void setApplicationAreaForeground(final boolean foreground)
            throws ReflectiveOperationException {
        if (!mEnabled || mApplicationArea == null
                || (mApplicationAreaAtTop != null
                        && mApplicationAreaAtTop.booleanValue()
                                == foreground)) {
            return;
        }

        final FrameworkWindowingApi windowing =
                FrameworkRuntime.current().windowing();
        final Class<?> transactionClass = windowing.transactionClass();
        final Object transaction = windowing.newTransaction();
        // An organizer-created area must remain at an edge of the default
        // task container. Leaving it between ordinary root tasks breaks task
        // traversal assumptions in some ActivityTaskManager implementations.
        windowing.reorder(
                transaction, mApplicationArea.token(), foreground);
        ShellWindowTransitionExecutor.applyAtomic(
                mService, transactionClass, transaction);
        mApplicationAreaAtTop = Boolean.valueOf(foreground);
        Log.d(TAG, "desktop application area foreground=" + foreground
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
            return HiddenTaskApi.getTaskActivityType(taskInfo) == ACTIVITY_TYPE_HOME;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not inspect foreground task type", error);
            return false;
        }
    }

    synchronized boolean removePackageTasks(
            final int displayId,
            final String packageName,
            final int focusTaskId) {
        if (!mEnabled || mApplicationArea == null
                || displayId != mDisplayId
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
            for (final int taskId : mOwnership.desktopTaskIds()) {
                if (taskId == focusTaskId || taskId == mHostTaskId) {
                    continue;
                }
                final Object task = HiddenTaskApi.findTask(
                        mService, displayId, taskId);
                if (task != null
                        && mOwnership.isDesktopTask(task)
                        && packageName.equals(HiddenTaskApi.getTaskPackage(task))) {
                    removedTaskIds.add(Integer.valueOf(taskId));
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
            // The package action already originates inside this application area,
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
        mTaskAreaPolicy = DesktopTaskAreaPolicy.UNCONFIGURED;
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
        if (mApplicationArea != null) {
            return;
        }
        final TaskDisplayAreaHandle applicationArea = createArea(
                "MagicDesk application session", "session");
        mApplicationArea = applicationArea;
        Log.i(TAG, "created desktop application area display=" + mDisplayId
                + " policy=" + mTaskAreaPolicy);
    }

    private TaskDisplayAreaHandle createArea(
            final String name,
            final String instanceKind) throws ReflectiveOperationException {
        final TaskDisplayAreaHandle area = TaskDisplayAreaHandle.create(
                mDisplayId,
                FEATURE_DEFAULT_TASK_CONTAINER,
                name);
        int backstopTaskId = -1;
        try {
            // Keep the application area in the display's default mode. Marking the
            // parent freeform makes framework restore every fullscreen child
            // to freeform when the area is activated. Windowed launch staging
            // publishes freeform only after the child's task token exists.
            // A desktop is one stable viewport. Individual activities may
            // adapt their own content, but must not rotate sibling planes.
            area.setIgnoreOrientationRequest(mService, true);
            backstopTaskId = launchAreaBackstop(
                    area, instanceKind);
            mApplicationBackstopTaskId = backstopTaskId;
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
            final String instanceKind) throws ReflectiveOperationException {
        // A HOME-type child here makes framework HOME traversal cross an
        // organizer area which can disappear during cleanup. The backstop is
        // structural only and must remain a regular, non-focusable task.
        final int taskId = TaskDisplayAreaLaunchCommand
                .launchFullscreenTaskBehind(
                        mService,
                        mDisplayId,
                        TaskAreaBackstopActivity.createIntent(
                                instanceKind + ':' + mDisplayId + ':'
                                        + area.featureId()),
                        BuildConfig.APPLICATION_ID,
                        area.token());
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
        ShellWindowTransitionExecutor.applyAtomic(
                mService, transactionClass, transaction);
        return taskId;
    }

    private void releaseTasks() {
        final TaskDisplayAreaHandle applicationArea = mApplicationArea;
        if (applicationArea == null) {
            mApplicationAreaAtTop = null;
            mTaskIds.clear();
            mApplicationBackstopTaskId = -1;
            mHostTaskId = -1;
            return;
        }
        final int applicationBackstopTaskId = mApplicationBackstopTaskId;
        try {
            final Set<Integer> ownedTaskIds = new LinkedHashSet<>(mTaskIds);
            final Set<Integer> applicationTaskIds = findOwnedChildTaskIds(
                    applicationArea.featureId(), ownedTaskIds);
            final Set<Integer> childTaskIds = new LinkedHashSet<>(
                    applicationTaskIds);
            normalizeChildTasks(childTaskIds);
            // Keep mode changes separate from hierarchy changes. Combining
            // them can make vendor WMS compare a task against an area whose
            // parent has already changed within the same transaction.
            // Closing the phone desktop parks its surviving tasks fullscreen
            // behind the UI that Android selects next. Raising every released
            // root is both unnecessary and unsafe on firmware whose priority
            // traversal still contains an organizer area being dismantled.
            applicationArea.detachChildTasks(
                    mService,
                    mDisplayId,
                    applicationTaskIds,
                    null,
                    false);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not release desktop task area", error);
        } finally {
            mApplicationArea = null;
            mApplicationAreaAtTop = null;
            mTaskIds.clear();
            mApplicationBackstopTaskId = -1;
            mHostTaskId = -1;
            if (!applicationArea.closeIfOnlyOwnedChildren(
                    mService,
                    mDisplayId,
                    ownedInfrastructureTaskIds(
                            applicationBackstopTaskId))) {
                Log.w(TAG, "desktop application area retained after unsafe"
                        + " cleanup feature="
                        + applicationArea.featureId());
            }
        }
    }

    private static Set<Integer> ownedInfrastructureTaskIds(
            final int backstopTaskId) {
        final Set<Integer> taskIds = new LinkedHashSet<>();
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

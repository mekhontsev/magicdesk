package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.util.Log;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** Keeps non-phone fullscreen tasks on stable, independently reordered planes. */
final class ShellFullscreenTaskPlanes implements AutoCloseable {
    private static final String TAG = "MagicDeskFullscreenPlanes";
    private static final int ACTIVITY_TYPE_STANDARD = 1;
    private static final int FEATURE_DEFAULT_TASK_CONTAINER = 1;
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final long SURFACE_COMMIT_TIMEOUT_SECONDS = 2L;

    private final Map<Integer, TaskDisplayAreaHandle> mPlanes =
            new LinkedHashMap<>();
    private final Map<TaskDisplayAreaHandle, Integer> mPlaneAnchorTaskIds =
            new LinkedHashMap<>();
    private final List<TaskDisplayAreaHandle> mAvailablePlanes =
            new ArrayList<>();
    private final List<Integer> mPlaneOrder = new ArrayList<>();
    private Object mService;
    private int mDisplayId = -1;
    private int mNextPlaneSlotId;
    private boolean mConcealedForShowDesktop;

    synchronized void configure(final int displayId) {
        if (displayId < 0) {
            close();
            mDisplayId = -1;
            mNextPlaneSlotId = 0;
            return;
        }
        if (mDisplayId != displayId) {
            close();
        }
        mDisplayId = displayId;
    }

    synchronized ShellFullscreenTaskArea.FocusResult focusStack(
            final Object service,
            final int displayId,
            final int[] requestedTaskIds,
            final ShellDesktopTaskOwnership ownership)
            throws ReflectiveOperationException {
        if (displayId != mDisplayId || requestedTaskIds == null
                || requestedTaskIds.length == 0) {
            return ShellFullscreenTaskArea.FocusResult.NOT_HANDLED;
        }
        mService = service;
        final List<Integer> fullscreenTaskIds = desktopFullscreenTasks(
                service, displayId, ownership);
        final int targetTaskId =
                requestedTaskIds[requestedTaskIds.length - 1];
        if (ownership.isDesktopHostTask(targetTaskId)) {
            focusDesktopHost(
                    service, displayId, targetTaskId, requestedTaskIds);
            return ShellFullscreenTaskArea.FocusResult.SESSION_FOREGROUND;
        }
        if (fullscreenTaskIds.isEmpty() && mPlanes.isEmpty()) {
            return ShellFullscreenTaskArea.FocusResult.NOT_HANDLED;
        }
        final MixedStackOrder mixedOrder = mixedStackOrder(
                service,
                displayId,
                targetTaskId,
                requestedTaskIds,
                fullscreenTaskIds,
                ownership);
        applyStableOrder(
                service,
                displayId,
                fullscreenTaskIds,
                requestedTaskIds,
                -1,
                false,
                mixedOrder);
        return mPlanes.containsKey(Integer.valueOf(targetTaskId))
                ? ShellFullscreenTaskArea.FocusResult.FULLSCREEN_FOREGROUND
                : ShellFullscreenTaskArea.FocusResult.SESSION_FOREGROUND;
    }

    synchronized boolean concealForShowDesktop(final int displayId) {
        if (displayId != mDisplayId) {
            return false;
        }
        try {
            setPlaneSurfacesVisible(false);
            mConcealedForShowDesktop = true;
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not conceal fullscreen planes for desktop",
                    error);
            return false;
        }
    }

    private void setPlaneSurfacesVisible(final boolean visible)
            throws ReflectiveOperationException {
        if (mPlanes.isEmpty()) {
            return;
        }
        final Class<?> surfaceClass = Class.forName(
                "android.view.SurfaceControl");
        final Class<?> transactionClass = Class.forName(
                "android.view.SurfaceControl$Transaction");
        final Object transaction =
                transactionClass.getConstructor().newInstance();
        try {
            final String operation = visible ? "show" : "hide";
            for (final TaskDisplayAreaHandle plane : mPlanes.values()) {
                transactionClass.getMethod(operation, surfaceClass)
                        .invoke(transaction, plane.surfaceLeash());
            }
            applyCommittedSurfaceTransaction(
                    transactionClass, transaction);
        } finally {
            transactionClass.getMethod("close").invoke(transaction);
        }
    }

    synchronized boolean beginFullscreen(
            final Object service,
            final int displayId,
            final int taskId,
            final boolean refreshCaption,
            final ShellDesktopTaskOwnership ownership) {
        if (displayId != mDisplayId || taskId < 0) {
            return false;
        }
        try {
            mService = service;
            final Object enteringTask = HiddenTaskApi.requireTask(
                    service, displayId, taskId);
            if (ownership.isDesktopHostTask(taskId)
                    || !ownership.isDesktopTask(enteringTask)) {
                return false;
            }
            final int captionSourceId = refreshCaption
                    ? TaskCaptionInsetsRefresher.captureCaptionSourceId(taskId)
                    : TaskLocalInsetsSourceParser.NO_SOURCE_ID;
            final List<Integer> fullscreenTaskIds = desktopFullscreenTasks(
                    service, displayId, ownership);
            fullscreenTaskIds.remove(Integer.valueOf(taskId));
            fullscreenTaskIds.add(Integer.valueOf(taskId));
            applyStableOrder(
                    service,
                    displayId,
                    fullscreenTaskIds,
                    new int[]{taskId},
                    taskId,
                    true,
                    null);
            TaskFullscreenTransitionCommand.refreshCaptionIfRequested(
                    service,
                    displayId,
                    taskId,
                    refreshCaption,
                    captionSourceId);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not enter fullscreen task=" + taskId, error);
            return false;
        }
    }

    synchronized boolean restoreFreeform(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect bounds) {
        if (displayId != mDisplayId || bounds == null || bounds.isEmpty()) {
            return false;
        }
        if (!ownsTask(taskId)) {
            return false;
        }
        try {
            final TaskDisplayAreaHandle plane =
                    mPlanes.get(Integer.valueOf(taskId));
            final int planeFeatureId = plane.featureId();
            // Keep the organizer area non-empty until the application's
            // reparent transition commits. The same retained anchor also
            // makes the empty plane reusable without deleting its hierarchy.
            ShellPreparedTaskTransition.detachAndShowFreeform(
                    service,
                    displayId,
                    taskId,
                    bounds,
                    plane.token(),
                    null);
            TaskDisplayAreaLaunchCommand.waitForTaskFreeformBounds(
                    service, displayId, taskId, bounds);
            waitForTaskOutsidePlane(
                    service, displayId, taskId, planeFeatureId);
            releasePlane(service, taskId);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not restore fullscreen plane task="
                    + taskId, error);
            return false;
        }
    }

    private TaskDisplayAreaHandle acquirePlane(
            final Object service,
            final int displayId)
            throws ReflectiveOperationException {
        if (!mAvailablePlanes.isEmpty()) {
            final TaskDisplayAreaHandle plane = mAvailablePlanes.remove(0);
            requirePlaneAnchor(service, displayId, plane);
            return plane;
        }
        final TaskDisplayAreaHandle plane =
                TaskDisplayAreaHandle.createSurfaceOrdered(
                        displayId,
                        FEATURE_DEFAULT_TASK_CONTAINER,
                        "MagicDesk fullscreen slot " + mNextPlaneSlotId++);
        int anchorTaskId = -1;
        try {
            anchorTaskId = TaskDisplayAreaLaunchCommand
                    .launchFullscreenTaskBehind(
                            service,
                            displayId,
                            TaskAreaBackstopActivity.createIntent(
                                    "fullscreen-slot:" + displayId + ':'
                                            + plane.featureId()),
                            BuildConfig.APPLICATION_ID,
                            plane.token(),
                            ACTIVITY_TYPE_STANDARD);
            final Object anchor = HiddenTaskApi.requireTask(
                    service, displayId, anchorTaskId);
            if (!isStandardBackstop(anchor)) {
                throw new IllegalStateException(
                        "fullscreen slot anchor is not a standard task");
            }
            waitForTaskInsidePlane(
                    service,
                    displayId,
                    anchorTaskId,
                    plane.featureId());
            makeAnchorNonFocusable(service, displayId, anchorTaskId);
            mPlaneAnchorTaskIds.put(
                    plane, Integer.valueOf(anchorTaskId));
            return plane;
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (anchorTaskId >= 0) {
                plane.closeIfOnlyOwnedChildren(
                        service,
                        displayId,
                        Collections.singleton(Integer.valueOf(anchorTaskId)));
                removeMigratedAnchorTasks(
                        service,
                        Collections.singletonMap(
                                Integer.valueOf(anchorTaskId),
                                Integer.valueOf(plane.featureId())));
            } else {
                plane.closeIfEmpty(service, displayId);
            }
            throw error;
        }
    }

    private int requirePlaneAnchor(
            final Object service,
            final int displayId,
            final TaskDisplayAreaHandle plane)
            throws ReflectiveOperationException {
        final Integer anchorTaskId = mPlaneAnchorTaskIds.get(plane);
        final Object anchor = anchorTaskId == null
                ? null
                : HiddenTaskApi.findTask(
                        service, displayId, anchorTaskId.intValue());
        if (!isStandardBackstop(anchor)
                || HiddenTaskApi.getTaskDisplayAreaFeatureId(anchor) != plane.featureId()) {
            throw new IllegalStateException(
                    "fullscreen slot lost its anchor feature="
                            + plane.featureId());
        }
        return anchorTaskId.intValue();
    }

    private static void makeAnchorNonFocusable(
            final Object service,
            final int displayId,
            final int anchorTaskId) throws ReflectiveOperationException {
        final FrameworkWindowingApi windowing =
                FrameworkRuntime.current().windowing();
        final Class<?> transactionClass = windowing.transactionClass();
        final Object transaction = windowing.newTransaction();
        final Object anchorToken = HiddenTaskApi.requireTaskToken(
                service, displayId, anchorTaskId);
        // The anchor keeps the organizer area non-empty but must never become
        // WindowManager's focused application when its plane is reordered.
        windowing.setFocusable(transaction, anchorToken, false);
        ShellWindowTransitionExecutor.applyAtomic(
                service, transactionClass, transaction);
    }

    private static boolean isStandardBackstop(final Object task)
            throws ReflectiveOperationException {
        return task != null
                && TaskAreaBackstopActivity.isBackstopComponent(
                        HiddenTaskApi.getTaskComponent(task))
                && HiddenTaskApi.getTaskActivityType(task) == ACTIVITY_TYPE_STANDARD;
    }

    private static void waitForTaskInsidePlane(
            final Object service,
            final int displayId,
            final int taskId,
            final int planeFeatureId) throws ReflectiveOperationException {
        final FrameworkTaskSnapshot task =
                BoundedStateAwaiter.awaitFramework(
                        BoundedStateAwaiter.Reason.TASK_HIERARCHY,
                        5_000L,
                        50L,
                        () -> FrameworkTaskSnapshotSource.findTask(
                                service, displayId, taskId),
                        current -> current != null
                                && current.displayAreaFeatureId
                                        == planeFeatureId);
        if (task != null
                && task.displayAreaFeatureId == planeFeatureId) {
            return;
        }
        final int observedFeatureId = task == null
                ? Integer.MIN_VALUE : task.displayAreaFeatureId;
        throw new IllegalStateException(
                "task did not enter fullscreen plane feature="
                        + planeFeatureId + ", observed=" + observedFeatureId);
    }

    private static void waitForTaskOutsidePlane(
            final Object service,
            final int displayId,
            final int taskId,
            final int planeFeatureId) throws ReflectiveOperationException {
        final FrameworkTaskSnapshot task =
                BoundedStateAwaiter.awaitFramework(
                        BoundedStateAwaiter.Reason.TASK_HIERARCHY,
                        5_000L,
                        50L,
                        () -> FrameworkTaskSnapshotSource.findTask(
                                service, displayId, taskId),
                        current -> current != null
                                && current.displayAreaFeatureId
                                        != planeFeatureId);
        if (task != null
                && task.displayAreaFeatureId != planeFeatureId) {
            return;
        }
        final int observedFeatureId = task == null
                ? planeFeatureId : task.displayAreaFeatureId;
        throw new IllegalStateException(
                "task remained in fullscreen plane feature="
                        + observedFeatureId);
    }

    synchronized boolean closeTask(
            final Object service,
            final int displayId,
            final int taskId,
            final int focusTaskId,
            final ShellDesktopTaskOwnership ownership) {
        if (displayId != mDisplayId || !ownsTask(taskId)
                || focusTaskId < 0 || focusTaskId == taskId) {
            return false;
        }
        try {
            int effectiveFocusTaskId = focusTaskId;
            if (ownership.isDesktopHostTask(focusTaskId)) {
                final int nextTaskId = findRemovalFocusTarget(
                        service, displayId, taskId, ownership);
                if (nextTaskId >= 0) {
                    effectiveFocusTaskId = nextTaskId;
                }
            }
            final Object focusTask = HiddenTaskApi.findTask(
                    service, displayId, effectiveFocusTaskId);
            if (!isDesktopFocusTarget(
                    focusTask, effectiveFocusTaskId, ownership)) {
                return false;
            }
            closeWithSuccessor(
                    service, displayId, taskId, effectiveFocusTaskId);
            return true;
        } catch (IOException | ReflectiveOperationException
                | RuntimeException error) {
            Log.w(TAG, "could not close fullscreen plane task="
                    + taskId, error);
            return false;
        }
    }

    synchronized boolean ownsTask(final int taskId) {
        return mPlanes.containsKey(Integer.valueOf(taskId));
    }

    synchronized void onTaskRemovalStarted(
            final Object service,
            final int taskId,
            final ShellDesktopTaskOwnership ownership) {
        final TaskDisplayAreaHandle plane = mPlanes.get(
                Integer.valueOf(taskId));
        if (plane == null || service == null || mDisplayId < 0) {
            return;
        }
        try {
            final Object task = HiddenTaskApi.findTask(
                    service, mDisplayId, taskId);
            if (task == null || !HiddenTaskApi.isTaskFocused(task)) {
                return;
            }
            final int focusTaskId = findRemovalFocusTarget(
                    service, mDisplayId, taskId, ownership);
            applyPlaneExitFocus(
                    service, mDisplayId, plane, focusTaskId);
            Log.i(TAG, "prepared fullscreen plane removal task=" + taskId
                    + " successor=" + focusTaskId
                    + " display=" + mDisplayId);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not prepare fullscreen plane removal task="
                    + taskId, error);
        }
    }

    synchronized boolean recoverAnchorFocus(
            final Object service,
            final int anchorTaskId,
            final ShellDesktopTaskOwnership ownership) {
        final TaskDisplayAreaHandle plane = findAnchorPlane(anchorTaskId);
        if (plane == null) {
            return false;
        }
        try {
            final int planeTaskId = findPlaneTaskId(plane);
            final int focusTaskId = findRemovalFocusTarget(
                    service, mDisplayId, planeTaskId, ownership);
            applyPlaneExitFocus(
                    service, mDisplayId, plane, focusTaskId);
            Log.w(TAG, "recovered structural anchor focus task="
                    + anchorTaskId + " successor=" + focusTaskId
                    + " display=" + mDisplayId);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not recover structural anchor focus task="
                    + anchorTaskId, error);
        }
        return true;
    }

    synchronized boolean hasPlanes() {
        return !mPlanes.isEmpty();
    }

    synchronized int planeFeatureId(final int taskId) {
        final TaskDisplayAreaHandle plane =
                mPlanes.get(Integer.valueOf(taskId));
        return plane == null ? Integer.MIN_VALUE : plane.featureId();
    }

    synchronized void onWindowingModeChanged(
            final int displayId,
            final int taskId,
            final int windowingMode) {
        if (displayId != mDisplayId
                || windowingMode == WINDOWING_MODE_FULLSCREEN
                || !ownsTask(taskId)) {
            return;
        }
        try {
            final Object task = HiddenTaskApi.requireTask(
                    mService, displayId, taskId);
            final Object windowConfiguration =
                    HiddenTaskApi.getWindowConfiguration(task);
            final Rect bounds = new Rect((Rect) windowConfiguration.getClass()
                    .getMethod("getBounds")
                    .invoke(windowConfiguration));
            if (!bounds.isEmpty()) {
                restoreFreeform(mService, displayId, taskId, bounds);
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not release freeform plane task="
                    + taskId, error);
        }
    }

    synchronized void onTaskRemoved(final int taskId) {
        removeTask(mService, taskId);
    }

    synchronized void onTaskDisplayChanged(
            final int taskId,
            final int displayId) {
        if (displayId != mDisplayId) {
            removeTask(mService, taskId);
        }
    }

    private void applyStableOrder(
            final Object service,
            final int displayId,
            final List<Integer> fullscreenTaskIds,
            final int[] requestedTaskIds,
            final int enteringTaskId,
            final boolean forceEnteringFullscreen,
            final MixedStackOrder mixedOrder)
            throws ReflectiveOperationException {
        discardStalePlaneRecords(service, displayId);

        final Map<Integer, TaskDisplayAreaHandle> acquiredPlanes =
                new LinkedHashMap<>();
        for (final Integer taskId : fullscreenTaskIds) {
            if (mPlanes.containsKey(taskId)) {
                continue;
            }
            acquiredPlanes.put(
                    taskId,
                    acquirePlane(
                            service, displayId));
        }
        final Map<Integer, TaskDisplayAreaHandle> effectivePlanes =
                new LinkedHashMap<>(mPlanes);
        effectivePlanes.putAll(acquiredPlanes);
        final FrameworkWindowingApi windowing =
                FrameworkRuntime.current().windowing();
        final Class<?> transactionClass = windowing.transactionClass();
        final Object transaction = windowing.newTransaction();
        final boolean launchEnteringTask = forceEnteringFullscreen
                && acquiredPlanes.containsKey(
                        Integer.valueOf(enteringTaskId));
        try {
            for (final Map.Entry<Integer, TaskDisplayAreaHandle> entry
                    : acquiredPlanes.entrySet()) {
                final Object planeToken = entry.getValue().token();
                windowing.setFocusable(transaction, planeToken, true);
                windowing.setWindowingMode(
                        transaction, planeToken, WINDOWING_MODE_FULLSCREEN);
                if (launchEnteringTask
                        && entry.getKey().intValue() == enteringTaskId) {
                    continue;
                }
                final Object taskToken = HiddenTaskApi.requireTaskToken(
                        service, displayId, entry.getKey().intValue());
                windowing.reparent(
                        transaction, taskToken, planeToken, true);
            }
            final boolean workspaceForeground = mixedOrder != null
                    && !mixedOrder.fullscreenForeground;
            final int focusTargetTaskId = requestedTaskIds[
                    requestedTaskIds.length - 1];
            // Exactly one fullscreen plane participates in focus selection;
            // covered planes remain visible and retain their fullscreen mode.
            for (final Map.Entry<Integer, TaskDisplayAreaHandle> entry
                    : effectivePlanes.entrySet()) {
                windowing.setFocusable(
                        transaction,
                        entry.getValue().token(),
                        !workspaceForeground
                                && entry.getKey().intValue()
                                        == focusTargetTaskId);
            }
            if (forceEnteringFullscreen && !launchEnteringTask) {
                final Object taskToken = HiddenTaskApi.requireTaskToken(
                        service, displayId, enteringTaskId);
                windowing.setWindowingMode(
                        transaction, taskToken, WINDOWING_MODE_FULLSCREEN);
                windowing.setBounds(transaction, taskToken, new Rect());
                TaskCaptionInsetsCommand.addCaptionInsetOperation(
                        transaction,
                        taskToken,
                        true);
            }
            final List<Integer> knownOrder = new ArrayList<>();
            for (final Integer taskId : mPlaneOrder) {
                if (effectivePlanes.containsKey(taskId)) {
                    knownOrder.add(taskId);
                }
            }
            for (final Integer taskId : fullscreenTaskIds) {
                if (!knownOrder.contains(taskId)) {
                    knownOrder.add(taskId);
                }
            }
            final int[] stableOrder = completeStableOrder(
                    knownOrder,
                    requestedTaskIds,
                    effectivePlanes.keySet());
            if (mixedOrder == null) {
                addOrderOperations(
                        service,
                        displayId,
                        stableOrder,
                        windowing,
                        transaction,
                        effectivePlanes,
                        null,
                        !forceEnteringFullscreen
                                && crossesFullscreenPlaneBoundary(
                                        stableOrder,
                                        effectivePlanes.keySet()));
            } else {
                // A mixed workspace already defines the complete hierarchy.
                // Running the plane-only ordering first lets ATMS hand
                // top-resumed state to a covered fullscreen sibling before the
                // overlay target is selected later in the same WCT.
                addMixedOrderOperations(
                        service,
                        displayId,
                        mixedOrder,
                        windowing,
                        transaction,
                        effectivePlanes);
            }
            if (forceEnteringFullscreen && !launchEnteringTask) {
                final Object enteringTaskToken = HiddenTaskApi.requireTaskToken(
                        service, displayId, enteringTaskId);
                // Select the application after its plane is raised. Doing this
                // earlier lets the plane's structural anchor retain focus.
                windowing.reorder(
                        transaction, enteringTaskToken, true, true);
            }
            // Applying the hierarchy directly avoids creating a transition
            // token unknown to WMShell, which could outlive a removed display.
            ShellWindowTransitionExecutor.applyAtomic(
                    service, transactionClass, transaction);
            if (mConcealedForShowDesktop) {
                setPlaneSurfacesVisible(true);
                mConcealedForShowDesktop = false;
            }
            // Keep the organizer leashes in the same mixed order as the WCT.
            // The explicit layers make plane swaps immediate; retaining the
            // workspace placeholders prevents either commit order from putting
            // a focused task above a stale surface.
            applySurfaceOrder(
                    mixedOrder == null
                            ? stableOrder
                            : mixedSurfaceOrder(stableOrder, mixedOrder),
                    effectivePlanes);
            if (launchEnteringTask) {
                final TaskDisplayAreaHandle enteringPlane = acquiredPlanes.get(
                        Integer.valueOf(enteringTaskId));
                // Nubia does not resize an organized task's surface for a
                // direct WCT. ActivityTaskManager owns this user-equivalent
                // focus transition, so WMShell receives the task leash and
                // commits fullscreen geometry without restarting the Activity.
                TaskDisplayAreaLaunchCommand.moveExistingTaskAsFullscreen(
                        service,
                        displayId,
                        enteringTaskId,
                        enteringPlane.token());
                TaskDisplayAreaLaunchCommand.waitForTaskWindowingMode(
                        service,
                        displayId,
                        enteringTaskId,
                        WINDOWING_MODE_FULLSCREEN);
                waitForTaskInsidePlane(
                        service,
                        displayId,
                        enteringTaskId,
                        enteringPlane.featureId());
            }
            mPlanes.putAll(acquiredPlanes);
            mPlaneOrder.clear();
            for (final int taskId : stableOrder) {
                if (effectivePlanes.containsKey(Integer.valueOf(taskId))) {
                    mPlaneOrder.add(Integer.valueOf(taskId));
                }
            }
            Log.i(TAG, "fullscreen planes display=" + displayId
                    + " tasks=" + mPlanes.keySet()
                    + " order=" + java.util.Arrays.toString(stableOrder)
                    + " target="
                    + requestedTaskIds[requestedTaskIds.length - 1]);
        } catch (ReflectiveOperationException | RuntimeException error) {
            for (final TaskDisplayAreaHandle plane : acquiredPlanes.values()) {
                if (!mAvailablePlanes.contains(plane)) {
                    mAvailablePlanes.add(plane);
                }
            }
            throw error;
        }
    }

    static int[] completeStableOrder(
            final List<Integer> knownPlaneOrder,
            final int[] requestedTaskIds,
            final Set<Integer> planeTaskIds) {
        final LinkedHashSet<Integer> order = new LinkedHashSet<>();
        if (knownPlaneOrder != null) {
            order.addAll(knownPlaneOrder);
        }
        if (requestedTaskIds != null && requestedTaskIds.length > 0) {
            for (int index = 0;
                    index < requestedTaskIds.length - 1;
                    index++) {
                final int taskId = requestedTaskIds[index];
                final Integer taskKey = Integer.valueOf(taskId);
                if (planeTaskIds != null && planeTaskIds.contains(taskKey)) {
                    continue;
                }
                order.remove(taskKey);
                order.add(taskKey);
            }
            final Integer targetTaskId = Integer.valueOf(
                    requestedTaskIds[requestedTaskIds.length - 1]);
            order.remove(targetTaskId);
            order.add(targetTaskId);
        }
        final int[] output = new int[order.size()];
        int index = 0;
        for (final Integer taskId : order) {
            output[index++] = taskId.intValue();
        }
        return output;
    }

    static int[] planeBottomReorderOrder(
            final int[] taskIds,
            final Set<Integer> planeTaskIds) {
        if (taskIds == null || taskIds.length == 0
                || planeTaskIds == null || planeTaskIds.isEmpty()) {
            return new int[0];
        }
        int count = 0;
        for (final int taskId : taskIds) {
            if (planeTaskIds.contains(Integer.valueOf(taskId))) {
                count++;
            }
        }
        final int[] output = new int[count];
        int outputIndex = 0;
        // Every reorder-to-bottom inserts below the preceding operation. Walk
        // the desired top-to-bottom order so the committed hierarchy retains
        // the original bottom-to-top MRU order.
        for (int index = taskIds.length - 1; index >= 0; index--) {
            final int taskId = taskIds[index];
            if (planeTaskIds.contains(Integer.valueOf(taskId))) {
                output[outputIndex++] = taskId;
            }
        }
        return output;
    }

    static MixedStackOrder buildMixedStackOrder(
            final int targetTaskId,
            final int desktopHostTaskId,
            final int[] requestedTaskIds,
            final Set<Integer> planeTaskIds,
            final List<Integer> visibleFreeformTaskIds,
            final int currentFullscreenTaskId,
            final boolean targetIsFreeform) {
        if (targetTaskId < 0 || desktopHostTaskId < 0
                || targetTaskId == desktopHostTaskId
                || planeTaskIds == null || planeTaskIds.isEmpty()) {
            return null;
        }
        final int fullscreenTaskId = planeTaskIds.contains(
                Integer.valueOf(targetTaskId))
                        ? targetTaskId : currentFullscreenTaskId;
        if (!planeTaskIds.contains(Integer.valueOf(fullscreenTaskId))) {
            return null;
        }
        final LinkedHashSet<Integer> retainedFreeforms =
                new LinkedHashSet<>();
        int hostIndex = -1;
        if (requestedTaskIds != null) {
            for (int index = 0; index < requestedTaskIds.length; index++) {
                if (requestedTaskIds[index] == desktopHostTaskId) {
                    hostIndex = index;
                    break;
                }
            }
        }
        if (hostIndex >= 0) {
            for (int index = hostIndex + 1;
                    index < requestedTaskIds.length;
                    index++) {
                final int taskId = requestedTaskIds[index];
                if (taskId != desktopHostTaskId
                        && !planeTaskIds.contains(Integer.valueOf(taskId))) {
                    retainedFreeforms.add(Integer.valueOf(taskId));
                }
            }
            if (!targetIsFreeform && visibleFreeformTaskIds != null) {
                for (final Integer taskId : visibleFreeformTaskIds) {
                    if (taskId != null
                            && !contains(requestedTaskIds,
                                    taskId.intValue())) {
                        // A semantic activation order may omit a freeform
                        // blocker because it is being demoted. It still has a
                        // live root surface, so explicitly place it below the
                        // host before raising the fullscreen plane.
                        retainedFreeforms.add(taskId);
                    }
                }
            }
        } else if (visibleFreeformTaskIds != null) {
            retainedFreeforms.addAll(visibleFreeformTaskIds);
        }
        if (targetIsFreeform) {
            retainedFreeforms.remove(Integer.valueOf(targetTaskId));
            retainedFreeforms.add(Integer.valueOf(targetTaskId));
        }
        if (retainedFreeforms.isEmpty()) {
            return null;
        }
        return new MixedStackOrder(
                targetTaskId,
                desktopHostTaskId,
                fullscreenTaskId,
                toIntArray(new ArrayList<>(retainedFreeforms)),
                !targetIsFreeform);
    }

    private static boolean contains(final int[] values, final int value) {
        if (values != null) {
            for (final int candidate : values) {
                if (candidate == value) {
                    return true;
                }
            }
        }
        return false;
    }

    static int[] mixedSurfaceOrder(
            final int[] stableOrder,
            final MixedStackOrder mixedOrder) {
        if (stableOrder == null || mixedOrder == null) {
            return stableOrder == null ? new int[0] : stableOrder.clone();
        }
        final LinkedHashSet<Integer> order = new LinkedHashSet<>();
        for (final int taskId : stableOrder) {
            order.add(Integer.valueOf(taskId));
        }
        order.remove(Integer.valueOf(mixedOrder.targetTaskId));
        for (final int taskId : mixedOrder.freeformTaskIds) {
            order.remove(Integer.valueOf(taskId));
        }
        for (final int taskId : mixedOrder.freeformTaskIds) {
            order.add(Integer.valueOf(taskId));
        }
        if (mixedOrder.fullscreenForeground) {
            order.add(Integer.valueOf(mixedOrder.targetTaskId));
        }
        return toIntArray(new ArrayList<>(order));
    }

    static final class MixedStackOrder {
        final int targetTaskId;
        final int desktopHostTaskId;
        final int fullscreenTaskId;
        final int[] freeformTaskIds;
        final boolean fullscreenForeground;

        MixedStackOrder(
                final int targetTaskId,
                final int desktopHostTaskId,
                final int fullscreenTaskId,
                final int[] freeformTaskIds,
                final boolean fullscreenForeground) {
            this.targetTaskId = targetTaskId;
            this.desktopHostTaskId = desktopHostTaskId;
            this.fullscreenTaskId = fullscreenTaskId;
            this.freeformTaskIds = freeformTaskIds;
            this.fullscreenForeground = fullscreenForeground;
        }

        boolean selectsFreeformTask(final int taskId) {
            return !fullscreenForeground && targetTaskId == taskId;
        }
    }


    private MixedStackOrder mixedStackOrder(
            final Object service,
            final int displayId,
            final int targetTaskId,
            final int[] requestedTaskIds,
            final List<Integer> fullscreenTaskIds,
            final ShellDesktopTaskOwnership ownership)
            throws ReflectiveOperationException {
        final Set<Integer> planeTaskIds = new LinkedHashSet<>(
                fullscreenTaskIds);
        planeTaskIds.addAll(mPlanes.keySet());
        int currentFullscreenTaskId = -1;
        for (int index = mPlaneOrder.size() - 1; index >= 0; index--) {
            final int taskId = mPlaneOrder.get(index).intValue();
            if (planeTaskIds.contains(Integer.valueOf(taskId))) {
                currentFullscreenTaskId = taskId;
                break;
            }
        }
        if (currentFullscreenTaskId < 0 && !fullscreenTaskIds.isEmpty()) {
            currentFullscreenTaskId = fullscreenTaskIds.get(
                    fullscreenTaskIds.size() - 1).intValue();
        }
        final Object targetTask = HiddenTaskApi.requireTask(
                service, displayId, targetTaskId);
        final boolean targetIsFreeform =
                HiddenTaskApi.getTaskWindowingMode(targetTask)
                        == WINDOWING_MODE_FREEFORM;
        return buildMixedStackOrder(
                targetTaskId,
                ownership.desktopHostTaskId(),
                requestedTaskIds,
                planeTaskIds,
                visibleFreeformTaskIds(
                        service, displayId, -1, ownership),
                currentFullscreenTaskId,
                targetIsFreeform);
    }

    private static void addOrderOperations(
            final Object service,
            final int displayId,
            final int[] taskIds,
            final FrameworkWindowingApi windowing,
            final Object transaction,
            final Map<Integer, TaskDisplayAreaHandle> planes,
            final Object workspaceToken,
            final boolean selectFullscreenChild)
            throws ReflectiveOperationException {
        if (taskIds == null || taskIds.length == 0) {
            throw new IllegalArgumentException("missing fullscreen plane order");
        }
        final int targetTaskId = taskIds[taskIds.length - 1];
        final boolean fullscreenForeground = planes.containsKey(
                Integer.valueOf(targetTaskId));
        if (!fullscreenForeground) {
            for (final int taskId : planeBottomReorderOrder(
                    taskIds, planes.keySet())) {
                final TaskDisplayAreaHandle plane = planes.get(
                        Integer.valueOf(taskId));
                windowing.reorder(transaction, plane.token(), false);
            }
        }
        for (final int taskId : taskIds) {
            final TaskDisplayAreaHandle plane =
                    planes.get(Integer.valueOf(taskId));
            if (plane != null) {
                if (!fullscreenForeground) {
                    continue;
                }
                final boolean active = fullscreenForeground
                        && taskId == targetTaskId;
                if (fullscreenForeground && !active) {
                    // Raising one fullscreen plane must not reorder the
                    // covered planes. Moving every inactive sibling to the
                    // bottom reverses their relative order on each cycle, so
                    // restoring a freeform window exposes a different task.
                    continue;
                }
                // Each plane remains focusable and owns exactly one fullscreen
                // root. Reordering the plane is therefore sufficient to move
                // both visual and input focus. Mutating focusability or
                // reordering the child root itself creates lifecycle effects
                // that applications can observe as a task switch.
                windowing.reorder(
                        transaction,
                        plane.token(),
                        active && fullscreenForeground);
                continue;
            }
            final Object taskToken = HiddenTaskApi.requireTaskToken(
                    service, displayId, taskId);
            if (workspaceToken != null) {
                windowing.reorder(transaction, taskToken, true, false);
                windowing.reorder(transaction, workspaceToken, true);
            } else {
                windowing.reorder(transaction, taskToken, true, true);
            }
        }
        if (fullscreenForeground && selectFullscreenChild) {
            // Raising a plane is sufficient for plane-to-plane switches and
            // keeps the child lifecycle untouched. When focus crosses an
            // ordinary workspace task, however, Nubia can retain that task's
            // input window even after selecting the fullscreen plane. Select
            // the plane's only child in the same transaction so hierarchy and
            // InputDispatcher focus commit together.
            final Object targetTaskToken = HiddenTaskApi.requireTaskToken(
                    service, displayId, targetTaskId);
            windowing.reorder(
                    transaction, targetTaskToken, true, true);
        }
    }

    static boolean crossesFullscreenPlaneBoundary(
            final int[] taskIds,
            final Set<Integer> planeTaskIds) {
        if (taskIds == null || taskIds.length == 0
                || planeTaskIds == null || planeTaskIds.isEmpty()
                || !planeTaskIds.contains(Integer.valueOf(
                        taskIds[taskIds.length - 1]))) {
            return false;
        }
        for (final int taskId : taskIds) {
            if (!planeTaskIds.contains(Integer.valueOf(taskId))) {
                return true;
            }
        }
        return false;
    }

    private void addMixedOrderOperations(
            final Object service,
            final int displayId,
            final MixedStackOrder order,
            final FrameworkWindowingApi windowing,
            final Object transaction,
            final Map<Integer, TaskDisplayAreaHandle> planes)
            throws ReflectiveOperationException {
        final TaskDisplayAreaHandle fullscreenPlane = planes.get(
                Integer.valueOf(order.fullscreenTaskId));
        if (fullscreenPlane == null) {
            throw new IllegalStateException(
                    "mixed stack lost fullscreen plane task="
                            + order.fullscreenTaskId);
        }
        final Object hostToken = HiddenTaskApi.requireTaskToken(
                service, displayId, order.desktopHostTaskId);
        windowing.reorder(transaction, hostToken, true, false);
        if (order.fullscreenForeground) {
            // Native root tasks can cross the desktop host in Z-order without
            // changing parent or windowing mode. Keep every explicit blocker
            // below the opaque host, then raise the selected fullscreen plane.
            for (int index = order.freeformTaskIds.length - 1;
                    index >= 0;
                    index--) {
                final Object taskToken = HiddenTaskApi.requireTaskToken(
                        service,
                        displayId,
                        order.freeformTaskIds[index]);
                windowing.reorder(transaction, taskToken, false, false);
            }
            windowing.reorder(transaction, fullscreenPlane.token(), true);
            final Object fullscreenTaskToken =
                    HiddenTaskApi.requireTaskToken(
                            service, displayId, order.fullscreenTaskId);
            windowing.reorder(
                    transaction, fullscreenTaskToken, true, true);
            return;
        }
        windowing.reorder(transaction, fullscreenPlane.token(), true);
        for (final int taskId : order.freeformTaskIds) {
            // Freeform roots follow the normal AOSP ordering path. The target
            // is last in this back-to-front list, so the same atomic reorder
            // selects it without relaunching or changing its geometry.
            windowing.reorder(
                    transaction,
                    HiddenTaskApi.requireTaskToken(
                            service, displayId, taskId),
                    true,
                    false);
        }
    }


    private void applySurfaceOrder(
            final int[] taskIds,
            final Map<Integer, TaskDisplayAreaHandle> planes)
            throws ReflectiveOperationException {
        applySurfaceLayers(
                surfaceLayers(taskIds, planes.keySet(), false), planes);
    }

    private void applySurfaceOrderBelowWorkspace(
            final List<Integer> taskIds,
            final Map<Integer, TaskDisplayAreaHandle> planes)
            throws ReflectiveOperationException {
        applySurfaceLayers(
                surfaceLayers(toIntArray(taskIds), planes.keySet(), true),
                planes);
    }

    private static int[] toIntArray(final List<Integer> taskIds) {
        final int[] order = new int[taskIds.size()];
        for (int index = 0; index < taskIds.size(); index++) {
            order[index] = taskIds.get(index).intValue();
        }
        return order;
    }

    static Map<Integer, Integer> surfaceLayers(
            final int[] taskIds,
            final Set<Integer> planeTaskIds,
            final boolean belowWorkspace) {
        final Map<Integer, Integer> layers = new LinkedHashMap<>();
        if (taskIds == null || planeTaskIds == null || planeTaskIds.isEmpty()) {
            return layers;
        }
        if (belowWorkspace) {
            int layer = -planeTaskIds.size();
            for (final int taskId : taskIds) {
                final Integer taskKey = Integer.valueOf(taskId);
                if (planeTaskIds.contains(taskKey)) {
                    layers.put(taskKey, Integer.valueOf(layer++));
                }
            }
            return layers;
        }
        for (int index = 0; index < taskIds.length; index++) {
            final Integer taskKey = Integer.valueOf(taskIds[index]);
            if (planeTaskIds.contains(taskKey)) {
                // Layer zero remains available to the desktop HOME root.
                layers.put(taskKey, Integer.valueOf(index + 1));
            }
        }
        return layers;
    }

    private void applySurfaceLayers(
            final Map<Integer, Integer> layers,
            final Map<Integer, TaskDisplayAreaHandle> planes)
            throws ReflectiveOperationException {
        if (layers.isEmpty() && mAvailablePlanes.isEmpty()) {
            return;
        }
        final Class<?> surfaceClass = Class.forName(
                "android.view.SurfaceControl");
        final Class<?> transactionClass = Class.forName(
                "android.view.SurfaceControl$Transaction");
        final Object transaction = transactionClass.getConstructor().newInstance();
        try {
            for (final Map.Entry<Integer, Integer> entry : layers.entrySet()) {
                final TaskDisplayAreaHandle plane = planes.get(entry.getKey());
                transactionClass.getMethod(
                        "setLayer", surfaceClass, Integer.TYPE)
                        .invoke(
                                transaction,
                                plane.surfaceLeash(),
                                entry.getValue());
            }
            int idleLayer = -mPlaneAnchorTaskIds.size();
            for (final TaskDisplayAreaHandle plane : mAvailablePlanes) {
                transactionClass.getMethod(
                        "setLayer", surfaceClass, Integer.TYPE)
                        .invoke(
                                transaction,
                                plane.surfaceLeash(),
                                Integer.valueOf(idleLayer++));
            }
            applyCommittedSurfaceTransaction(
                    transactionClass, transaction);
        } finally {
            transactionClass.getMethod("close").invoke(transaction);
        }
    }

    private static void applyCommittedSurfaceTransaction(
            final Class<?> transactionClass,
            final Object transaction) throws ReflectiveOperationException {
        final Class<?> listenerClass = Class.forName(
                "android.view.SurfaceControl$TransactionCommittedListener");
        final CountDownLatch committed = new CountDownLatch(1);
        final Object listener = Proxy.newProxyInstance(
                listenerClass.getClassLoader(),
                new Class<?>[]{listenerClass},
                (proxy, method, arguments) -> {
                    final String name = method.getName();
                    if ("onTransactionCommitted".equals(name)) {
                        committed.countDown();
                        return null;
                    }
                    if ("toString".equals(name)) {
                        return "MagicDeskSurfaceCommitListener";
                    }
                    if ("hashCode".equals(name)) {
                        return Integer.valueOf(
                                System.identityHashCode(proxy));
                    }
                    if ("equals".equals(name)) {
                        return Boolean.valueOf(arguments != null
                                && arguments.length == 1
                                && proxy == arguments[0]);
                    }
                    return null;
                });
        final Executor directExecutor = Runnable::run;
        transactionClass.getMethod(
                "addTransactionCommittedListener",
                Executor.class,
                listenerClass).invoke(
                        transaction, directExecutor, listener);
        transactionClass.getMethod("apply").invoke(transaction);
        final boolean completed;
        try {
            completed = committed.await(
                    SURFACE_COMMIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "surface transaction commit interrupted", error);
        }
        if (!completed) {
            throw new IllegalStateException(
                    "surface transaction commit timed out");
        }
    }

    private void closeWithSuccessor(
            final Object service,
            final int displayId,
            final int closingTaskId,
            final int focusTaskId)
            throws IOException, ReflectiveOperationException {
        final TaskDisplayAreaHandle closingPlane = mPlanes.get(
                Integer.valueOf(closingTaskId));
        final TaskDisplayAreaHandle focusPlane = mPlanes.get(
                Integer.valueOf(focusTaskId));
        if (closingPlane == null) {
            throw new IllegalStateException(
                    "closing fullscreen plane is unavailable");
        }

        // Move input focus while the closing task still has a valid window.
        // Removing it in the same WCT can leave InputDispatcher on its stale
        // application token even though WindowManager selects the successor.
        applyPlaneExitFocus(
                service, displayId, closingPlane, focusTaskId);
        waitForInputFocus(displayId, focusTaskId);

        final FrameworkWindowingApi windowing =
                FrameworkRuntime.current().windowing();
        final Class<?> transactionClass = windowing.transactionClass();
        final Object closeTransaction = windowing.newTransaction();
        final Object closingToken = HiddenTaskApi.requireTaskToken(
                service, displayId, closingTaskId);
        final Object focusToken = HiddenTaskApi.requireTaskToken(
                service, displayId, focusTaskId);
        windowing.removeTask(closeTransaction, closingToken);
        windowing.reorder(closeTransaction, focusToken, true, true);
        ShellWindowTransitionExecutor.applySynchronized(
                service, transactionClass, closeTransaction);
        retirePlaneRecord(closingTaskId);
        if (focusPlane != null) {
            mPlaneOrder.remove(Integer.valueOf(focusTaskId));
            mPlaneOrder.add(Integer.valueOf(focusTaskId));
            applySurfaceOrder(toIntArray(mPlaneOrder), mPlanes);
        } else {
            applySurfaceOrderBelowWorkspace(mPlaneOrder, mPlanes);
        }
    }

    private void applyPlaneExitFocus(
            final Object service,
            final int displayId,
            final TaskDisplayAreaHandle plane,
            final int focusTaskId) throws ReflectiveOperationException {
        final FrameworkWindowingApi windowing =
                FrameworkRuntime.current().windowing();
        final Class<?> transactionClass = windowing.transactionClass();
        final Object transaction = windowing.newTransaction();
        addPlaneExitFocusOperations(
                service,
                displayId,
                plane,
                focusTaskId,
                windowing,
                transaction);
        ShellWindowTransitionExecutor.applyAtomic(
                service, transactionClass, transaction);
        if (mPlanes.containsKey(Integer.valueOf(focusTaskId))) {
            mPlaneOrder.remove(Integer.valueOf(focusTaskId));
            mPlaneOrder.add(Integer.valueOf(focusTaskId));
        }
    }

    private void addPlaneExitFocusOperations(
            final Object service,
            final int displayId,
            final TaskDisplayAreaHandle plane,
            final int focusTaskId,
            final FrameworkWindowingApi windowing,
            final Object transaction) throws ReflectiveOperationException {
        requirePlaneAnchor(service, displayId, plane);
        windowing.setFocusable(transaction, plane.token(), false);
        windowing.reorder(transaction, plane.token(), false);
        if (focusTaskId < 0) {
            return;
        }
        final TaskDisplayAreaHandle focusPlane = mPlanes.get(
                Integer.valueOf(focusTaskId));
        if (focusPlane != null) {
            windowing.setFocusable(transaction, focusPlane.token(), true);
            windowing.reorder(transaction, focusPlane.token(), true);
        }
        windowing.reorder(
                transaction,
                HiddenTaskApi.requireTaskToken(
                        service, displayId, focusTaskId),
                true,
                true);
    }

    private static void waitForInputFocus(
            final int displayId, final int taskId) throws IOException {
        final String lastInput = BoundedStateAwaiter.awaitIo(
                BoundedStateAwaiter.Reason.INPUT_FOCUS,
                2_000L,
                25L,
                ShellFullscreenTaskPlanes::readLocalInputState,
                input -> TaskInputWindowParser.isTaskFocused(
                        input, displayId, taskId));
        if (TaskInputWindowParser.isTaskFocused(
                lastInput, displayId, taskId)) {
            return;
        }
        throw new IOException("surviving fullscreen task did not receive "
                + "input focus before close: task=" + taskId
                + ", display=" + displayId + "; "
                + TaskInputWindowParser.describeFocus(lastInput, displayId));
    }

    private static String readLocalInputState() throws IOException {
        try {
            return FrameworkInputSnapshotSource.readLocal();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException(
                    "fullscreen close focus wait interrupted", error);
        }
    }

    private void focusDesktopHost(
            final Object service,
            final int displayId,
            final int hostTaskId,
            final int[] requestedTaskIds) throws ReflectiveOperationException {
        final FrameworkWindowingApi windowing =
                FrameworkRuntime.current().windowing();
        final Class<?> transactionClass = windowing.transactionClass();
        final Object transaction = windowing.newTransaction();
        for (final int taskId : planeBottomReorderOrder(
                toIntArray(mPlaneOrder), mPlanes.keySet())) {
            final TaskDisplayAreaHandle plane = mPlanes.get(
                    Integer.valueOf(taskId));
            windowing.setFocusable(transaction, plane.token(), false);
            windowing.reorder(transaction, plane.token(), false);
        }
        for (int index = requestedTaskIds.length - 1;
                index >= 0;
                index--) {
            final int taskId = requestedTaskIds[index];
            if (taskId == hostTaskId
                    || mPlanes.containsKey(Integer.valueOf(taskId))) {
                continue;
            }
            windowing.reorder(
                    transaction,
                    HiddenTaskApi.requireTaskToken(
                            service, displayId, taskId),
                    false,
                    false);
        }
        final Object hostToken = HiddenTaskApi.requireTaskToken(
                service, displayId, hostTaskId);
        windowing.reorder(transaction, hostToken, true, true);
        ShellWindowTransitionExecutor.applyAtomic(
                service, transactionClass, transaction);
        applySurfaceOrderBelowWorkspace(mPlaneOrder, mPlanes);
        Log.i(TAG, "fullscreen planes display=" + displayId
                + " tasks=" + mPlanes.keySet()
                + " target=host order=" + requestedTaskIds.length);
    }

    private static List<Integer> desktopFullscreenTasks(
            final Object service,
            final int displayId,
            final ShellDesktopTaskOwnership ownership)
            throws ReflectiveOperationException {
        final List<Integer> taskIds = new ArrayList<>();
        for (final Object task : HiddenTaskApi.getTasks(service, displayId)) {
            final int taskId = HiddenTaskApi.getTaskId(task);
            if (!ownership.isDesktopHostTask(taskId)
                    && !TaskAreaBackstopActivity.isBackstopComponent(
                            HiddenTaskApi.getTaskComponent(task))
                    && ownership.isDesktopTask(task)
                    && HiddenTaskApi.getTaskWindowingMode(task)
                            == WINDOWING_MODE_FULLSCREEN) {
                // Running tasks are top-first; WCT ordering is bottom-first.
                taskIds.add(0, Integer.valueOf(taskId));
            }
        }
        return taskIds;
    }

    private static List<Integer> visibleFreeformTaskIds(
            final Object service,
            final int displayId,
            final int excludedTaskId,
            final ShellDesktopTaskOwnership ownership)
            throws ReflectiveOperationException {
        final List<Integer> topFirst = new ArrayList<>();
        for (final Object task : HiddenTaskApi.getTasks(service, displayId)) {
            final int taskId = HiddenTaskApi.getTaskId(task);
            if (taskId == excludedTaskId
                    || ownership.isDesktopHostTask(taskId)
                    || TaskAreaBackstopActivity.isBackstopComponent(
                            HiddenTaskApi.getTaskComponent(task))
                    || !ownership.isDesktopTask(task)
                    || HiddenTaskApi.getTaskWindowingMode(task)
                            != WINDOWING_MODE_FREEFORM) {
                continue;
            }
            if (HiddenTaskApi.isTaskVisible(task)) {
                topFirst.add(Integer.valueOf(taskId));
            }
        }
        Collections.reverse(topFirst);
        return topFirst;
    }

    private static boolean isDesktopFocusTarget(
            final Object task,
            final int taskId,
            final ShellDesktopTaskOwnership ownership)
            throws ReflectiveOperationException {
        return task != null
                && !TaskAreaBackstopActivity.isBackstopComponent(
                        HiddenTaskApi.getTaskComponent(task))
                && (ownership.isDesktopHostTask(taskId)
                        || ownership.isDesktopTask(task));
    }

    private int findRemovalFocusTarget(
            final Object service,
            final int displayId,
            final int excludedTaskId,
            final ShellDesktopTaskOwnership ownership)
            throws ReflectiveOperationException {
        final int hostTaskId = ownership.desktopHostTaskId();
        final List<?> tasks = HiddenTaskApi.getTasks(service, displayId);
        for (final Object task : tasks) {
            final int taskId = HiddenTaskApi.getTaskId(task);
            if (taskId != excludedTaskId
                    && mPlanes.containsKey(Integer.valueOf(taskId))
                    && isDesktopFocusTarget(task, taskId, ownership)) {
                return taskId;
            }
        }
        for (final Object task : tasks) {
            final int taskId = HiddenTaskApi.getTaskId(task);
            if (taskId == excludedTaskId
                    || mPlanes.containsKey(Integer.valueOf(taskId))) {
                continue;
            }
            if (taskId == hostTaskId) {
                return isDesktopFocusTarget(task, taskId, ownership)
                        ? taskId : -1;
            }
            if (isDesktopFocusTarget(task, taskId, ownership)) {
                return taskId;
            }
        }
        final Object hostTask = HiddenTaskApi.findTask(
                service, displayId, hostTaskId);
        return isDesktopFocusTarget(hostTask, hostTaskId, ownership)
                ? hostTaskId : -1;
    }

    private TaskDisplayAreaHandle findAnchorPlane(final int anchorTaskId) {
        for (final Map.Entry<TaskDisplayAreaHandle, Integer> entry
                : mPlaneAnchorTaskIds.entrySet()) {
            if (entry.getValue().intValue() == anchorTaskId) {
                return entry.getKey();
            }
        }
        return null;
    }

    private int findPlaneTaskId(final TaskDisplayAreaHandle plane) {
        for (final Map.Entry<Integer, TaskDisplayAreaHandle> entry
                : mPlanes.entrySet()) {
            if (entry.getValue() == plane) {
                return entry.getKey().intValue();
            }
        }
        return -1;
    }

    private void discardStalePlaneRecords(
            final Object service,
            final int displayId) throws ReflectiveOperationException {
        final Map<Integer, Integer> taskFeatureIds = new LinkedHashMap<>();
        for (final Object task : HiddenTaskApi.getTasks(service, displayId)) {
            final Integer taskId = Integer.valueOf(
                    HiddenTaskApi.getTaskId(task));
            taskFeatureIds.put(
                    taskId,
                    Integer.valueOf(HiddenTaskApi.getTaskDisplayAreaFeatureId(task)));
        }
        for (final Map.Entry<Integer, TaskDisplayAreaHandle> entry
                : new ArrayList<>(mPlanes.entrySet())) {
            final Integer featureId = taskFeatureIds.get(entry.getKey());
            if (featureId == null
                    || featureId.intValue() != entry.getValue().featureId()) {
                removeTask(service, entry.getKey().intValue());
            }
        }
    }

    private void removeTask(final Object service, final int taskId) {
        releasePlane(service, taskId);
    }

    private void releasePlane(final Object service, final int taskId) {
        final TaskDisplayAreaHandle plane = retirePlaneRecord(taskId);
        if (plane == null) {
            return;
        }
        if (service == null || mDisplayId < 0) {
            return;
        }
        try {
            requirePlaneAnchor(service, mDisplayId, plane);
            final FrameworkWindowingApi windowing =
                    FrameworkRuntime.current().windowing();
            final Class<?> transactionClass = windowing.transactionClass();
            final Object transaction = windowing.newTransaction();
            windowing.setFocusable(transaction, plane.token(), false);
            windowing.reorder(transaction, plane.token(), false);
            // Wait until WindowManager has committed focusability and
            // hierarchy order before restoring the retained surface layers.
            // Otherwise its later surface transaction can overwrite the
            // fullscreen peer order that we apply below.
            ShellWindowTransitionExecutor.applySynchronized(
                    service, transactionClass, transaction);
            // The hierarchy reorder above can also disturb sibling surface
            // layers. Reassert active and idle slot layers together so the
            // most recently exposed fullscreen peer remains underneath the
            // restored freeform task without an intermediate wrong frame.
            if (hasFocusedPlaneTask(service, mDisplayId)) {
                applySurfaceOrder(toIntArray(mPlaneOrder), mPlanes);
            } else {
                applySurfaceOrderBelowWorkspace(mPlaneOrder, mPlanes);
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not park fullscreen slot feature="
                    + plane.featureId(), error);
        }
    }

    private TaskDisplayAreaHandle retirePlaneRecord(final int taskId) {
        mPlaneOrder.remove(Integer.valueOf(taskId));
        final TaskDisplayAreaHandle plane =
                mPlanes.remove(Integer.valueOf(taskId));
        if (plane != null && !mAvailablePlanes.contains(plane)) {
            mAvailablePlanes.add(plane);
        }
        return plane;
    }

    private boolean hasFocusedPlaneTask(
            final Object service,
            final int displayId) throws ReflectiveOperationException {
        for (final Integer taskId : mPlanes.keySet()) {
            final Object task = HiddenTaskApi.findTask(
                    service, displayId, taskId.intValue());
            if (task != null && HiddenTaskApi.isTaskFocused(task)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized void close() {
        final Object service = mService;
        final int displayId = mDisplayId;
        final Map<Integer, Integer> anchorFeatureIds = new LinkedHashMap<>();
        for (final Map.Entry<TaskDisplayAreaHandle, Integer> entry
                : mPlaneAnchorTaskIds.entrySet()) {
            anchorFeatureIds.put(
                    entry.getValue(),
                    Integer.valueOf(entry.getKey().featureId()));
        }
        for (final Map.Entry<Integer, TaskDisplayAreaHandle> entry
                : new ArrayList<>(mPlanes.entrySet())) {
            final Integer taskId = entry.getKey();
            final TaskDisplayAreaHandle plane = entry.getValue();
            if (service != null && displayId >= 0) {
                try {
                    plane.detachChildTasks(
                            service,
                            displayId,
                            Collections.singleton(taskId),
                            null);
                } catch (ReflectiveOperationException | RuntimeException error) {
                    Log.w(TAG, "could not detach fullscreen plane task="
                            + taskId, error);
                }
            }
        }
        for (final Map.Entry<TaskDisplayAreaHandle, Integer> entry
                : new ArrayList<>(mPlaneAnchorTaskIds.entrySet())) {
            if (service != null && displayId >= 0) {
                entry.getKey().closeIfOnlyOwnedChildren(
                        service,
                        displayId,
                        Collections.singleton(entry.getValue()));
            }
        }
        // Android migrates standard tasks to the default display when a
        // display disappears. If that happens before observer cleanup, the
        // organizer area is already gone but its structural anchor can
        // survive as an ordinary phone task. Remove only anchors whose task
        // ids and components still match this owner.
        removeMigratedAnchorTasks(service, anchorFeatureIds);
        mPlanes.clear();
        mPlaneAnchorTaskIds.clear();
        mAvailablePlanes.clear();
        mPlaneOrder.clear();
        mService = null;
        mNextPlaneSlotId = 0;
        mConcealedForShowDesktop = false;
    }


    private static void removeMigratedAnchorTasks(
            final Object service,
            final Map<Integer, Integer> anchorFeatureIds) {
        if (service == null || anchorFeatureIds == null
                || anchorFeatureIds.isEmpty()) {
            return;
        }
        try {
            for (final Object task : HiddenTaskApi.getAllTasks(service)) {
                final Integer taskId = Integer.valueOf(
                        HiddenTaskApi.getTaskId(task));
                final Integer expectedFeatureId = anchorFeatureIds.get(taskId);
                if (expectedFeatureId == null
                        || !TaskAreaBackstopActivity.isBackstopComponent(
                                HiddenTaskApi.getTaskComponent(task))) {
                    continue;
                }
                final int actualFeatureId = HiddenTaskApi.getTaskDisplayAreaFeatureId(task);
                if (actualFeatureId == expectedFeatureId.intValue()) {
                    // The owned area is still alive. Removing its structural
                    // anchor separately would recreate the empty-area crash
                    // this task exists to prevent.
                    continue;
                }
                if (!TaskControlCommand.removeTask(
                        service, taskId.intValue())) {
                    Log.w(TAG, "could not remove migrated fullscreen slot "
                            + "anchor task=" + taskId);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not remove migrated fullscreen slot anchors="
                    + anchorFeatureIds.keySet(), error);
        }
    }
}

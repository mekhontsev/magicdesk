package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Keeps non-phone fullscreen tasks on stable, independently reordered planes. */
final class ShellFullscreenTaskPlanes implements AutoCloseable {
    private static final String TAG = "MagicDeskFullscreenPlanes";
    private static final int ACTIVITY_TYPE_STANDARD = 1;
    private static final int WINDOWING_MODE_FULLSCREEN = 1;

    private final Map<Integer, TaskDisplayAreaHandle> mPlanes =
            new LinkedHashMap<>();
    private final Map<TaskDisplayAreaHandle, Integer> mPlaneAnchorTaskIds =
            new LinkedHashMap<>();
    private final List<TaskDisplayAreaHandle> mAvailablePlanes =
            new ArrayList<>();
    private final List<Integer> mPlaneOrder = new ArrayList<>();
    private Object mService;
    private int mDisplayId = -1;
    private int mParentFeatureId;
    private Object mReleaseParentToken;
    private int mNextPlaneSlotId;

    synchronized void configure(
            final int displayId,
            final int parentFeatureId,
            final Object releaseParentToken) {
        if (displayId < 0) {
            close();
            mDisplayId = -1;
            mParentFeatureId = 0;
            mReleaseParentToken = null;
            mNextPlaneSlotId = 0;
            return;
        }
        if (mDisplayId != displayId
                || mParentFeatureId != parentFeatureId
                || mReleaseParentToken != releaseParentToken) {
            close();
        }
        mDisplayId = displayId;
        mParentFeatureId = parentFeatureId;
        mReleaseParentToken = releaseParentToken;
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
            return ShellFullscreenTaskArea.FocusResult.FULLSCREEN_FOREGROUND;
        }
        if (fullscreenTaskIds.isEmpty() && mPlanes.isEmpty()) {
            return ShellFullscreenTaskArea.FocusResult.NOT_HANDLED;
        }
        applyStableOrder(
                service,
                displayId,
                fullscreenTaskIds,
                requestedTaskIds,
                -1,
                false);
        return mPlanes.containsKey(Integer.valueOf(targetTaskId))
                ? ShellFullscreenTaskArea.FocusResult.FULLSCREEN_FOREGROUND
                : ShellFullscreenTaskArea.FocusResult.SESSION_FOREGROUND;
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
                    true);
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
            // The slot's standard anchor remains in the source area while the
            // application is reparented. Nubia's mirror implementation tears
            // down the display when a live task area becomes empty.
            ShellPreparedTaskTransition.detachAndShowFreeform(
                    service,
                    displayId,
                    taskId,
                    bounds,
                    mReleaseParentToken);
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
                        mParentFeatureId,
                        "MagicDesk fullscreen slot " + mNextPlaneSlotId++);
        int anchorTaskId = -1;
        try {
            anchorTaskId = TaskDisplayAreaLaunchCommand
                    .launchFullscreenTask(
                            service,
                            displayId,
                            TaskAreaBackstopActivity.createIntent(
                                    "fullscreen-slot:" + displayId + ':'
                                            + plane.featureId()),
                            BuildConfig.APPLICATION_ID,
                            Class.forName(
                                    "android.window.WindowContainerToken"),
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
                || HiddenTaskApi.getIntField(
                        anchor, "displayAreaFeatureId") != plane.featureId()) {
            throw new IllegalStateException(
                    "fullscreen slot lost its anchor feature="
                            + plane.featureId());
        }
        return anchorTaskId.intValue();
    }

    private static boolean isStandardBackstop(final Object task)
            throws ReflectiveOperationException {
        return task != null
                && TaskAreaBackstopActivity.isBackstopComponent(
                        HiddenTaskApi.getTaskComponent(task))
                && HiddenTaskApi.getWindowConfigurationValue(
                        task, "getActivityType") == ACTIVITY_TYPE_STANDARD;
    }

    private static void waitForTaskInsidePlane(
            final Object service,
            final int displayId,
            final int taskId,
            final int planeFeatureId) throws ReflectiveOperationException {
        final long deadline = android.os.SystemClock.uptimeMillis() + 5_000L;
        int observedFeatureId = Integer.MIN_VALUE;
        do {
            final Object task = HiddenTaskApi.requireTask(
                    service, displayId, taskId);
            observedFeatureId = HiddenTaskApi.getIntField(
                    task, "displayAreaFeatureId");
            if (observedFeatureId == planeFeatureId) {
                return;
            }
            android.os.SystemClock.sleep(50L);
        } while (android.os.SystemClock.uptimeMillis() < deadline);
        throw new IllegalStateException(
                "task did not enter fullscreen plane feature="
                        + planeFeatureId + ", observed=" + observedFeatureId);
    }

    private static void waitForTaskOutsidePlane(
            final Object service,
            final int displayId,
            final int taskId,
            final int planeFeatureId) throws ReflectiveOperationException {
        final long deadline = android.os.SystemClock.uptimeMillis() + 5_000L;
        int observedFeatureId = planeFeatureId;
        do {
            final Object task = HiddenTaskApi.requireTask(
                    service, displayId, taskId);
            observedFeatureId = HiddenTaskApi.getIntField(
                    task, "displayAreaFeatureId");
            if (observedFeatureId != planeFeatureId) {
                return;
            }
            android.os.SystemClock.sleep(50L);
        } while (android.os.SystemClock.uptimeMillis() < deadline);
        throw new IllegalStateException(
                "task remained in fullscreen plane feature="
                        + observedFeatureId);
    }

    synchronized boolean closeTask(
            final Object service,
            final int displayId,
            final int taskId,
            final ShellDesktopTaskOwnership ownership) {
        if (displayId != mDisplayId || !ownsTask(taskId)) {
            return false;
        }
        try {
            final int survivorTaskId = findFullscreenSurvivor(
                    service, displayId, taskId, ownership);
            if (survivorTaskId < 0) {
                return false;
            }
            closeWithSurvivor(
                    service, displayId, taskId, survivorTaskId);
            removeTask(service, taskId);
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
            final boolean forceEnteringFullscreen)
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

        final Class<?> tokenClass =
                Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object transaction =
                transactionClass.getConstructor().newInstance();
        try {
            for (final Map.Entry<Integer, TaskDisplayAreaHandle> entry
                    : acquiredPlanes.entrySet()) {
                final Object planeToken = entry.getValue().token();
                transactionClass.getMethod(
                        "setFocusable", tokenClass, Boolean.TYPE)
                        .invoke(
                                transaction,
                                planeToken,
                                Boolean.TRUE);
                transactionClass.getMethod(
                        "setWindowingMode", tokenClass, Integer.TYPE)
                        .invoke(
                                transaction,
                                planeToken,
                                Integer.valueOf(WINDOWING_MODE_FULLSCREEN));
                final Object taskToken = HiddenTaskApi.requireTaskToken(
                        service, displayId, entry.getKey().intValue());
                transactionClass.getMethod(
                        "reparent", tokenClass, tokenClass, Boolean.TYPE)
                        .invoke(
                                transaction,
                                taskToken,
                                planeToken,
                                Boolean.TRUE);
            }
            if (forceEnteringFullscreen) {
                final Object taskToken = HiddenTaskApi.requireTaskToken(
                        service, displayId, enteringTaskId);
                transactionClass.getMethod(
                        "setWindowingMode", tokenClass, Integer.TYPE)
                        .invoke(
                                transaction,
                                taskToken,
                                Integer.valueOf(WINDOWING_MODE_FULLSCREEN));
                transactionClass.getMethod("setBounds", tokenClass, Rect.class)
                        .invoke(transaction, taskToken, new Rect());
                TaskCaptionInsetsCommand.addCaptionInsetOperation(
                        transactionClass,
                        transaction,
                        tokenClass,
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
            addOrderOperations(
                    service,
                    displayId,
                    stableOrder,
                    transactionClass,
                    transaction,
                    tokenClass,
                    effectivePlanes,
                    mReleaseParentToken);
            if (forceEnteringFullscreen) {
                final Object enteringTaskToken =
                        HiddenTaskApi.requireTaskToken(
                                service, displayId, enteringTaskId);
                // Select the application after its plane is raised. Doing this
                // earlier lets the plane's structural anchor retain focus when
                // the transaction is finally applied by WMShell.
                transactionClass.getMethod(
                        "reorder",
                        tokenClass,
                        Boolean.TYPE,
                        Boolean.TYPE)
                        .invoke(
                                transaction,
                                enteringTaskToken,
                                Boolean.TRUE,
                                Boolean.TRUE);
                ShellWindowTransitionExecutor.playSystemTransition(
                        displayId,
                        ShellWindowTransitionExecutor.SystemTransition.CHANGE,
                        transactionClass,
                        transaction,
                        "enter-fullscreen-plane");
            } else {
                ShellWindowTransitionExecutor.applyAtomic(
                        service, transactionClass, transaction);
                applySurfaceOrder(stableOrder, effectivePlanes);
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

    private static void addOrderOperations(
            final Object service,
            final int displayId,
            final int[] taskIds,
            final Class<?> transactionClass,
            final Object transaction,
            final Class<?> tokenClass,
            final Map<Integer, TaskDisplayAreaHandle> planes,
            final Object workspaceToken)
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
                transactionClass.getMethod(
                        "reorder", tokenClass, Boolean.TYPE)
                        .invoke(
                                transaction,
                                plane.token(),
                                Boolean.FALSE);
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
                transactionClass.getMethod(
                        "reorder", tokenClass, Boolean.TYPE)
                        .invoke(
                                transaction,
                                plane.token(),
                                Boolean.valueOf(
                                        active && fullscreenForeground));
                continue;
            }
            final Object taskToken = HiddenTaskApi.requireTaskToken(
                    service, displayId, taskId);
            if (workspaceToken != null) {
                transactionClass.getMethod(
                        "reorder", tokenClass, Boolean.TYPE, Boolean.TYPE)
                        .invoke(
                                transaction,
                                taskToken,
                                Boolean.TRUE,
                                Boolean.FALSE);
                transactionClass.getMethod(
                        "reorder", tokenClass, Boolean.TYPE)
                        .invoke(
                                transaction,
                                workspaceToken,
                                Boolean.TRUE);
            } else {
                transactionClass.getMethod(
                        "reorder", tokenClass, Boolean.TYPE, Boolean.TYPE)
                        .invoke(
                                transaction,
                                taskToken,
                                Boolean.TRUE,
                                Boolean.TRUE);
            }
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
            transactionClass.getMethod("apply").invoke(transaction);
        } finally {
            transactionClass.getMethod("close").invoke(transaction);
        }
    }

    private void closeWithSurvivor(
            final Object service,
            final int displayId,
            final int closingTaskId,
            final int survivorTaskId)
            throws IOException, ReflectiveOperationException {
        final Class<?> tokenClass = Class.forName(
                "android.window.WindowContainerToken");
        final Class<?> transactionClass = Class.forName(
                "android.window.WindowContainerTransaction");
        final Object focusTransaction =
                transactionClass.getConstructor().newInstance();
        for (final Map.Entry<Integer, TaskDisplayAreaHandle> entry
                : mPlanes.entrySet()) {
            final boolean survivor = entry.getKey().intValue()
                    == survivorTaskId;
            final Object planeToken = entry.getValue().token();
            transactionClass.getMethod(
                    "reorder", tokenClass, Boolean.TYPE)
                    .invoke(
                            focusTransaction,
                            planeToken,
                            Boolean.valueOf(survivor));
        }
        if (!mPlanes.containsKey(Integer.valueOf(survivorTaskId))) {
            final Object survivorToken = HiddenTaskApi.requireTaskToken(
                    service, displayId, survivorTaskId);
            transactionClass.getMethod(
                    "reorder", tokenClass, Boolean.TYPE, Boolean.TYPE)
                    .invoke(
                            focusTransaction,
                            survivorToken,
                            Boolean.TRUE,
                            Boolean.TRUE);
        }
        ShellWindowTransitionExecutor.applyAtomic(
                service, transactionClass, focusTransaction);
        mPlaneOrder.remove(Integer.valueOf(survivorTaskId));
        mPlaneOrder.add(Integer.valueOf(survivorTaskId));
        applySurfaceOrder(toIntArray(mPlaneOrder), mPlanes);
        waitForInputFocus(displayId, survivorTaskId);

        final Object closeTransaction =
                transactionClass.getConstructor().newInstance();
        final Object closingToken = HiddenTaskApi.requireTaskToken(
                service, displayId, closingTaskId);
        transactionClass.getMethod("removeTask", tokenClass)
                .invoke(closeTransaction, closingToken);
        ShellWindowTransitionExecutor.applySynchronized(
                service, transactionClass, closeTransaction);
    }

    private static void waitForInputFocus(
            final int displayId, final int taskId) throws IOException {
        final long deadline = android.os.SystemClock.uptimeMillis() + 2_000L;
        String lastInput = "";
        do {
            try {
                lastInput = InputStateDump.read();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException(
                        "fullscreen close focus wait interrupted", error);
            }
            if (TaskInputWindowParser.isTaskFocused(
                    lastInput, displayId, taskId)) {
                return;
            }
            android.os.SystemClock.sleep(25L);
        } while (android.os.SystemClock.uptimeMillis() < deadline);
        throw new IOException("surviving fullscreen task did not receive "
                + "input focus before close: task=" + taskId
                + ", display=" + displayId + "; "
                + TaskInputWindowParser.describeFocus(lastInput, displayId));
    }

    private void focusDesktopHost(
            final Object service,
            final int displayId,
            final int hostTaskId,
            final int[] requestedTaskIds) throws ReflectiveOperationException {
        final Class<?> tokenClass = Class.forName(
                "android.window.WindowContainerToken");
        final Class<?> transactionClass = Class.forName(
                "android.window.WindowContainerTransaction");
        final Object transaction =
                transactionClass.getConstructor().newInstance();
        for (final int taskId : planeBottomReorderOrder(
                toIntArray(mPlaneOrder), mPlanes.keySet())) {
            final TaskDisplayAreaHandle plane = mPlanes.get(
                    Integer.valueOf(taskId));
            transactionClass.getMethod(
                    "reorder", tokenClass, Boolean.TYPE)
                    .invoke(transaction, plane.token(), Boolean.FALSE);
        }
        if (mReleaseParentToken != null) {
            transactionClass.getMethod(
                    "reorder", tokenClass, Boolean.TYPE)
                    .invoke(transaction, mReleaseParentToken, Boolean.TRUE);
        }
        final Object hostToken = HiddenTaskApi.requireTaskToken(
                service, displayId, hostTaskId);
        transactionClass.getMethod(
                "reorder", tokenClass, Boolean.TYPE, Boolean.TYPE)
                .invoke(
                        transaction,
                        hostToken,
                        Boolean.TRUE,
                        Boolean.TRUE);
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
            final int taskId = HiddenTaskApi.getIntField(task, "taskId");
            if (!ownership.isDesktopHostTask(taskId)
                    && !TaskAreaBackstopActivity.isBackstopComponent(
                            HiddenTaskApi.getTaskComponent(task))
                    && ownership.isDesktopTask(task)
                    && HiddenTaskApi.getWindowConfigurationValue(
                            task, "getWindowingMode")
                            == WINDOWING_MODE_FULLSCREEN) {
                // Running tasks are top-first; WCT ordering is bottom-first.
                taskIds.add(0, Integer.valueOf(taskId));
            }
        }
        return taskIds;
    }

    private int findFullscreenSurvivor(
            final Object service,
            final int displayId,
            final int closingTaskId,
            final ShellDesktopTaskOwnership ownership)
            throws ReflectiveOperationException {
        for (final Object task : HiddenTaskApi.getTasks(service, displayId)) {
            final int taskId = HiddenTaskApi.getIntField(task, "taskId");
            if (taskId != closingTaskId
                    && !ownership.isDesktopHostTask(taskId)
                    && !TaskAreaBackstopActivity.isBackstopComponent(
                            HiddenTaskApi.getTaskComponent(task))
                    && ownership.isDesktopTask(task)
                    && HiddenTaskApi.getWindowConfigurationValue(
                            task, "getWindowingMode")
                            == WINDOWING_MODE_FULLSCREEN) {
                return taskId;
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
                    HiddenTaskApi.getIntField(task, "taskId"));
            taskFeatureIds.put(
                    taskId,
                    Integer.valueOf(HiddenTaskApi.getIntField(
                            task, "displayAreaFeatureId")));
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
        mPlaneOrder.remove(Integer.valueOf(taskId));
        final TaskDisplayAreaHandle plane =
                mPlanes.remove(Integer.valueOf(taskId));
        if (plane == null) {
            return;
        }
        if (!mAvailablePlanes.contains(plane)) {
            mAvailablePlanes.add(plane);
        }
        if (service == null || mDisplayId < 0) {
            return;
        }
        try {
            requirePlaneAnchor(service, mDisplayId, plane);
            final Class<?> tokenClass = Class.forName(
                    "android.window.WindowContainerToken");
            final Class<?> transactionClass = Class.forName(
                    "android.window.WindowContainerTransaction");
            final Object transaction =
                    transactionClass.getConstructor().newInstance();
            transactionClass.getMethod(
                    "setFocusable", tokenClass, Boolean.TYPE)
                    .invoke(transaction, plane.token(), Boolean.FALSE);
            transactionClass.getMethod(
                    "reorder", tokenClass, Boolean.TYPE)
                    .invoke(transaction, plane.token(), Boolean.FALSE);
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
            applySurfaceOrder(toIntArray(mPlaneOrder), mPlanes);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not park fullscreen slot feature="
                    + plane.featureId(), error);
        }
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
                            mReleaseParentToken);
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
                        HiddenTaskApi.getIntField(task, "taskId"));
                final Integer expectedFeatureId = anchorFeatureIds.get(taskId);
                if (expectedFeatureId == null
                        || !TaskAreaBackstopActivity.isBackstopComponent(
                                HiddenTaskApi.getTaskComponent(task))) {
                    continue;
                }
                final int actualFeatureId = HiddenTaskApi.getIntField(
                        task, "displayAreaFeatureId");
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

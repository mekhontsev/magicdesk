package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps a reordered stack of true fullscreen tasks in a fullscreen parent.
 *
 * <p>The dedicated parent is the invariant: reordering the same tasks in the
 * active freeform-oriented desktop parent lets some firmware resolve them as
 * freeform. Do not replace this with a delayed fullscreen repair. See
 * {@code docs/architecture.md#keep-true-fullscreen-tasks-under-one-fullscreen-parent}.
 */
@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
final class ShellFullscreenTaskArea implements AutoCloseable {
    private static final String TAG = "MagicDeskFullscreenArea";
    private static final int FEATURE_ROOT = 0;
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final long VISIBILITY_TIMEOUT_MILLIS = 3_000L;
    private static final long VISIBILITY_POLL_MILLIS = 20L;

    private final Set<Integer> mTaskIds = new HashSet<>();
    private final Map<Integer, Rect> mAppRestoreBounds = new HashMap<>();
    private final ShellDesktopTaskOwnership mOwnership;

    private TaskDisplayAreaHandle mArea;
    private Object mAreaService;
    private int mDisplayId = -1;
    private int mConfiguredDisplayId = -1;
    private int mParentFeatureId = FEATURE_ROOT;
    private Object mReleaseParentToken;

    ShellFullscreenTaskArea(final ShellDesktopTaskOwnership ownership) {
        if (ownership == null) {
            throw new IllegalArgumentException(
                    "desktop task ownership is required");
        }
        mOwnership = ownership;
    }

    synchronized boolean focusStack(
            final Object service,
            final int displayId,
            final int[] taskIds) {
        try {
            if (taskIds == null || taskIds.length == 0) {
                return false;
            }
            final int targetTaskId = taskIds[taskIds.length - 1];
            final Object targetTask = HiddenTaskApi.requireTask(
                    service, displayId, targetTaskId);
            if (mOwnership.isDesktopHostTask(targetTaskId)
                    || !mOwnership.isDesktopTask(targetTask)) {
                return false;
            }
            final int[] focusTaskIds = desktopFocusTasks(
                    service, displayId, taskIds);
            final int[] appTaskIds = withoutDesktopHost(focusTaskIds);
            if (!isFullscreenStack(service, displayId, appTaskIds)) {
                if (focusMixedStack(
                        service, displayId, focusTaskIds)) {
                    return true;
                }
                // A freeform task may be focused while another task remains
                // in this area. Its own mode/display/removal events own the
                // area's lifetime; unrelated focus requests do not.
                return false;
            }
            ensureArea(service, displayId);
            applyFocus(service, displayId, appTaskIds, focusTaskIds);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "fullscreen task area unavailable", error);
            close();
            return false;
        }
    }

    private boolean focusMixedStack(
            final Object service,
            final int displayId,
            final int[] focusTaskIds) throws ReflectiveOperationException {
        boolean containsNonFullscreenTask = false;
        for (final int taskId : focusTaskIds) {
            final Object task = HiddenTaskApi.requireTask(
                    service, displayId, taskId);
            if (HiddenTaskApi.getWindowConfigurationValue(
                    task, "getWindowingMode")
                    != WINDOWING_MODE_FULLSCREEN) {
                containsNonFullscreenTask = true;
                break;
            }
        }
        if (!containsNonFullscreenTask) {
            return false;
        }

        final List<Integer> fullscreenTaskIds = new ArrayList<>();
        for (final Object task : HiddenTaskApi.getTasks(service, displayId)) {
            final int taskId = HiddenTaskApi.getIntField(task, "taskId");
            if (HiddenTaskApi.getWindowConfigurationValue(
                    task, "getWindowingMode")
                    != WINDOWING_MODE_FULLSCREEN
                    || !mOwnership.isDesktopTask(task)
                    || mOwnership.isDesktopHostTask(taskId)) {
                continue;
            }
            fullscreenTaskIds.add(Integer.valueOf(taskId));
        }
        if (fullscreenTaskIds.isEmpty()) {
            return false;
        }

        ensureArea(service, displayId);
        final Class<?> tokenClass =
                Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass = Class.forName(
                "android.window.WindowContainerTransaction");
        final Object transaction =
                transactionClass.getConstructor().newInstance();
        final Object areaToken = mArea.token();
        for (final Integer fullscreenTaskId : fullscreenTaskIds) {
            final int taskId = fullscreenTaskId.intValue();
            final Object taskToken = HiddenTaskApi.requireTaskToken(
                    service, displayId, taskId);
            transactionClass.getMethod(
                    "setWindowingMode", tokenClass, Integer.TYPE)
                    .invoke(transaction, taskToken,
                            Integer.valueOf(WINDOWING_MODE_FULLSCREEN));
            transactionClass.getMethod("setBounds", tokenClass, Rect.class)
                    .invoke(transaction, taskToken, new Rect());
            if (mTaskIds.add(fullscreenTaskId)) {
                transactionClass.getMethod(
                        "reparent", tokenClass, tokenClass, Boolean.TYPE)
                        .invoke(transaction, taskToken, areaToken, Boolean.TRUE);
            }
        }
        // Reparenting and focus must be one queued WMShell transition. Applying
        // the hierarchy synchronously before TO_FRONT can deadlock against the
        // transition that caused this focus request on projection firmware.
        TaskWindowingCommand.focusTasks(
                service,
                displayId,
                focusTaskIds,
                transactionClass,
                transaction);
        Log.i(TAG, "preserved fullscreen tasks=" + fullscreenTaskIds
                + " while focusing mixed stack on display=" + displayId);
        return true;
    }

    private int[] desktopFocusTasks(
            final Object service,
            final int displayId,
            final int[] taskIds) throws ReflectiveOperationException {
        final List<Integer> desktopTaskIds = new ArrayList<>();
        for (final int taskId : taskIds) {
            final Object task = HiddenTaskApi.requireTask(
                    service, displayId, taskId);
            if (mOwnership.isDesktopHostTask(taskId)
                    || mOwnership.isDesktopTask(task)) {
                desktopTaskIds.add(Integer.valueOf(taskId));
            }
        }
        final int[] output = new int[desktopTaskIds.size()];
        for (int index = 0; index < desktopTaskIds.size(); index++) {
            output[index] = desktopTaskIds.get(index).intValue();
        }
        return output;
    }

    private int[] withoutDesktopHost(final int[] taskIds) {
        int appTaskCount = 0;
        for (final int taskId : taskIds) {
            if (!mOwnership.isDesktopHostTask(taskId)) {
                appTaskCount++;
            }
        }
        if (appTaskCount == taskIds.length) {
            return taskIds;
        }
        final int[] appTaskIds = new int[appTaskCount];
        int outputIndex = 0;
        for (final int taskId : taskIds) {
            if (!mOwnership.isDesktopHostTask(taskId)) {
                appTaskIds[outputIndex++] = taskId;
            }
        }
        return appTaskIds;
    }

    synchronized boolean beginAppFullscreen(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect restoreBounds) {
        if (restoreBounds == null || restoreBounds.isEmpty()) {
            return false;
        }
        try {
            if (mDisplayId >= 0 && mDisplayId != displayId) {
                close();
            }
            mDisplayId = displayId;
            final Class<?> tokenClass =
                    Class.forName("android.window.WindowContainerToken");
            final Class<?> transactionClass = Class.forName(
                    "android.window.WindowContainerTransaction");
            final Object transaction =
                    transactionClass.getConstructor().newInstance();
            final Object taskToken = HiddenTaskApi.requireTaskToken(
                    service, displayId, taskId);
            transactionClass.getMethod(
                    "setWindowingMode", tokenClass, Integer.TYPE)
                    .invoke(transaction, taskToken,
                            Integer.valueOf(WINDOWING_MODE_FULLSCREEN));
            transactionClass.getMethod("setBounds", tokenClass, Rect.class)
                    .invoke(transaction, taskToken, new Rect());
            transactionClass.getMethod(
                    "reorder", tokenClass, Boolean.TYPE)
                    .invoke(transaction, taskToken, Boolean.TRUE);
            TaskCaptionInsetsCommand.addCaptionInsetOperation(
                    transactionClass,
                    transaction,
                    tokenClass,
                    taskToken,
                    true);
            // Keep app-requested fullscreen directly under the active desktop
            // parent. The dedicated child is needed only when several
            // fullscreen tasks are reordered; some projection displays reject
            // a lone task moved under an organizer-created child.
            TaskFullscreenTransitionCommand.startTransition(
                    transactionClass, transaction);
            mAppRestoreBounds.put(
                    Integer.valueOf(taskId), new Rect(restoreBounds));
            Log.i(TAG, "entered app fullscreen task=" + taskId
                    + " display=" + displayId);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "app fullscreen task area unavailable task="
                    + taskId, error);
            mAppRestoreBounds.remove(Integer.valueOf(taskId));
            if (mTaskIds.isEmpty()) {
                close();
            }
            return false;
        }
    }

    synchronized boolean beginFullscreen(
            final Object service,
            final int displayId,
            final int taskId,
            final boolean refreshCaption) {
        if (mArea == null || mDisplayId != displayId
                || mTaskIds.isEmpty()) {
            return false;
        }
        try {
            final int captionSourceId = refreshCaption
                    ? TaskCaptionInsetsRefresher.captureCaptionSourceId(taskId)
                    : TaskLocalInsetsSourceParser.NO_SOURCE_ID;
            final Class<?> tokenClass =
                    Class.forName("android.window.WindowContainerToken");
            final Class<?> transactionClass = Class.forName(
                    "android.window.WindowContainerTransaction");
            final Object transaction =
                    transactionClass.getConstructor().newInstance();
            final Object taskToken = HiddenTaskApi.requireTaskToken(
                    service, displayId, taskId);
            transactionClass.getMethod(
                    "setWindowingMode", tokenClass, Integer.TYPE)
                    .invoke(transaction, taskToken,
                            Integer.valueOf(WINDOWING_MODE_FULLSCREEN));
            transactionClass.getMethod("setBounds", tokenClass, Rect.class)
                    .invoke(transaction, taskToken, new Rect());
            transactionClass.getMethod(
                    "reparent", tokenClass, tokenClass, Boolean.TYPE)
                    .invoke(transaction, taskToken,
                            mArea.token(), Boolean.TRUE);
            TaskCaptionInsetsCommand.addCaptionInsetOperation(
                    transactionClass,
                    transaction,
                    tokenClass,
                    taskToken,
                    true);
            mTaskIds.add(Integer.valueOf(taskId));
            TaskWindowingCommand.focusTasks(
                    service,
                    displayId,
                    new int[]{taskId},
                    transactionClass,
                    transaction);
            TaskFullscreenTransitionCommand.refreshCaptionIfRequested(
                    service,
                    displayId,
                    taskId,
                    refreshCaption,
                    captionSourceId);
            Log.i(TAG, "entered managed fullscreen task=" + taskId
                    + " display=" + displayId);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "managed fullscreen transition failed task="
                    + taskId, error);
            return false;
        }
    }

    synchronized boolean restoreAppFullscreen(
            final Object service,
            final int displayId,
            final int taskId) {
        final Rect restoreBounds = mAppRestoreBounds.get(
                Integer.valueOf(taskId));
        if (restoreBounds == null || displayId != mDisplayId) {
            return false;
        }
        boolean hidden = false;
        try {
            // Firmware may already have nominally changed the task to freeform.
            // Re-establish a hidden fullscreen boundary, detaching only when
            // our organizer parent owns the task, then reveal only the
            // canonical freeform geometry.
            final boolean detachFromFullscreenParent =
                    mTaskIds.contains(Integer.valueOf(taskId));
            if (detachFromFullscreenParent) {
                ShellPreparedTaskTransition.prepareDetachedFullscreen(
                        service,
                        displayId,
                        taskId,
                        mReleaseParentToken);
            } else {
                ShellPreparedTaskTransition.prepareFullscreen(
                        service, displayId, taskId);
            }
            hidden = true;
            TaskDisplayAreaLaunchCommand.waitForTaskVisibility(
                    service, displayId, taskId, false);
            forgetAppFullscreenTask(taskId);
            ShellPreparedTaskTransition.showPreparedFreeform(
                    service, displayId, taskId, restoreBounds);
            TaskDisplayAreaLaunchCommand.waitForTaskFreeformBounds(
                    service, displayId, taskId, restoreBounds);
            hidden = false;
            Log.i(TAG, "restored app fullscreen task=" + taskId
                    + " display=" + displayId);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "app fullscreen restore failed task=" + taskId, error);
            forgetAppFullscreenTask(taskId);
            if (hidden) {
                try {
                    ShellPreparedTaskTransition.restorePreparedTask(
                            service,
                            displayId,
                            taskId,
                            WINDOWING_MODE_FREEFORM,
                            restoreBounds);
                    return true;
                } catch (ReflectiveOperationException
                        | RuntimeException restoreError) {
                    error.addSuppressed(restoreError);
                }
            }
            return false;
        }
    }

    private void forgetAppFullscreenTask(final int taskId) {
        mAppRestoreBounds.remove(Integer.valueOf(taskId));
        mTaskIds.remove(Integer.valueOf(taskId));
        if (mTaskIds.isEmpty()) {
            close();
        }
    }

    private void applyFocus(
            final Object service,
            final int displayId,
            final int[] appTaskIds,
            final int[] focusTaskIds) throws ReflectiveOperationException {
        final Class<?> tokenClass =
                Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object transaction = transactionClass.getConstructor().newInstance();
        final Object areaToken = mArea.token();
        for (final int taskId : appTaskIds) {
            final Object taskToken = HiddenTaskApi.requireTaskToken(
                    service, displayId, taskId);
            transactionClass.getMethod(
                    "setWindowingMode", tokenClass, Integer.TYPE)
                    .invoke(transaction, taskToken,
                            Integer.valueOf(WINDOWING_MODE_FULLSCREEN));
            transactionClass.getMethod("setBounds", tokenClass, Rect.class)
                    .invoke(transaction, taskToken, new Rect());
            if (mTaskIds.add(Integer.valueOf(taskId))) {
                transactionClass.getMethod(
                        "reparent", tokenClass, tokenClass, Boolean.TYPE)
                        .invoke(transaction, taskToken, areaToken, Boolean.TRUE);
            }
        }
        // Include hierarchy preservation and focus ordering in the same
        // transition so WMShell serializes both against native transitions.
        TaskWindowingCommand.focusTasks(
                service,
                displayId,
                focusTaskIds,
                transactionClass,
                transaction);
    }

    private boolean isFullscreenStack(
            final Object service,
            final int displayId,
            final int[] taskIds) throws ReflectiveOperationException {
        if (taskIds == null || taskIds.length < 2) {
            return false;
        }
        for (final int taskId : taskIds) {
            final Object task = HiddenTaskApi.findTask(
                    service, displayId, taskId);
            if (task == null
                    || HiddenTaskApi.getWindowConfigurationValue(
                            task, "getWindowingMode")
                            != WINDOWING_MODE_FULLSCREEN) {
                return false;
            }
        }
        return true;
    }

    synchronized boolean restoreTask(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect bounds) {
        if (mArea == null || mDisplayId != displayId
                || !mTaskIds.contains(Integer.valueOf(taskId))
                || bounds == null || bounds.isEmpty()) {
            return false;
        }
        try {
            ShellPreparedTaskTransition.detachAndShowFreeform(
                    service,
                    displayId,
                    taskId,
                    bounds,
                    mReleaseParentToken);
            mAppRestoreBounds.remove(Integer.valueOf(taskId));
            mTaskIds.remove(Integer.valueOf(taskId));
            // The detach is part of an asynchronous WMShell transition. Keep
            // the now-empty organizer area alive until the observer closes or
            // reuses it; deleting its parent here can race the same transition.
            Log.i(TAG, "restored fullscreen task=" + taskId
                    + " display=" + displayId);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "failed to restore fullscreen task=" + taskId, error);
            try {
                // Preserve the old two-step fallback only when the atomic
                // detach-and-restore transaction itself is unavailable.
                ShellPreparedTaskTransition.detachFullscreenParent(
                        service,
                        displayId,
                        taskId,
                        mReleaseParentToken);
                mAppRestoreBounds.remove(Integer.valueOf(taskId));
                mTaskIds.remove(Integer.valueOf(taskId));
                if (mTaskIds.isEmpty()) {
                    close();
                }
            } catch (ReflectiveOperationException
                    | RuntimeException detachError) {
                error.addSuppressed(detachError);
            }
            return false;
        }
    }

    synchronized boolean closeTask(
            final Object service,
            final int displayId,
            final int taskId) {
        if (mArea == null || mDisplayId != displayId
                || !mTaskIds.contains(Integer.valueOf(taskId))) {
            return false;
        }
        final int survivorTaskId;
        try {
            survivorTaskId = findTopSurvivor(service, displayId, taskId);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "failed to find fullscreen close survivor", error);
            return false;
        }
        if (survivorTaskId < 0) {
            return false;
        }

        try {
            // Hand visibility to the survivor before destroying the old top
            // task. This reuses the proven fullscreen-area focus path and
            // makes the subsequent removal a background operation.
            applyFocus(
                    service,
                    displayId,
                    new int[]{survivorTaskId},
                    new int[]{survivorTaskId});
            waitForVisibleTask(service, displayId, survivorTaskId);

            final Class<?> tokenClass =
                    Class.forName("android.window.WindowContainerToken");
            final Class<?> transactionClass = Class.forName(
                    "android.window.WindowContainerTransaction");
            final Object transaction =
                    transactionClass.getConstructor().newInstance();
            final Object closingToken = HiddenTaskApi.requireTaskToken(
                    service, displayId, taskId);
            transactionClass.getMethod("removeTask", tokenClass)
                    .invoke(transaction, closingToken);
            SyncWindowContainerTransaction.apply(
                    service, transactionClass, transaction);
            mTaskIds.remove(Integer.valueOf(taskId));
            mAppRestoreBounds.remove(Integer.valueOf(taskId));
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "fullscreen close handoff failed task="
                    + taskId, error);
            return false;
        }
        Log.i(TAG, "closed fullscreen task=" + taskId
                + " survivor=" + survivorTaskId
                + " display=" + displayId);
        return true;
    }

    private int findTopSurvivor(
            final Object service,
            final int displayId,
            final int closingTaskId) throws ReflectiveOperationException {
        // ActivityTaskManager returns running tasks in top-first order.
        for (final Object task : HiddenTaskApi.getTasks(service, displayId)) {
            final int candidateTaskId = HiddenTaskApi.getIntField(
                    task, "taskId");
            if (candidateTaskId != closingTaskId
                    && mTaskIds.contains(Integer.valueOf(candidateTaskId))
                    && HiddenTaskApi.getWindowConfigurationValue(
                            task, "getWindowingMode")
                            == WINDOWING_MODE_FULLSCREEN) {
                return candidateTaskId;
            }
        }
        return -1;
    }

    private static void waitForVisibleTask(
            final Object service,
            final int displayId,
            final int taskId) throws ReflectiveOperationException {
        final long deadline = SystemClock.uptimeMillis()
                + VISIBILITY_TIMEOUT_MILLIS;
        do {
            final Object task = HiddenTaskApi.findTask(
                    service, displayId, taskId);
            if (task != null
                    && HiddenTaskApi.getBooleanField(task, "isVisible")) {
                return;
            }
            SystemClock.sleep(VISIBILITY_POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IllegalStateException(
                "fullscreen survivor did not become visible task=" + taskId);
    }

    private void ensureArea(
            final Object service,
            final int displayId) throws ReflectiveOperationException {
        if (mArea != null && mDisplayId == displayId) {
            return;
        }
        if (mArea != null || (mDisplayId >= 0 && mDisplayId != displayId)) {
            close();
        }

        if (mConfiguredDisplayId >= 0
                && displayId != mConfiguredDisplayId) {
            throw new IllegalStateException(
                    "fullscreen parent is not configured for display "
                            + displayId);
        }
        final TaskDisplayAreaHandle area = TaskDisplayAreaHandle.create(
                displayId,
                mParentFeatureId,
                "MagicDesk fullscreen stack");
        final Object areaToken = area.token();
        try {
            final Class<?> tokenClass =
                    Class.forName("android.window.WindowContainerToken");
            final Class<?> transactionClass =
                    Class.forName("android.window.WindowContainerTransaction");
            final Object transaction =
                    transactionClass.getConstructor().newInstance();
            transactionClass.getMethod(
                    "setWindowingMode", tokenClass, Integer.TYPE)
                    .invoke(transaction, areaToken,
                            Integer.valueOf(WINDOWING_MODE_FULLSCREEN));
            SyncWindowContainerTransaction.apply(
                    service, transactionClass, transaction);
        } catch (ReflectiveOperationException | RuntimeException error) {
            area.closeIfEmpty(service, displayId);
            throw error;
        }

        mArea = area;
        mAreaService = service;
        mDisplayId = displayId;
        Log.i(TAG, "created fullscreen task area display=" + displayId);
    }

    synchronized void onWindowingModeChanged(
            final int displayId,
            final int taskId,
            final int windowingMode) {
        if (displayId == mDisplayId
                && mTaskIds.contains(Integer.valueOf(taskId))
                && !mAppRestoreBounds.containsKey(Integer.valueOf(taskId))
                && windowingMode != WINDOWING_MODE_FULLSCREEN) {
            close();
        }
    }

    synchronized void onTaskRemoved(final int taskId) {
        mAppRestoreBounds.remove(Integer.valueOf(taskId));
        mTaskIds.remove(Integer.valueOf(taskId));
        if (mTaskIds.isEmpty() && mAppRestoreBounds.isEmpty()) {
            close();
        }
    }

    synchronized void onTaskDisplayChanged(
            final int taskId,
            final int displayId) {
        if (displayId != mDisplayId) {
            onTaskRemoved(taskId);
        }
    }

    synchronized void configure(
            final int displayId,
            final int parentFeatureId,
            final Object releaseParentToken) {
        if (displayId < 0) {
            close();
            mConfiguredDisplayId = -1;
            mParentFeatureId = FEATURE_ROOT;
            mReleaseParentToken = null;
            return;
        }
        if (parentFeatureId < 0) {
            throw new IllegalArgumentException(
                    "invalid fullscreen parent feature");
        }
        if (mConfiguredDisplayId != displayId
                || mParentFeatureId != parentFeatureId
                || mReleaseParentToken != releaseParentToken) {
            close();
        }
        mConfiguredDisplayId = displayId;
        mParentFeatureId = parentFeatureId;
        mReleaseParentToken = releaseParentToken;
    }

    @Override
    public synchronized void close() {
        final TaskDisplayAreaHandle area = mArea;
        final Object service = mAreaService;
        final int displayId = mDisplayId;
        final Set<Integer> ownedTaskIds = new HashSet<>(mTaskIds);
        mArea = null;
        mAreaService = null;
        mDisplayId = -1;
        mTaskIds.clear();
        mAppRestoreBounds.clear();
        if (area != null) {
            try {
                // A failed or interrupted window transition can leave a task
                // under this organizer area. Deleting a non-empty area
                // corrupts parent links on some firmware.
                // Dynamic feature IDs are reused and can remain in stale
                // Recents metadata. Only this area's own live task IDs are
                // safe to inspect and detach.
                area.detachChildTasks(
                        service,
                        displayId,
                        ownedTaskIds,
                        mReleaseParentToken);
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.w(TAG, "could not detach fullscreen tasks before cleanup",
                        error);
            }
            if (!area.closeIfEmpty(service, displayId)) {
                Log.w(TAG, "fullscreen task area retained after unsafe cleanup"
                        + " feature=" + area.featureId());
            }
        }
    }
}

package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Keeps true fullscreen tasks in a fullscreen parent while their order changes.
 *
 * <p>The dedicated parent is the invariant: reordering the same tasks in the
 * default desktop task area lets some firmware resolve them as freeform. Do
 * not replace this with a delayed fullscreen repair. See
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

    private TaskDisplayAreaHandle mArea;
    private int mDisplayId = -1;

    synchronized boolean focusStack(
            final Object service,
            final int displayId,
            final int[] taskIds) {
        try {
            if (!isFullscreenStack(service, displayId, taskIds)) {
                // A freeform task may be focused while another task remains
                // in this area. Its own mode/display/removal events own the
                // area's lifetime; unrelated focus requests do not.
                return false;
            }
            ensureArea(service, displayId);
            applyFocus(service, displayId, taskIds);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "fullscreen task area unavailable", error);
            close();
            return false;
        }
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
            ensureArea(service, displayId);
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
            if (!mTaskIds.contains(Integer.valueOf(taskId))) {
                transactionClass.getMethod(
                        "reparent", tokenClass, tokenClass, Boolean.TYPE)
                        .invoke(transaction, taskToken, mArea.token(), Boolean.TRUE);
            } else {
                transactionClass.getMethod(
                        "reorder", tokenClass, Boolean.TYPE)
                        .invoke(transaction, taskToken, Boolean.TRUE);
            }
            TaskCaptionInsetsCommand.addCaptionInsetOperation(
                    transactionClass,
                    transaction,
                    tokenClass,
                    taskToken,
                    true);
            // The long-lived observer owns the parent and the matching restore.
            // A one-shot command cannot keep this hierarchy stable while the
            // application later leaves immersive mode.
            TaskFullscreenTransitionCommand.startTransition(
                    transactionClass, transaction);
            mTaskIds.add(Integer.valueOf(taskId));
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
            // Re-establish a hidden fullscreen boundary while detaching it from
            // our parent, then reveal only the canonical freeform geometry.
            ShellPreparedTaskTransition.prepareDetachedFullscreen(
                    service, displayId, taskId);
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
            final int[] taskIds) throws ReflectiveOperationException {
        final Class<?> tokenClass =
                Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object transaction = transactionClass.getConstructor().newInstance();
        final Object areaToken = mArea.token();
        for (final int taskId : taskIds) {
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
            } else {
                transactionClass.getMethod(
                        "reorder", tokenClass, Boolean.TYPE)
                        .invoke(transaction, taskToken, Boolean.TRUE);
            }
        }
        SyncWindowContainerTransaction.apply(
                service, transactionClass, transaction);
        // The synchronous hierarchy update does not always move
        // InputDispatcher focus. Once every task has the fullscreen parent,
        // a normal TO_FRONT activation can synchronize input without letting
        // the default freeform task area change either task's mode.
        TaskWindowingCommand.focusTasks(service, displayId, taskIds);
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
                    service, displayId, taskId, bounds);
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
                        service, displayId, taskId);
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
                    service, displayId, new int[]{survivorTaskId});
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
        close();

        final TaskDisplayAreaHandle area = TaskDisplayAreaHandle.create(
                displayId, FEATURE_ROOT, "MagicDesk fullscreen stack");
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
            area.close();
            throw error;
        }

        mArea = area;
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
        if (mTaskIds.remove(Integer.valueOf(taskId)) && mTaskIds.isEmpty()) {
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

    synchronized void configure(final int displayId) {
        if (mDisplayId >= 0 && displayId != mDisplayId) {
            close();
        }
    }

    @Override
    public synchronized void close() {
        final TaskDisplayAreaHandle area = mArea;
        mArea = null;
        mDisplayId = -1;
        mTaskIds.clear();
        mAppRestoreBounds.clear();
        if (area != null) {
            area.close();
        }
    }
}

package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Coordinates stable per-task fullscreen planes on one desktop. */
final class ShellFullscreenTaskArea implements AutoCloseable {
    interface FullscreenTaskStarter {
        int start(Object taskAreaToken) throws ReflectiveOperationException;
    }

    enum FocusResult {
        NOT_HANDLED,
        WORKSPACE_FOREGROUND,
        FULLSCREEN_FOREGROUND
    }

    enum CloseResult {
        NOT_HANDLED,
        SUCCEEDED,
        FAILED
    }

    private static final String TAG = "MagicDeskFullscreenTopology";
    private static final int WINDOWING_MODE_FULLSCREEN = 1;

    private final ShellDesktopTaskOwnership mOwnership;
    private final ShellFullscreenTaskPlanes mPlanes;
    private final Map<Integer, Rect> mAppRestoreBounds = new HashMap<>();

    private int mDisplayId = -1;

    ShellFullscreenTaskArea(
            final ShellDesktopTaskOwnership ownership,
            final ShellDesktopSurfaceOrder surfaceOrder) {
        if (ownership == null) {
            throw new IllegalArgumentException(
                    "desktop task ownership is required");
        }
        mOwnership = ownership;
        mPlanes = new ShellFullscreenTaskPlanes(surfaceOrder);
    }

    synchronized ShellFullscreenTaskArea.FocusResult focusStack(
            final Object service,
            final int displayId,
            final int[] taskIds) {
        if (displayId != mDisplayId) {
            return ShellFullscreenTaskArea.FocusResult.NOT_HANDLED;
        }
        try {
            if (taskIds == null || taskIds.length == 0) {
                return ShellFullscreenTaskArea.FocusResult.NOT_HANDLED;
            }
            final int targetTaskId = taskIds[taskIds.length - 1];
            final Object targetTask = HiddenTaskApi.requireTask(
                    service, displayId, targetTaskId);
            if (!mOwnership.isDesktopHostTask(targetTaskId)
                    && !mOwnership.isDesktopTask(targetTask)) {
                return ShellFullscreenTaskArea.FocusResult.NOT_HANDLED;
            }
            final int[] desktopTaskIds = desktopFocusTasks(
                    service, displayId, taskIds);
            if (desktopTaskIds.length == 0) {
                return ShellFullscreenTaskArea.FocusResult.NOT_HANDLED;
            }
            return mPlanes.focusStack(
                    service, displayId, desktopTaskIds, mOwnership);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "fullscreen plane focus unavailable", error);
            return ShellFullscreenTaskArea.FocusResult.NOT_HANDLED;
        }
    }

    synchronized boolean ownsFocusTarget(
            final Object service,
            final int displayId,
            final int taskId) throws ReflectiveOperationException {
        if (displayId != mDisplayId || taskId < 0) {
            return false;
        }
        final Object task = HiddenTaskApi.requireTask(
                service, displayId, taskId);
        return mOwnership.isDesktopHostTask(taskId)
                || mOwnership.isDesktopTask(task);
    }

    synchronized boolean concealForShowDesktop(final int displayId) {
        return mPlanes.concealForShowDesktop(displayId);
    }

    synchronized boolean beginAppFullscreen(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect restoreBounds,
            final int densityDpi) {
        if (displayId != mDisplayId || restoreBounds == null
                || restoreBounds.isEmpty()) {
            return false;
        }
        final boolean entered = mPlanes.beginFullscreen(
                service,
                displayId,
                taskId,
                false,
                densityDpi,
                mOwnership);
        if (entered) {
            mAppRestoreBounds.put(
                    Integer.valueOf(taskId), new Rect(restoreBounds));
        }
        return entered;
    }

    synchronized boolean beginFullscreen(
            final Object service,
            final int displayId,
            final int taskId,
            final boolean refreshCaption,
            final int densityDpi) {
        return displayId == mDisplayId
                && mPlanes.beginFullscreen(
                        service,
                        displayId,
                        taskId,
                        refreshCaption,
                        densityDpi,
                        mOwnership);
    }

    synchronized int launchFullscreen(
            final Object service,
            final int displayId,
            final FullscreenTaskStarter starter,
            final int densityDpi)
            throws ReflectiveOperationException {
        if (displayId != mDisplayId) {
            throw new IllegalArgumentException(
                    "display is not configured: " + displayId);
        }
        return mPlanes.launchFullscreen(
                service, displayId, starter, densityDpi, mOwnership);
    }

    synchronized boolean restoreTask(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect bounds,
            final int densityDpi) {
        if (displayId != mDisplayId) {
            return false;
        }
        final Integer taskKey = Integer.valueOf(taskId);
        final Rect appBounds = mAppRestoreBounds.get(taskKey);
        final Rect restoreBounds = appBounds == null ? bounds : appBounds;
        if (restoreBounds == null || restoreBounds.isEmpty()) {
            return false;
        }
        final boolean restored = mPlanes.restoreFreeform(
                service,
                displayId,
                taskId,
                restoreBounds,
                densityDpi);
        if (restored) {
            mAppRestoreBounds.remove(taskKey);
        }
        return restored;
    }

    synchronized ShellFullscreenTaskArea.CloseResult closeTask(
            final Object service,
            final int displayId,
            final int taskId,
            final int focusTaskId) {
        if (displayId != mDisplayId || !mPlanes.ownsTask(taskId)) {
            return ShellFullscreenTaskArea.CloseResult.NOT_HANDLED;
        }
        final boolean closed = mPlanes.closeTask(
                service, displayId, taskId, focusTaskId, mOwnership);
        if (closed) {
            mAppRestoreBounds.remove(Integer.valueOf(taskId));
        }
        return closed
                ? ShellFullscreenTaskArea.CloseResult.SUCCEEDED
                : ShellFullscreenTaskArea.CloseResult.FAILED;
    }

    synchronized boolean onWindowingModeChanged(
            final int displayId,
            final int taskId,
            final int windowingMode,
            final boolean focused) {
        if (displayId != mDisplayId) {
            return false;
        }
        mPlanes.onWindowingModeChanged(displayId, taskId, windowingMode);
        final Integer taskKey = Integer.valueOf(taskId);
        final boolean released =
                !focused
                        && windowingMode != WINDOWING_MODE_FULLSCREEN
                        && mAppRestoreBounds.containsKey(taskKey);
        if (released) {
            mAppRestoreBounds.remove(taskKey);
            Log.i(TAG, "released background app fullscreen task=" + taskId
                    + " display=" + displayId);
        }
        return released;
    }

    synchronized void onTaskRemovalStarted(
            final Object service,
            final int taskId) {
        mPlanes.onTaskRemovalStarted(service, taskId, mOwnership);
    }

    synchronized boolean recoverAnchorFocus(
            final Object service,
            final int taskId) {
        return mPlanes.recoverAnchorFocus(service, taskId, mOwnership);
    }

    synchronized void onTaskRemoved(final int taskId) {
        mPlanes.onTaskRemoved(taskId);
        mAppRestoreBounds.remove(Integer.valueOf(taskId));
    }

    synchronized void addDensityOperation(
            final FrameworkWindowingApi windowing,
            final Object transaction,
            final int taskId,
            final int densityDpi) throws ReflectiveOperationException {
        mPlanes.addDensityOperation(
                windowing, transaction, taskId, densityDpi);
    }

    synchronized void onTaskDisplayChanged(
            final int taskId,
            final int displayId) {
        if (displayId == mDisplayId) {
            return;
        }
        mPlanes.onTaskDisplayChanged(taskId, displayId);
        mAppRestoreBounds.remove(Integer.valueOf(taskId));
    }

    synchronized void configure(final int displayId) {
        if (displayId < 0) {
            close();
            return;
        }
        if (mDisplayId != displayId) {
            close();
        }
        mDisplayId = displayId;
        mPlanes.configure(displayId);
    }

    int[] desktopFocusTasks(
            final Object service,
            final int displayId,
            final int[] taskIds) throws ReflectiveOperationException {
        final List<Integer> output = new ArrayList<>();
        for (final int taskId : taskIds) {
            final Object task = HiddenTaskApi.requireTask(
                    service, displayId, taskId);
            if (mOwnership.isDesktopHostTask(taskId)
                    || mOwnership.isDesktopTask(task)) {
                output.add(Integer.valueOf(taskId));
            }
        }
        final int[] result = new int[output.size()];
        for (int index = 0; index < output.size(); index++) {
            result[index] = output.get(index).intValue();
        }
        // HOME partitions an explicit workspace plan. Injecting it here would
        // turn a single-task activation into replacement of its background.
        return result;
    }

    @Override
    public synchronized void close() {
        mPlanes.configure(-1);
        mAppRestoreBounds.clear();
        mDisplayId = -1;
    }
}

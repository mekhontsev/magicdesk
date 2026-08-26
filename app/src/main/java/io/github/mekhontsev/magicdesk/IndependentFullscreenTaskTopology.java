package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Coordinates stable per-task fullscreen planes on non-phone desktops. */
final class IndependentFullscreenTaskTopology
        implements ShellFullscreenTaskTopology {
    private static final String TAG = "MagicDeskFullscreenTopology";
    private static final int FEATURE_ROOT = 0;
    private static final int WINDOWING_MODE_FULLSCREEN = 1;

    private final ShellDesktopTaskOwnership mOwnership;
    private final ShellFullscreenTaskPlanes mPlanes =
            new ShellFullscreenTaskPlanes();
    private final Map<Integer, Rect> mAppRestoreBounds = new HashMap<>();

    private int mDisplayId = -1;

    IndependentFullscreenTaskTopology(
            final ShellDesktopTaskOwnership ownership) {
        if (ownership == null) {
            throw new IllegalArgumentException(
                    "desktop task ownership is required");
        }
        mOwnership = ownership;
    }

    @Override
    public synchronized ShellFullscreenTaskArea.FocusResult focusStack(
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

    @Override
    public synchronized boolean beginAppFullscreen(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect restoreBounds) {
        if (displayId != mDisplayId || restoreBounds == null
                || restoreBounds.isEmpty()) {
            return false;
        }
        final boolean entered = mPlanes.beginFullscreen(
                service, displayId, taskId, false, mOwnership);
        if (entered) {
            mAppRestoreBounds.put(
                    Integer.valueOf(taskId), new Rect(restoreBounds));
        }
        return entered;
    }

    @Override
    public synchronized boolean beginFullscreen(
            final Object service,
            final int displayId,
            final int taskId,
            final boolean refreshCaption) {
        return displayId == mDisplayId
                && mPlanes.beginFullscreen(
                        service,
                        displayId,
                        taskId,
                        refreshCaption,
                        mOwnership);
    }

    @Override
    public synchronized boolean restoreTask(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect bounds) {
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
                service, displayId, taskId, restoreBounds);
        if (restored) {
            mAppRestoreBounds.remove(taskKey);
        }
        return restored;
    }

    @Override
    public synchronized boolean closeTask(
            final Object service,
            final int displayId,
            final int taskId) {
        if (displayId != mDisplayId) {
            return false;
        }
        final boolean closed = mPlanes.closeTask(
                service, displayId, taskId, mOwnership);
        if (closed) {
            mAppRestoreBounds.remove(Integer.valueOf(taskId));
        }
        return closed;
    }

    @Override
    public synchronized boolean onWindowingModeChanged(
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

    @Override
    public synchronized void onTaskRemoved(final int taskId) {
        mPlanes.onTaskRemoved(taskId);
        mAppRestoreBounds.remove(Integer.valueOf(taskId));
    }

    @Override
    public void onTaskMovedToFront(
            final int displayId,
            final int taskId) {
        // Focus requests own hierarchy changes. Callbacks are observational.
    }

    @Override
    public void onTaskStackChanged() {
        // Plane lifetime follows task mode/removal callbacks directly.
    }

    @Override
    public synchronized void onTaskDisplayChanged(
            final int taskId,
            final int displayId) {
        if (displayId == mDisplayId) {
            return;
        }
        mPlanes.onTaskDisplayChanged(taskId, displayId);
        mAppRestoreBounds.remove(Integer.valueOf(taskId));
    }

    @Override
    public synchronized void configure(
            final int displayId,
            final DesktopTaskAreaPolicy taskAreaPolicy,
            final int parentFeatureId,
            final Object releaseParentToken) {
        if (displayId < 0) {
            close();
            return;
        }
        if (taskAreaPolicy == null
                || !taskAreaPolicy.usesIndependentFullscreenPlanes()
                || parentFeatureId < 0) {
            throw new IllegalArgumentException(
                    "invalid independent fullscreen configuration");
        }
        if (mDisplayId != displayId) {
            close();
        }
        mDisplayId = displayId;
        mPlanes.configure(
                displayId,
                parentFeatureId,
                releaseParentToken);
    }

    private int[] desktopFocusTasks(
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
        return result;
    }

    @Override
    public synchronized void close() {
        mPlanes.configure(-1, FEATURE_ROOT, null);
        mAppRestoreBounds.clear();
        mDisplayId = -1;
    }
}

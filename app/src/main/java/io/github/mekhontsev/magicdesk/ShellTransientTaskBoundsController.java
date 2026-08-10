package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.graphics.Rect;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Repairs task-surface geometry after an in-task activity changes packages. */
final class ShellTransientTaskBoundsController {
    private static final String TAG = "MagicDeskTasks";
    private static final String MAGICDESK_PACKAGE =
            "io.github.mekhontsev.magicdesk";
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final int ACTIVITY_TYPE_HOME = 2;
    private static final int RESIZE_MODE_SYSTEM = 0;
    private static final int PULSE_LEFT = 0;
    private static final int PULSE_RIGHT = 1;
    private static final int PULSE_UP = 2;
    private static final int PULSE_DOWN = 3;

    private final Object mService;
    private final Map<Integer, Rect> mStableBounds = new HashMap<>();
    private final Set<Integer> mCorrectedTransientTasks = new HashSet<>();
    private final Method mResizeTask;

    private int mDisplayId = -1;
    private Rect mDisplayBounds = new Rect();

    ShellTransientTaskBoundsController(final Object service)
            throws ReflectiveOperationException {
        mService = service;
        mResizeTask = service.getClass().getMethod(
                "resizeTask", Integer.TYPE, Rect.class, Integer.TYPE);
    }

    synchronized void configure(
            final int displayId,
            final Rect displayBounds) {
        if (displayId < 0 || displayBounds == null || displayBounds.isEmpty()) {
            throw new IllegalArgumentException("invalid display bounds");
        }
        if (mDisplayId != displayId
                || !mDisplayBounds.equals(displayBounds)) {
            mDisplayId = displayId;
            mDisplayBounds = new Rect(displayBounds);
            mStableBounds.clear();
            mCorrectedTransientTasks.clear();
        }
    }

    synchronized void clearConfiguration() {
        mDisplayId = -1;
        mDisplayBounds.setEmpty();
        mStableBounds.clear();
        mCorrectedTransientTasks.clear();
    }

    synchronized void observeTasks(
            final int displayId,
            final List<?> tasks) {
        if (displayId != mDisplayId || tasks == null) {
            return;
        }
        for (final Object task : tasks) {
            try {
                observe(task);
            } catch (ReflectiveOperationException | RuntimeException error) {
                throw new IllegalStateException(
                        "transient task bounds repair failed", error);
            }
        }
    }

    synchronized void forget(final int taskId) {
        final Integer key = Integer.valueOf(taskId);
        mStableBounds.remove(key);
        mCorrectedTransientTasks.remove(key);
    }

    synchronized void close() {
        clearConfiguration();
    }

    private void observe(final Object task) throws ReflectiveOperationException {
        final TaskState state = readTaskState(task);
        if (state == null) {
            return;
        }
        if (!state.transientTop) {
            mStableBounds.put(state.taskId, state.bounds);
            mCorrectedTransientTasks.remove(state.taskId);
            return;
        }

        final Rect stableBounds = mStableBounds.get(state.taskId);
        if (stableBounds == null || stableBounds.isEmpty()) {
            return;
        }
        if (mCorrectedTransientTasks.contains(state.taskId)) {
            return;
        }

        resynchronizeSurface(state.taskId.intValue(), stableBounds);
        mCorrectedTransientTasks.add(state.taskId);
        Log.i(TAG, "resynchronized transient task surface task=" + state.taskId
                + " stable=" + stableBounds
                + " top=" + state.topActivity.flattenToShortString());
    }

    private static TaskState readTaskState(final Object task)
            throws ReflectiveOperationException {
        if (!HiddenTaskApi.getBooleanField(task, "isVisible")
                || HiddenTaskApi.getWindowConfigurationValue(
                        task, "getWindowingMode") != WINDOWING_MODE_FREEFORM
                || HiddenTaskApi.getWindowConfigurationValue(
                        task, "getActivityType") == ACTIVITY_TYPE_HOME) {
            return null;
        }
        final ComponentName baseActivity = (ComponentName) HiddenTaskApi.getField(
                task, "baseActivity");
        final ComponentName topActivity = (ComponentName) HiddenTaskApi.getField(
                task, "topActivity");
        final Rect bounds = getBounds(task);
        if (baseActivity == null || topActivity == null || bounds.isEmpty()
                || MAGICDESK_PACKAGE.equals(baseActivity.getPackageName())) {
            return null;
        }
        return new TaskState(
                HiddenTaskApi.getIntField(task, "taskId"),
                bounds,
                topActivity,
                !baseActivity.getPackageName().equals(
                        topActivity.getPackageName()));
    }

    private static Rect getBounds(final Object task)
            throws ReflectiveOperationException {
        final Object windowConfiguration = HiddenTaskApi.getWindowConfiguration(task);
        final Rect bounds = (Rect) windowConfiguration.getClass()
                .getMethod("getBounds")
                .invoke(windowConfiguration);
        return bounds == null ? new Rect() : new Rect(bounds);
    }

    private void resynchronizeSurface(final int taskId, final Rect stableBounds)
            throws ReflectiveOperationException {
        final Rect pulseBounds = createPulseBounds(stableBounds, mDisplayBounds);
        mResizeTask.invoke(
                mService,
                Integer.valueOf(taskId),
                pulseBounds,
                Integer.valueOf(RESIZE_MODE_SYSTEM));
        mResizeTask.invoke(
                mService,
                Integer.valueOf(taskId),
                new Rect(stableBounds),
                Integer.valueOf(RESIZE_MODE_SYSTEM));
    }

    static Rect createPulseBounds(
            final Rect stableBounds,
            final Rect displayBounds) {
        if (stableBounds == null
                || stableBounds.isEmpty()
                || displayBounds == null
                || displayBounds.isEmpty()
                || !displayBounds.contains(stableBounds)) {
            throw new IllegalArgumentException("invalid pulse bounds");
        }
        final Rect pulse = new Rect(stableBounds);
        switch (selectPulseDirection(
                stableBounds.left,
                stableBounds.top,
                stableBounds.right,
                stableBounds.bottom,
                displayBounds.left,
                displayBounds.top,
                displayBounds.right,
                displayBounds.bottom)) {
            case PULSE_LEFT:
                pulse.offset(-1, 0);
                break;
            case PULSE_RIGHT:
                pulse.offset(1, 0);
                break;
            case PULSE_UP:
                pulse.offset(0, -1);
                break;
            case PULSE_DOWN:
                pulse.offset(0, 1);
                break;
            default:
                pulse.right--;
                break;
        }
        return pulse;
    }

    static int selectPulseDirection(
            final int left,
            final int top,
            final int right,
            final int bottom,
            final int displayLeft,
            final int displayTop,
            final int displayRight,
            final int displayBottom) {
        if (left > displayLeft) {
            return PULSE_LEFT;
        }
        if (right < displayRight) {
            return PULSE_RIGHT;
        }
        if (top > displayTop) {
            return PULSE_UP;
        }
        if (bottom < displayBottom) {
            return PULSE_DOWN;
        }
        return -1;
    }

    private static final class TaskState {
        final Integer taskId;
        final Rect bounds;
        final ComponentName topActivity;
        final boolean transientTop;

        TaskState(
                final int taskId,
                final Rect bounds,
                final ComponentName topActivity,
                final boolean transientTop) {
            this.taskId = Integer.valueOf(taskId);
            this.bounds = new Rect(bounds);
            this.topActivity = topActivity;
            this.transientTop = transientTop;
        }
    }
}

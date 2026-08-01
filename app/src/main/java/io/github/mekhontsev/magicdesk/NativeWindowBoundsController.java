package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.util.Log;
import android.view.Display;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class NativeWindowBoundsController {
    interface RuntimeState {
        int displayId();
        Context windowContext();
        DesktopViewport viewport();
        Rect workAreaBounds();
        void scheduleRefresh();
    }

    private static final String TAG = "MagicDeskTasks";
    private static final String MAGICDESK_PACKAGE =
            "io.github.mekhontsev.magicdesk";
    private static final int TASKBAR_RESERVE_DP = 64;

    private final Context mApplicationContext;
    private final Handler mHandler;
    private final RuntimeState mRuntimeState;
    private final Map<Integer, Rect> mLastWindowBounds = new HashMap<>();
    private final Map<Integer, Rect> mMaximizeRestoreBounds = new HashMap<>();
    private final Map<Integer, BoundsTransition> mTransitions = new HashMap<>();

    NativeWindowBoundsController(
            final Context context,
            final Handler handler,
            final RuntimeState runtimeState) {
        mApplicationContext = context.getApplicationContext();
        mHandler = handler;
        mRuntimeState = runtimeState;
    }

    void reset() {
        mLastWindowBounds.clear();
        mMaximizeRestoreBounds.clear();
        mTransitions.clear();
    }

    void forget(final int taskId) {
        final Integer key = Integer.valueOf(taskId);
        mLastWindowBounds.remove(key);
        mMaximizeRestoreBounds.remove(key);
        mTransitions.remove(key);
    }

    void clearForFullscreen(final int taskId) {
        forget(taskId);
    }

    Rect getMaximizeRestoreBounds(final int taskId) {
        final Rect bounds = mMaximizeRestoreBounds.get(Integer.valueOf(taskId));
        return bounds == null ? null : new Rect(bounds);
    }

    Rect getSnappedBounds(final boolean left) {
        final Rect workArea = getTaskbarMaximizedBounds();
        final int middle = workArea.left + workArea.width() / 2;
        return left
                ? new Rect(
                        workArea.left,
                        workArea.top,
                        middle,
                        workArea.bottom)
                : new Rect(
                        middle,
                        workArea.top,
                        workArea.right,
                        workArea.bottom);
    }

    Rect getFullscreenBounds() {
        final DesktopViewport viewport = mRuntimeState.viewport();
        if (viewport != null) {
            return viewport.displayBounds();
        }
        final int displayId = mRuntimeState.displayId();
        final DisplayManager displayManager =
                mApplicationContext.getSystemService(DisplayManager.class);
        final Display display = displayManager == null
                ? null : displayManager.getDisplay(displayId);
        if (display != null) {
            final Point size = new Point();
            getRealSize(display, size);
            if (size.x > 0 && size.y > 0) {
                return new Rect(0, 0, size.x, size.y);
            }
        }
        final Context windowContext = mRuntimeState.windowContext();
        return new Rect(
                0,
                0,
                windowContext.getResources().getDisplayMetrics().widthPixels,
                windowContext.getResources().getDisplayMetrics().heightPixels);
    }

    Rect getTaskbarMaximizedBounds() {
        final Rect workArea = mRuntimeState.workAreaBounds();
        if (workArea != null && !workArea.isEmpty()) {
            return new Rect(workArea);
        }
        final Rect bounds = getFullscreenBounds();
        bounds.bottom = Math.max(
                1,
                bounds.bottom - dp(
                        mRuntimeState.windowContext(), TASKBAR_RESERVE_DP));
        return bounds;
    }

    void requestBounds(
            final TaskRepository.TaskEntry task,
            final Rect targetBounds,
            final boolean clearsMaximizeState) {
        final Integer taskId = Integer.valueOf(task.taskId);
        final BoundsTransition transition =
                new BoundsTransition(targetBounds, clearsMaximizeState);
        mTransitions.put(taskId, transition);
        TaskRepository.resizeTaskBounds(
                task,
                targetBounds,
                result -> mHandler.post(() -> {
                    if (mTransitions.get(taskId) != transition) {
                        if (result.success) {
                            mRuntimeState.scheduleRefresh();
                        }
                        return;
                    }
                    if (!result.success) {
                        mTransitions.remove(taskId);
                        if (!clearsMaximizeState) {
                            mMaximizeRestoreBounds.remove(taskId);
                        }
                        Log.w(TAG,
                                "native bounds transition failed task="
                                        + task.taskId
                                        + " message=" + result.message);
                        return;
                    }
                    mRuntimeState.scheduleRefresh();
                }));
    }

    void reconcile(
            final List<TaskRepository.TaskEntry> tasks,
            final Set<Integer> fullscreenTransitionTasks,
            final Map<Integer, Rect> fullscreenRestoreBounds) {
        if (mRuntimeState.windowContext() == null) {
            return;
        }
        final int displayId = mRuntimeState.displayId();
        final Rect fullscreenBounds = getFullscreenBounds();
        final Rect maximizedBounds = getTaskbarMaximizedBounds();
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task == null || task.displayId != displayId || task.home
                    || MAGICDESK_PACKAGE.equals(task.packageName)
                    || !task.isFreeform() || task.bounds.isEmpty()) {
                continue;
            }
            final Integer taskId = Integer.valueOf(task.taskId);
            if (fullscreenTransitionTasks.contains(taskId)
                    || fullscreenRestoreBounds.containsKey(taskId)) {
                forget(task.taskId);
                continue;
            }

            final BoundsTransition transition = mTransitions.get(taskId);
            if (transition != null) {
                if (task.bounds.equals(transition.targetBounds)) {
                    mTransitions.remove(taskId);
                    if (transition.clearsMaximizeState) {
                        mMaximizeRestoreBounds.remove(taskId);
                        mLastWindowBounds.put(
                                taskId, new Rect(transition.targetBounds));
                    }
                }
                continue;
            }
            if (!task.visible) {
                continue;
            }

            final Rect restoreBounds = mMaximizeRestoreBounds.get(taskId);
            if (task.bounds.equals(fullscreenBounds)) {
                if (restoreBounds != null) {
                    requestBounds(task, restoreBounds, true);
                } else {
                    Rect previousBounds = mLastWindowBounds.get(taskId);
                    if (previousBounds == null || previousBounds.isEmpty()
                            || previousBounds.equals(fullscreenBounds)
                            || previousBounds.equals(maximizedBounds)) {
                        previousBounds =
                                getDefaultWindowBounds(maximizedBounds);
                    }
                    mMaximizeRestoreBounds.put(
                            taskId, new Rect(previousBounds));
                    requestBounds(task, maximizedBounds, false);
                }
                continue;
            }

            if (restoreBounds != null) {
                if (!task.bounds.equals(maximizedBounds)) {
                    Log.d(TAG,
                            "preserve native maximize task=" + task.taskId
                                    + " unexpectedBounds=" + task.bounds);
                    requestBounds(task, maximizedBounds, false);
                }
                continue;
            }

            if (task.hasCrossPackageTopActivity()) {
                continue;
            }

            if (task.bounds.equals(maximizedBounds)) {
                Rect previousBounds = mLastWindowBounds.get(taskId);
                if (previousBounds == null || previousBounds.isEmpty()) {
                    previousBounds = getDefaultWindowBounds(maximizedBounds);
                }
                mMaximizeRestoreBounds.put(
                        taskId, new Rect(previousBounds));
            } else {
                mLastWindowBounds.put(taskId, new Rect(task.bounds));
            }
        }
    }

    private Rect getDefaultWindowBounds(final Rect workArea) {
        final int width = Math.min(
                1200,
                Math.max(
                        Math.min(640, workArea.width()),
                        Math.round(workArea.width() * 0.625f)));
        final int height = Math.min(
                760,
                Math.max(
                        Math.min(420, workArea.height()),
                        Math.round(workArea.height() * 0.72f)));
        final int left =
                workArea.left + Math.max(0, (workArea.width() - width) / 2);
        final int top =
                workArea.top + Math.max(0, (workArea.height() - height) / 2);
        return new Rect(left, top, left + width, top + height);
    }

    @SuppressWarnings("deprecation")
    private static void getRealSize(final Display display, final Point size) {
        display.getRealSize(size);
    }

    private static int dp(final Context context, final int value) {
        return Math.round(
                value * context.getResources().getDisplayMetrics().density);
    }

    private static final class BoundsTransition {
        final Rect targetBounds;
        final boolean clearsMaximizeState;

        BoundsTransition(
                final Rect targetBounds,
                final boolean clearsMaximizeState) {
            this.targetBounds = new Rect(targetBounds);
            this.clearsMaximizeState = clearsMaximizeState;
        }
    }
}

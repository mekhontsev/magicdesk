package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.util.Log;
import android.view.Display;

import java.util.List;

final class NativeWindowBoundsController {
    interface RuntimeState {
        int displayId();
        Context windowContext();
        DesktopViewport viewport();
        Rect workAreaBounds();
        void scheduleRefresh();
    }

    private static final String TAG = "MagicDeskTasks";
    private static final int TASKBAR_RESERVE_DP = 64;

    private final Context mApplicationContext;
    private final Handler mHandler;
    private final DesktopTaskRuntimeRegistry mTaskStates;
    private final RuntimeState mRuntimeState;

    NativeWindowBoundsController(
            final Context context,
            final Handler handler,
            final DesktopTaskRuntimeRegistry taskStates,
            final RuntimeState runtimeState) {
        mApplicationContext = context.getApplicationContext();
        mHandler = handler;
        mTaskStates = taskStates;
        mRuntimeState = runtimeState;
    }

    void reset() {
        mTaskStates.clearNativeBoundsState();
    }

    void clearForFullscreen(final int taskId) {
        final DesktopTaskRuntimeState state = mTaskStates.find(taskId);
        if (state != null) {
            state.clearNativeBoundsState();
        }
    }

    Rect getMaximizeRestoreBounds(final int taskId) {
        final DesktopTaskRuntimeState state = mTaskStates.find(taskId);
        return state == null ? null : state.maximizeRestoreBounds();
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

    boolean isNativeCaptionSnapOutsideWorkArea(final Rect bounds) {
        return correctNativeCaptionSnapBounds(
                bounds,
                getFullscreenBounds(),
                getTaskbarMaximizedBounds()) != null;
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
        final int taskId = task.taskId;
        final DesktopTaskRuntimeState state = mTaskStates.state(taskId);
        final DesktopTaskRuntimeState.BoundsTransition transition =
                state.beginBoundsTransition(
                        targetBounds, clearsMaximizeState);
        TaskRepository.resizeTaskBounds(
                task,
                targetBounds,
                result -> mHandler.post(() -> {
                    if (!mTaskStates.isCurrent(taskId, state)
                            || !state.isBoundsTransition(transition)) {
                        if (result.success) {
                            mRuntimeState.scheduleRefresh();
                        }
                        return;
                    }
                    if (!result.success) {
                        state.clearBoundsTransition(transition);
                        if (!clearsMaximizeState) {
                            state.clearMaximizeRestoreBounds();
                        }
                        Log.w(TAG,
                                "native bounds transition failed task="
                                        + taskId
                                        + " message=" + result.message);
                        return;
                    }
                    mRuntimeState.scheduleRefresh();
                }));
    }

    void reconcile(final List<TaskRepository.TaskEntry> tasks) {
        if (mRuntimeState.windowContext() == null) {
            return;
        }
        final int displayId = mRuntimeState.displayId();
        final Rect fullscreenBounds = getFullscreenBounds();
        final Rect maximizedBounds = getTaskbarMaximizedBounds();
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task == null || task.displayId != displayId
                    || !DesktopManagedTaskPolicy
                            .isManagedApplicationTask(task)
                    || !task.isBoundedFreeform()) {
                continue;
            }
            final DesktopTaskRuntimeState state =
                    mTaskStates.state(task.taskId);
            if (state.isFullscreenTransition()
                    || state.fullscreenRestoreBounds() != null) {
                state.clearNativeBoundsState();
                continue;
            }

            final DesktopTaskRuntimeState.BoundsTransition transition =
                    state.boundsTransition();
            if (transition != null) {
                if (task.bounds.equals(transition.targetBounds())) {
                    state.clearBoundsTransition(transition);
                    if (transition.clearsMaximizeState) {
                        state.clearMaximizeRestoreBounds();
                        state.setLastWindowBounds(
                                transition.targetBounds());
                    }
                }
                continue;
            }
            if (!task.visible) {
                continue;
            }

            final Rect correctedSnapBounds =
                    correctNativeCaptionSnapBounds(
                            task.bounds,
                            fullscreenBounds,
                            maximizedBounds);
            if (correctedSnapBounds != null) {
                // Nubia's caption layout menu divides the full display and
                // ignores MagicDesk's taskbar work area. Keep the native UI,
                // but normalize its exact half-screen result like Win+Left or
                // Win+Right. Arbitrary user resizing is left untouched.
                requestBounds(task, correctedSnapBounds, true);
                continue;
            }

            final Rect restoreBounds = state.maximizeRestoreBounds();
            if (task.bounds.equals(fullscreenBounds)) {
                if (restoreBounds != null) {
                    requestBounds(task, restoreBounds, true);
                } else {
                    Rect previousBounds = state.lastWindowBounds();
                    if (previousBounds == null || previousBounds.isEmpty()
                            || previousBounds.equals(fullscreenBounds)
                            || previousBounds.equals(maximizedBounds)) {
                        previousBounds =
                                getDefaultWindowBounds(maximizedBounds);
                    }
                    state.setMaximizeRestoreBounds(previousBounds);
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
                Rect previousBounds = state.lastWindowBounds();
                if (previousBounds == null || previousBounds.isEmpty()) {
                    previousBounds = getDefaultWindowBounds(maximizedBounds);
                }
                state.setMaximizeRestoreBounds(previousBounds);
            } else {
                state.setLastWindowBounds(task.bounds);
            }
        }
    }

    static Rect correctNativeCaptionSnapBounds(
            final Rect taskBounds,
            final Rect displayBounds,
            final Rect workAreaBounds) {
        if (taskBounds == null
                || displayBounds == null
                || workAreaBounds == null
                || !TaskRepository.hasExplicitBounds(taskBounds)
                || !TaskRepository.hasExplicitBounds(displayBounds)
                || !TaskRepository.hasExplicitBounds(workAreaBounds)
                || taskBounds.top != displayBounds.top
                || taskBounds.bottom != displayBounds.bottom) {
            return null;
        }
        final int displayMiddle =
                displayBounds.left
                        + (displayBounds.right - displayBounds.left) / 2;
        final boolean left = taskBounds.left == displayBounds.left
                && taskBounds.right == displayMiddle;
        final boolean right = taskBounds.left == displayMiddle
                && taskBounds.right == displayBounds.right;
        if (!left && !right) {
            return null;
        }
        final int workAreaMiddle =
                workAreaBounds.left
                        + (workAreaBounds.right - workAreaBounds.left) / 2;
        final Rect corrected = left
                ? rect(
                        workAreaBounds.left,
                        workAreaBounds.top,
                        workAreaMiddle,
                        workAreaBounds.bottom)
                : rect(
                        workAreaMiddle,
                        workAreaBounds.top,
                        workAreaBounds.right,
                        workAreaBounds.bottom);
        return sameBounds(taskBounds, corrected) ? null : corrected;
    }

    private static boolean sameBounds(
            final Rect first,
            final Rect second) {
        return first.left == second.left
                && first.top == second.top
                && first.right == second.right
                && first.bottom == second.bottom;
    }

    private static Rect rect(
            final int left,
            final int top,
            final int right,
            final int bottom) {
        final Rect bounds = new Rect();
        bounds.left = left;
        bounds.top = top;
        bounds.right = right;
        bounds.bottom = bottom;
        return bounds;
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
}

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
                getNativeCaptionSnapArea(),
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
        requestBounds(task, targetBounds, clearsMaximizeState, null);
    }

    void requestBounds(
            final TaskRepository.TaskEntry task,
            final Rect targetBounds,
            final boolean clearsMaximizeState,
            final TaskRepository.ActionCallback callback) {
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
                        complete(callback, result);
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
                        complete(callback, result);
                        return;
                    }
                    mRuntimeState.scheduleRefresh();
                    complete(callback, result);
                }));
    }

    private static void complete(
            final TaskRepository.ActionCallback callback,
            final TaskRepository.ActionResult result) {
        if (callback != null) {
            callback.onComplete(result);
        }
    }

    void reconcile(final List<TaskRepository.TaskEntry> tasks) {
        if (mRuntimeState.windowContext() == null) {
            return;
        }
        final int displayId = mRuntimeState.displayId();
        final Rect fullscreenBounds = getFullscreenBounds();
        final Rect nativeCaptionSnapArea = getNativeCaptionSnapArea();
        final Rect maximizedBounds = getTaskbarMaximizedBounds();
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task == null || task.displayId != displayId
                    || !DesktopManagedTaskPolicy
                            .isControllableApplicationTask(task)
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
                            nativeCaptionSnapArea,
                            maximizedBounds);
            if (correctedSnapBounds != null) {
                // Some native caption menus divide Android's stable area and
                // ignore the MagicDesk taskbar. Preserve Android's horizontal
                // result, including application minimum width, and reserve
                // only the taskbar-owned vertical area.
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
            final Rect nativeSnapArea,
            final Rect workAreaBounds) {
        if (taskBounds == null
                || nativeSnapArea == null
                || workAreaBounds == null
                || !TaskRepository.hasExplicitBounds(taskBounds)
                || !TaskRepository.hasExplicitBounds(nativeSnapArea)
                || !TaskRepository.hasExplicitBounds(workAreaBounds)
                || taskBounds.top != nativeSnapArea.top
                || taskBounds.bottom != nativeSnapArea.bottom) {
            return null;
        }
        final Rect corrected = rect(
                taskBounds.left,
                taskBounds.top,
                taskBounds.right,
                taskBounds.bottom);
        corrected.top = workAreaBounds.top;
        corrected.bottom = workAreaBounds.bottom;
        return sameBounds(taskBounds, corrected) ? null : corrected;
    }

    private Rect getNativeCaptionSnapArea() {
        final DesktopViewport viewport = mRuntimeState.viewport();
        return viewport == null
                ? getFullscreenBounds()
                : viewport.contentBounds();
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

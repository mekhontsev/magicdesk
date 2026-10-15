package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/** Android task adapter for a pure layout policy; called inside the workspace queue. */
final class DesktopWindowLayoutController {
    interface Backend {
        void apply(int displayId, List<TaskRepository.TaskEntry> tasks,
                List<Rect> bounds, TaskRepository.ActionCallback callback);
    }

    private final DesktopTaskRuntimeRegistry mStates;
    private final Executor mDispatcher;
    private final Backend mBackend;
    private final WindowLayoutSession mSession;

    DesktopWindowLayoutController(DesktopTaskRuntimeRegistry states, Executor dispatcher,
            Backend backend, WindowLayoutPolicy policy) {
        mStates = states;
        mDispatcher = dispatcher;
        mBackend = backend;
        mSession = new WindowLayoutSession(policy);
    }

    void replacePolicy(WindowLayoutPolicy policy) {
        mSession.replacePolicy(policy);
        // Fullscreen residency is not a layout placement. Keep its plane and
        // change only the restore destination when the old policy is released.
        for (DesktopTaskRuntimeState state : mStates.snapshot()) {
            if (!state.layoutOwnership().isOwned()) continue;
            if (state.fullscreenRestoreBounds() != null) {
                state.setFullscreenRestoreBounds(rect(state.layoutOwnership().floatingBounds()));
            }
            state.layoutOwnership().release();
        }
    }

    WindowLayoutPolicy policy() { return mSession.policy(); }

    boolean observesWorkspace() { return mSession.observesWorkspace(); }

    boolean acceptObservation(WindowLayoutPolicy.Workspace workspace) {
        return mSession.acceptObservation(workspace);
    }

    void clearObservation() { mSession.clearObservation(); }

    WindowLayoutPolicy.Workspace workspace(int displayId, Rect workArea,
            List<TaskRepository.TaskEntry> tasks, Set<Integer> concealed, int focusedTaskId) {
        List<WindowLayoutPolicy.Window> windows = new ArrayList<>();
        for (TaskRepository.TaskEntry task : tasks) {
            if (task.displayId != displayId
                    || !DesktopManagedTaskPolicy.isControllableApplicationTask(task)) continue;
            DesktopTaskRuntimeState state = mStates.find(task.taskId);
            windows.add(new WindowLayoutPolicy.Window(task.taskId,
                    task.isBoundedFreeform() && !task.hasCrossPackageTopActivity()
                            && (state == null || !state.isFullscreenTransition()),
                    concealed.contains(task.taskId), bounds(task.bounds),
                    state == null ? null : state.layoutOwnership().floatingBounds()));
        }
        return new WindowLayoutPolicy.Workspace(displayId, bounds(workArea), windows, focusedTaskId);
    }

    WindowLayoutPolicy.Plan plan(WindowLayoutPolicy.Workspace workspace,
            WindowLayoutPolicy.Request request) {
        return mSession.plan(workspace, request);
    }

    void execute(WindowLayoutPolicy.Workspace workspace, WindowLayoutPolicy.Plan plan,
            List<TaskRepository.TaskEntry> tasks, BooleanSupplier current,
            TaskRepository.ActionCallback callback) {
        plan.validate(workspace);
        if (!current.getAsBoolean()) {
            callback.onComplete(new TaskRepository.ActionResult(false, "layout operation cancelled"));
            return;
        }
        if (plan.placements().isEmpty()) {
            mSession.acknowledge(plan);
            callback.onComplete(new TaskRepository.ActionResult(true, "unchanged"));
            return;
        }
        List<TaskRepository.TaskEntry> selected = new ArrayList<>();
        List<Rect> bounds = new ArrayList<>();
        // Validate the whole batch before acquiring any geometry ownership.
        for (WindowLayoutPolicy.Placement placement : plan.placements()) {
            TaskRepository.TaskEntry task = tasks.stream()
                    .filter(candidate -> candidate.taskId == placement.taskId())
                    .findFirst().orElseThrow(() -> new IllegalArgumentException("task unavailable"));
            if (task.displayId != workspace.displayId() || !task.isBoundedFreeform()) {
                throw new IllegalArgumentException("layout task left the workspace");
            }
            selected.add(task);
            bounds.add(rect(placement.bounds()));
        }
        List<Pending> pending = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            TaskRepository.TaskEntry task = selected.get(i);
            WindowLayoutPolicy.Placement placement = plan.placements().get(i);
            DesktopTaskRuntimeState state = mStates.state(task.taskId);
            Pending change = new Pending(task.taskId, state, placement,
                    state.arrangedWindowBounds(), state.layoutOwnership().save(),
                    state.beginBoundsTransition(bounds.get(i),
                            placement.retention() != WindowLayoutPolicy.Retention.FLOATING));
            pending.add(change);
            if (placement.retention() == WindowLayoutPolicy.Retention.LAYOUT) {
                state.layoutOwnership().acquire(bounds(task.bounds), placement.bounds());
            } else if (placement.retention() == WindowLayoutPolicy.Retention.ARRANGEMENT) {
                state.setArrangedWindowBounds(bounds.get(i));
            }
        }
        final boolean[] completed = {false};
        TaskRepository.ActionCallback resultCallback = result -> mDispatcher.execute(() -> {
            if (completed[0]) return;
            completed[0] = true;
            boolean valid = current.getAsBoolean();
            boolean allCurrent = valid && pending.stream()
                    .allMatch(change -> mStates.isCurrent(change.taskId, change.state));
            for (Pending change : pending) {
                if (!valid || !mStates.isCurrent(change.taskId, change.state)
                        || !change.state.isBoundsTransition(change.transition)) continue;
                change.state.clearBoundsTransition(change.transition);
                if (!result.success) {
                    change.state.setArrangedWindowBounds(change.arrangement);
                    change.state.layoutOwnership().restore(change.ownership);
                } else if (change.placement.retention() != WindowLayoutPolicy.Retention.LAYOUT) {
                    change.state.layoutOwnership().release();
                    if (change.placement.retention() == WindowLayoutPolicy.Retention.FLOATING) {
                        change.state.clearWindowRestoreBounds();
                    }
                }
            }
            // Acknowledgement releases the command, not a promise of exact geometry.
            // Native minimum-size constraints are observed by the existing task observer.
            if (allCurrent && result.success) {
                mSession.acknowledge(plan);
            }
            callback.onComplete(allCurrent ? result : new TaskRepository.ActionResult(
                    false, "layout operation cancelled"));
        });
        try {
            mBackend.apply(workspace.displayId(), selected, bounds, resultCallback);
        } catch (RuntimeException error) {
            resultCallback.onComplete(new TaskRepository.ActionResult(false, error.toString()));
        }
    }

    Map<Integer, Rect> floatingBounds(List<TaskRepository.TaskEntry> tasks) {
        Map<Integer, Rect> result = null;
        for (TaskRepository.TaskEntry task : tasks) {
            DesktopTaskRuntimeState state = mStates.find(task.taskId);
            if (task.isFreeform() && state != null && state.layoutOwnership().isOwned()) {
                if (result == null) result = new LinkedHashMap<>();
                result.put(task.taskId, rect(state.layoutOwnership().floatingBounds()));
            }
        }
        return result == null ? java.util.Collections.emptyMap() : result;
    }

    static WindowLayoutPolicy.Bounds bounds(Rect rect) {
        return rect == null || rect.right <= rect.left || rect.bottom <= rect.top ? null
                : new WindowLayoutPolicy.Bounds(rect.left, rect.top, rect.right, rect.bottom);
    }

    static Rect rect(WindowLayoutPolicy.Bounds bounds) {
        if (bounds == null) return null;
        Rect rect = new Rect();
        rect.left = bounds.left(); rect.top = bounds.top();
        rect.right = bounds.right(); rect.bottom = bounds.bottom();
        return rect;
    }

    private record Pending(int taskId, DesktopTaskRuntimeState state,
            WindowLayoutPolicy.Placement placement, Rect arrangement,
            WindowLayoutOwnership.Saved ownership,
            DesktopTaskRuntimeState.BoundsTransition transition) { }
}

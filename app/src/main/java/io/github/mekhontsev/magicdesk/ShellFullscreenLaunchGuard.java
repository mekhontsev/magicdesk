package io.github.mekhontsev.magicdesk;

import android.content.Intent;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Protects Android's Task-only next-sibling lookup before ActivityStarter uses it. */
final class ShellFullscreenLaunchGuard implements ShellActivityStartController.Listener {
    static final class Boundary {
        final int areaId;
        final Object areaToken;
        final int separatorId;
        final Object separatorToken;

        Boundary(final int areaId, final Object areaToken,
                final int separatorId, final Object separatorToken) {
            this.areaId = areaId;
            this.areaToken = areaToken;
            this.separatorId = separatorId;
            this.separatorToken = separatorToken;
        }
    }

    private record State(Object service, int displayId, List<Boundary> boundaries) { }

    // The owner may be holding its monitor while ATMS calls activityStarting.
    // This callback must neither enter that monitor nor await a transition/draw.
    private volatile State mState = new State(null, -1, List.of());

    void add(final Object service, final int displayId, final Boundary boundary) {
        final List<Boundary> boundaries = new ArrayList<>(mState.boundaries());
        boundaries.add(boundary);
        mState = new State(service, displayId, List.copyOf(boundaries));
    }

    void remove(final int areaId) {
        final State state = mState;
        mState = new State(state.service(), state.displayId(),
                state.boundaries().stream().filter(b -> b.areaId != areaId).toList());
    }

    void clear() {
        mState = new State(null, -1, List.of());
    }

    @Override
    public boolean onActivityStarting(final Intent intent, final String packageName) {
        try {
            protect();
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            // Do not deliver into a known unsafe hierarchy or reconstruct an Intent
            // whose launch options and grants are absent from this callback.
            Log.w("MagicDeskTasks", "fullscreen launch boundary unavailable", error);
            return false;
        }
    }

    void protect() throws ReflectiveOperationException {
        final State state = mState;
        if (state.boundaries().isEmpty()) { return; }
        final List<FrameworkTaskSnapshot> roots =
                FrameworkTaskSnapshotSource.readRoots(state.service(), state.displayId());
        final List<Integer> order = repairOrder(roots, state.boundaries());
        if (order.isEmpty()) { return; }
        final Map<Integer, Object> tokens = new LinkedHashMap<>();
        for (final FrameworkTaskSnapshot root : roots) {
            tokens.put(root.taskId, HiddenTaskApi.getTaskToken(root.task));
        }
        for (final Boundary boundary : state.boundaries()) {
            tokens.put(-boundary.areaId, boundary.areaToken);
            tokens.put(boundary.separatorId, boundary.separatorToken);
        }
        final FrameworkWindowingApi windowing = FrameworkRuntime.current().windowing();
        final Object transaction = windowing.newTransaction();
        for (int index = order.size() - 1; index >= 0; index--) {
            windowing.reorder(transaction, tokens.get(order.get(index)), true, false);
        }
        if (state != mState) {
            throw new IllegalStateException("fullscreen boundary ownership changed during launch");
        }
        // One nested Binder submission, no callback/event wait and no focus operation.
        ShellWindowTransitionExecutor.applyAtomic(
                state.service(), windowing.transactionClass(), transaction);
    }

    /** Top-first root/negative-area IDs; empty means adjacency is already safe. */
    static List<Integer> repairOrder(final List<FrameworkTaskSnapshot> roots,
            final List<Boundary> boundaries) {
        final Map<Integer, Boundary> byArea = new LinkedHashMap<>();
        final Map<Integer, FrameworkTaskSnapshot> byTask = new LinkedHashMap<>();
        for (final Boundary boundary : boundaries) { byArea.put(boundary.areaId, boundary); }
        final List<Integer> current = new ArrayList<>();
        for (final FrameworkTaskSnapshot root : roots) {
            byTask.put(root.taskId, root);
            if (byArea.containsKey(root.displayAreaFeatureId)) {
                final Integer id = -root.displayAreaFeatureId;
                if (!current.contains(id)) { current.add(id); }
            } else if (root.displayAreaFeatureId
                    == TaskDisplayAreaHandle.Parent.DEFAULT_TASK_CONTAINER.featureId()) {
                current.add(root.taskId);
            }
        }
        final Map<Integer, Integer> separators = new LinkedHashMap<>();
        for (final Boundary boundary : boundaries) {
            // An area can disappear with its display. Never recreate or move its
            // migrated separator on another display from an in-flight callback.
            if (!current.contains(-boundary.areaId)) { continue; }
            final FrameworkTaskSnapshot separator = byTask.get(boundary.separatorId);
            if (separator == null || separator.displayAreaFeatureId
                    != TaskDisplayAreaHandle.Parent.DEFAULT_TASK_CONTAINER.featureId()
                    || separator.rootTaskId != separator.taskId) {
                throw new IllegalStateException("fullscreen separator missing for area="
                        + boundary.areaId);
            }
            separators.put(-boundary.areaId, boundary.separatorId);
        }
        final List<Integer> desired = new ArrayList<>();
        for (final Integer id : current) {
            if (separators.containsValue(id)) { continue; }
            desired.add(id);
            final Integer separator = separators.get(id);
            if (separator != null) { desired.add(separator); }
        }
        return current.equals(desired) ? Collections.emptyList() : desired;
    }
}

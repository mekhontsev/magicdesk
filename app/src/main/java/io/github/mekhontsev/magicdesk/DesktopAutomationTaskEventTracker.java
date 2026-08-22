package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Derives stable task events from snapshots supplied by the existing watcher. */
final class DesktopAutomationTaskEventTracker {
    private final Map<Integer, State> mPrevious = new LinkedHashMap<>();
    private boolean mInitialized;

    void reset() {
        mPrevious.clear();
        mInitialized = false;
    }

    void observe(final TaskRepository.Snapshot snapshot) {
        if (snapshot == null || !snapshot.available) {
            return;
        }
        try {
            observeAvailable(snapshot);
        } catch (JSONException ignored) {
            DesktopAutomationEventJournal.record(
                    "task", "snapshot_error", false,
                    "could not encode task snapshot");
        }
    }

    private void observeAvailable(final TaskRepository.Snapshot snapshot)
            throws JSONException {
        final Map<Integer, State> current = new LinkedHashMap<>();
        collect(current, snapshot.tasks);
        collect(current, snapshot.phoneTasks);
        if (!mInitialized) {
            mPrevious.putAll(current);
            mInitialized = true;
            record("snapshot", -1, new JSONObject(), "tasks=" + current.size());
            return;
        }
        for (final Map.Entry<Integer, State> entry : current.entrySet()) {
            final int taskId = entry.getKey().intValue();
            final State next = entry.getValue();
            final State previous = mPrevious.get(entry.getKey());
            if (previous == null) {
                record("added", taskId, next.toJson(), next.packageName);
                continue;
            }
            if (previous.displayId != next.displayId) {
                record("display_changed", taskId, next.toJson()
                        .put("previousDisplayId", previous.displayId),
                        previous.displayId + " -> " + next.displayId);
            }
            if (!previous.windowingMode.equals(next.windowingMode)) {
                record("window_mode_changed", taskId, next.toJson()
                        .put("previousWindowingMode", previous.windowingMode),
                        previous.windowingMode + " -> " + next.windowingMode);
            }
            if (!sameBounds(previous.bounds, next.bounds)) {
                record("bounds_changed", taskId, next.toJson()
                        .put("previousBounds", rectJson(previous.bounds)),
                        next.bounds.toShortString());
            }
            if (!previous.active && next.active) {
                record("focused", taskId, next.toJson(), next.packageName);
            }
            if (!previous.topActivity.equals(next.topActivity)) {
                record("top_activity_changed", taskId, next.toJson()
                        .put("previousTopActivity", previous.topActivity),
                        next.topActivity);
            }
            if (previous.visible != next.visible) {
                record("visibility_changed", taskId, next.toJson()
                        .put("previousVisible", previous.visible),
                        Boolean.toString(next.visible));
            }
        }
        for (final Map.Entry<Integer, State> entry : mPrevious.entrySet()) {
            if (!current.containsKey(entry.getKey())) {
                record("removed", entry.getKey().intValue(),
                        entry.getValue().toJson(), entry.getValue().packageName);
            }
        }
        mPrevious.clear();
        mPrevious.putAll(current);
    }

    private static void collect(
            final Map<Integer, State> destination,
            final List<TaskRepository.TaskEntry> tasks) {
        if (tasks == null) {
            return;
        }
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task != null) {
                destination.put(Integer.valueOf(task.taskId), new State(task));
            }
        }
    }

    private static void record(
            final String operation,
            final int taskId,
            final JSONObject data,
            final String detail) {
        try {
            if (taskId >= 0) {
                data.put("taskId", taskId);
            }
            DesktopAutomationEventJournal.record(
                    "task", operation, true, detail, data);
        } catch (JSONException ignored) {
            DesktopAutomationEventJournal.record(
                    "task", operation, true, detail);
        }
    }

    private static JSONObject rectJson(final Rect rect) throws JSONException {
        return new JSONObject()
                .put("left", rect.left)
                .put("top", rect.top)
                .put("right", rect.right)
                .put("bottom", rect.bottom);
    }

    private static boolean sameBounds(final Rect first, final Rect second) {
        return first.left == second.left
                && first.top == second.top
                && first.right == second.right
                && first.bottom == second.bottom;
    }

    private static final class State {
        final int displayId;
        final String windowingMode;
        final Rect bounds;
        final String packageName;
        final String topActivity;
        final boolean visible;
        final boolean active;

        State(final TaskRepository.TaskEntry task) {
            displayId = task.displayId;
            windowingMode = task.windowingMode;
            bounds = new Rect(task.bounds);
            packageName = task.packageName;
            topActivity = task.topActivityName == null
                    ? "" : task.topActivityName;
            visible = task.visible;
            active = task.active;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("displayId", displayId)
                    .put("windowingMode", windowingMode)
                    .put("mode", windowingMode)
                    .put("bounds", rectJson(bounds))
                    .put("package", packageName)
                    .put("topActivity", topActivity)
                    .put("visible", visible)
                    .put("active", active);
        }
    }
}

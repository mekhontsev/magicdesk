package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DesktopSpaceStateStore {
    static final int SPACE_COUNT = 4;

    private static final Map<Integer, State> STATES = new HashMap<>();

    private DesktopSpaceStateStore() {
    }

    static synchronized int activeSpace(final int displayId) {
        return state(displayId).activeSpace;
    }

    static synchronized void setActiveSpace(
            final int displayId, final int space) {
        validateSpace(space);
        state(displayId).activeSpace = space;
    }

    static synchronized void sync(
            final int displayId,
            final List<TaskRepository.TaskEntry> tasks,
            final String desktopPackage) {
        final State state = state(displayId);
        final Set<Integer> liveTaskIds = new HashSet<>();
        if (tasks != null) {
            for (final TaskRepository.TaskEntry task : tasks) {
                if (!isAppTask(task, desktopPackage)) {
                    continue;
                }
                final Integer taskId = Integer.valueOf(task.taskId);
                liveTaskIds.add(taskId);
                if (!state.initialized || task.visible || task.active) {
                    state.taskSpaces.put(
                            taskId, Integer.valueOf(state.activeSpace));
                }
            }
        }
        state.taskSpaces.keySet().retainAll(liveTaskIds);
        state.initialized = true;
    }

    static synchronized List<TaskRepository.TaskEntry> tasksInSpace(
            final int displayId,
            final int space,
            final List<TaskRepository.TaskEntry> tasks,
            final String desktopPackage) {
        validateSpace(space);
        final State state = state(displayId);
        final List<TaskRepository.TaskEntry> result = new ArrayList<>();
        if (tasks == null) {
            return result;
        }
        for (final TaskRepository.TaskEntry task : tasks) {
            if (!isAppTask(task, desktopPackage)) {
                continue;
            }
            final Integer assigned =
                    state.taskSpaces.get(Integer.valueOf(task.taskId));
            if (assigned != null && assigned.intValue() == space) {
                result.add(task);
            }
        }
        return result;
    }

    static synchronized boolean isInActiveSpace(
            final int displayId,
            final TaskRepository.TaskEntry task,
            final String desktopPackage) {
        if (!isAppTask(task, desktopPackage)) {
            return false;
        }
        final State state = state(displayId);
        final Integer assigned =
                state.taskSpaces.get(Integer.valueOf(task.taskId));
        return assigned == null
                || assigned.intValue() == state.activeSpace;
    }

    static synchronized void clear(final int displayId) {
        STATES.remove(Integer.valueOf(displayId));
    }

    static synchronized void clearAll() {
        STATES.clear();
    }

    private static State state(final int displayId) {
        State state = STATES.get(Integer.valueOf(displayId));
        if (state == null) {
            state = new State();
            STATES.put(Integer.valueOf(displayId), state);
        }
        return state;
    }

    private static boolean isAppTask(
            final TaskRepository.TaskEntry task,
            final String desktopPackage) {
        return task != null && task.taskId >= 0 && !task.home
                && task.packageName != null
                && !task.packageName.equals(desktopPackage);
    }

    private static void validateSpace(final int space) {
        if (space < 0 || space >= SPACE_COUNT) {
            throw new IllegalArgumentException("invalid desktop space " + space);
        }
    }

    private static final class State {
        int activeSpace;
        boolean initialized;
        final Map<Integer, Integer> taskSpaces = new HashMap<>();
    }
}

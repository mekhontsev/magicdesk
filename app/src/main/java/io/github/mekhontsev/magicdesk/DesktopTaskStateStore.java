package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class DesktopTaskStateStore {
    private static final Map<Integer, List<TaskRepository.TaskEntry>>
            VISIBLE_TASKS_BY_DISPLAY = new HashMap<>();
    private static final Map<Integer, List<TaskRepository.TaskEntry>>
            LAST_VISIBLE_TASKS_BY_DISPLAY = new HashMap<>();
    private static final Map<Integer, Boolean>
            HAS_VISIBLE_APP_TASK_BY_DISPLAY = new HashMap<>();
    private static final Set<Integer> FROZEN_LAST_VISIBLE_STACKS =
            new HashSet<>();

    private DesktopTaskStateStore() {
    }

    static synchronized List<TaskRepository.TaskEntry> getVisibleTasks(
            final int displayId) {
        final List<TaskRepository.TaskEntry> tasks =
                VISIBLE_TASKS_BY_DISPLAY.get(Integer.valueOf(displayId));
        return tasks == null ? null : new ArrayList<>(tasks);
    }

    static synchronized List<TaskRepository.TaskEntry> getLastVisibleTasks(
            final int displayId) {
        final List<TaskRepository.TaskEntry> tasks =
                LAST_VISIBLE_TASKS_BY_DISPLAY.get(Integer.valueOf(displayId));
        return tasks == null ? Collections.emptyList() : copyTasks(tasks);
    }

    static synchronized Boolean hasVisibleAppTask(final int displayId) {
        return HAS_VISIBLE_APP_TASK_BY_DISPLAY.get(Integer.valueOf(displayId));
    }

    static synchronized void beginFullscreenTransition(
            final int displayId,
            final List<TaskRepository.TaskEntry> visibleTasks,
            final int excludedTaskId) {
        if (displayId < 0) {
            return;
        }
        final List<TaskRepository.TaskEntry> workspace = new ArrayList<>();
        if (visibleTasks != null) {
            for (final TaskRepository.TaskEntry task : visibleTasks) {
                if (task != null && task.taskId != excludedTaskId) {
                    workspace.add(task);
                }
            }
        }
        LAST_VISIBLE_TASKS_BY_DISPLAY.put(
                Integer.valueOf(displayId),
                Collections.unmodifiableList(copyTasks(workspace)));
        FROZEN_LAST_VISIBLE_STACKS.add(Integer.valueOf(displayId));
    }

    static synchronized void finishFullscreenTransition(
            final int displayId,
            final boolean success) {
        if (displayId < 0) {
            return;
        }
        FROZEN_LAST_VISIBLE_STACKS.remove(Integer.valueOf(displayId));
        if (success) {
            return;
        }
        final List<TaskRepository.TaskEntry> visibleTasks =
                VISIBLE_TASKS_BY_DISPLAY.get(Integer.valueOf(displayId));
        if (visibleTasks != null) {
            LAST_VISIBLE_TASKS_BY_DISPLAY.put(
                    Integer.valueOf(displayId),
                    Collections.unmodifiableList(copyTasks(visibleTasks)));
        }
    }

    static synchronized void forgetVisibleTasks(final int displayId) {
        if (displayId >= 0) {
            VISIBLE_TASKS_BY_DISPLAY.put(
                    Integer.valueOf(displayId), Collections.emptyList());
        }
    }

    static synchronized void publish(
            final int displayId,
            final List<TaskRepository.TaskEntry> tasks,
            final boolean hasVisibleAppTask) {
        VISIBLE_TASKS_BY_DISPLAY.put(
                Integer.valueOf(displayId),
                Collections.unmodifiableList(new ArrayList<>(tasks)));
        HAS_VISIBLE_APP_TASK_BY_DISPLAY.put(
                Integer.valueOf(displayId),
                Boolean.valueOf(hasVisibleAppTask));
        if (!FROZEN_LAST_VISIBLE_STACKS.contains(Integer.valueOf(displayId))) {
            rememberVisibleTasks(displayId, tasks);
        }
    }

    static synchronized void clear(final int displayId) {
        if (displayId < 0) {
            return;
        }
        VISIBLE_TASKS_BY_DISPLAY.remove(Integer.valueOf(displayId));
        LAST_VISIBLE_TASKS_BY_DISPLAY.remove(Integer.valueOf(displayId));
        HAS_VISIBLE_APP_TASK_BY_DISPLAY.remove(Integer.valueOf(displayId));
        FROZEN_LAST_VISIBLE_STACKS.remove(Integer.valueOf(displayId));
    }

    private static void rememberVisibleTasks(
            final int displayId,
            final List<TaskRepository.TaskEntry> tasks) {
        if (displayId < 0 || tasks == null || tasks.isEmpty()) {
            return;
        }
        LAST_VISIBLE_TASKS_BY_DISPLAY.put(
                Integer.valueOf(displayId),
                Collections.unmodifiableList(copyTasks(tasks)));
    }

    private static List<TaskRepository.TaskEntry> copyTasks(
            final List<TaskRepository.TaskEntry> tasks) {
        final List<TaskRepository.TaskEntry> copies =
                new ArrayList<>(tasks.size());
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task == null) {
                continue;
            }
            copies.add(new TaskRepository.TaskEntry(
                    task.rootTaskId,
                    task.taskId,
                    task.displayId,
                    task.packageName,
                    task.componentName,
                    task.topActivityName,
                    task.windowingMode,
                    task.bounds,
                    task.home,
                    task.visible,
                    task.active));
        }
        return copies;
    }
}

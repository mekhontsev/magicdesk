package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds activate/demote z-orders without changing task window state.
 *
 * <p>Demotion rotates the active task behind its next MRU peer. It never
 * minimizes, hides, reparents, resizes, or changes the mode of that task.</p>
 */
final class TaskbarTaskOrder {
    private TaskbarTaskOrder() {
    }

    static List<Integer> demoteActiveTask(
            final TaskRepository.Snapshot snapshot,
            final int activeTaskId,
            final List<TaskRepository.TaskEntry> savedWorkspaceTopFirst) {
        final List<Integer> order = new ArrayList<>();
        if (snapshot == null || !snapshot.available || activeTaskId < 0) {
            return order;
        }
        final TaskRepository.TaskEntry activeTask = findTask(
                snapshot.tasks, activeTaskId);
        final TaskRepository.TaskEntry desktopHost = findDesktopHost(
                snapshot.tasks);
        if (activeTask == null || !activeTask.active
                || desktopHost == null
                || activeTask.displayId != desktopHost.displayId
                || !DesktopManagedTaskPolicy
                        .isControllableApplicationTask(activeTask)) {
            return order;
        }

        final List<TaskRepository.TaskEntry> nextTasksTopFirst =
                resolveNextTasks(
                        snapshot.tasks,
                        savedWorkspaceTopFirst,
                        activeTask);
        final Set<Integer> includedTaskIds = new HashSet<>();
        if (nextTasksTopFirst.isEmpty()) {
            // With no peer, bringing the desktop host forward is the same
            // z-order operation: the application remains live beneath it.
            addTask(order, includedTaskIds, activeTask.taskId);
            addTask(order, includedTaskIds, desktopHost.taskId);
            return order;
        }

        // focusDesktopTasks consumes a bottom-first order. Keep the desktop
        // below the demoted task, preserve every peer's relative order, and
        // activate the task that was immediately behind the old foreground.
        addTask(order, includedTaskIds, desktopHost.taskId);
        addTask(order, includedTaskIds, activeTask.taskId);
        for (int index = nextTasksTopFirst.size() - 1; index >= 0; index--) {
            addTask(
                    order,
                    includedTaskIds,
                    nextTasksTopFirst.get(index).taskId);
        }
        return order;
    }

    private static List<TaskRepository.TaskEntry> resolveNextTasks(
            final List<TaskRepository.TaskEntry> liveTasks,
            final List<TaskRepository.TaskEntry> savedWorkspaceTopFirst,
            final TaskRepository.TaskEntry activeTask) {
        final List<TaskRepository.TaskEntry> tasks = new ArrayList<>();
        final Set<Integer> includedTaskIds = new HashSet<>();
        if (liveTasks != null) {
            for (final TaskRepository.TaskEntry liveTask : liveTasks) {
                addPeerTask(tasks, includedTaskIds, liveTask, activeTask);
            }
        }
        // A saved workspace can contain live freeform roots that a vendor dump
        // temporarily omits from the useful z-order while fullscreen is active.
        if (savedWorkspaceTopFirst != null) {
            for (final TaskRepository.TaskEntry savedTask
                    : savedWorkspaceTopFirst) {
                final TaskRepository.TaskEntry liveTask = savedTask == null
                        ? null : findTask(liveTasks, savedTask.taskId);
                addPeerTask(tasks, includedTaskIds, liveTask, activeTask);
            }
        }
        return tasks;
    }

    private static void addPeerTask(
            final List<TaskRepository.TaskEntry> tasks,
            final Set<Integer> includedTaskIds,
            final TaskRepository.TaskEntry task,
            final TaskRepository.TaskEntry activeTask) {
        if (task == null || task.taskId == activeTask.taskId
                || task.displayId != activeTask.displayId
                || !DesktopManagedTaskPolicy
                        .isControllableApplicationTask(task)
                || !includedTaskIds.add(Integer.valueOf(task.taskId))) {
            return;
        }
        tasks.add(task);
    }

    private static TaskRepository.TaskEntry findDesktopHost(
            final List<TaskRepository.TaskEntry> tasks) {
        if (tasks != null) {
            for (final TaskRepository.TaskEntry task : tasks) {
                if (DesktopTaskController.isDesktopHostTask(task)) {
                    return task;
                }
            }
        }
        return null;
    }

    private static TaskRepository.TaskEntry findTask(
            final List<TaskRepository.TaskEntry> tasks,
            final int taskId) {
        if (tasks != null) {
            for (final TaskRepository.TaskEntry task : tasks) {
                if (task != null && task.taskId == taskId) {
                    return task;
                }
            }
        }
        return null;
    }

    private static void addTask(
            final List<Integer> order,
            final Set<Integer> includedTaskIds,
            final int taskId) {
        if (taskId >= 0 && includedTaskIds.add(Integer.valueOf(taskId))) {
            order.add(Integer.valueOf(taskId));
        }
    }
}

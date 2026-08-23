package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Builds the complete desktop z-order used when concealing a fullscreen task. */
final class TaskbarTaskOrder {
    private TaskbarTaskOrder() {
    }

    static List<Integer> concealFullscreenTask(
            final TaskRepository.Snapshot snapshot,
            final int fullscreenTaskId,
            final List<TaskRepository.TaskEntry> savedWorkspaceTopFirst) {
        final List<Integer> order = new ArrayList<>();
        if (snapshot == null || !snapshot.available || fullscreenTaskId < 0) {
            return order;
        }
        final TaskRepository.TaskEntry fullscreenTask = findTask(
                snapshot.tasks, fullscreenTaskId);
        final TaskRepository.TaskEntry desktopHost = findDesktopHost(
                snapshot.tasks);
        if (fullscreenTask == null || !fullscreenTask.isFullscreen()
                || desktopHost == null
                || fullscreenTask.displayId != desktopHost.displayId) {
            return order;
        }

        // focusDesktopTasks consumes a bottom-first order. Put the concealed
        // fullscreen task below the host, then restore every live freeform
        // window above the host without changing their previous z-order.
        final Set<Integer> includedTaskIds = new HashSet<>();
        addTask(order, includedTaskIds, fullscreenTask.taskId);
        addTask(order, includedTaskIds, desktopHost.taskId);
        final List<TaskRepository.TaskEntry> workspace = resolveWorkspace(
                snapshot.tasks, savedWorkspaceTopFirst, fullscreenTaskId);
        for (int index = workspace.size() - 1; index >= 0; index--) {
            addTask(order, includedTaskIds, workspace.get(index).taskId);
        }
        return order;
    }

    private static List<TaskRepository.TaskEntry> resolveWorkspace(
            final List<TaskRepository.TaskEntry> liveTasks,
            final List<TaskRepository.TaskEntry> savedWorkspaceTopFirst,
            final int excludedTaskId) {
        final List<TaskRepository.TaskEntry> workspace = new ArrayList<>();
        final Set<Integer> includedTaskIds = new HashSet<>();
        if (savedWorkspaceTopFirst != null) {
            for (final TaskRepository.TaskEntry savedTask
                    : savedWorkspaceTopFirst) {
                final TaskRepository.TaskEntry liveTask = savedTask == null
                        ? null : findTask(liveTasks, savedTask.taskId);
                addWorkspaceTask(
                        workspace, includedTaskIds, liveTask, excludedTaskId);
            }
        }
        if (!workspace.isEmpty()) {
            return workspace;
        }

        // A fullscreen task can also originate outside MagicDesk's explicit
        // transition path. In that case there is no saved workspace, so use
        // every currently visible freeform task above the desktop host.
        if (liveTasks != null) {
            for (final TaskRepository.TaskEntry liveTask : liveTasks) {
                if (DesktopTaskController.isDesktopHostTask(liveTask)) {
                    break;
                }
                if (liveTask != null && liveTask.visible) {
                    addWorkspaceTask(
                            workspace,
                            includedTaskIds,
                            liveTask,
                            excludedTaskId);
                }
            }
        }
        return workspace;
    }

    private static void addWorkspaceTask(
            final List<TaskRepository.TaskEntry> workspace,
            final Set<Integer> includedTaskIds,
            final TaskRepository.TaskEntry task,
            final int excludedTaskId) {
        if (task == null || task.taskId == excludedTaskId
                || !task.isFreeform()
                || !DesktopManagedTaskPolicy
                        .isControllableApplicationTask(task)
                || !includedTaskIds.add(Integer.valueOf(task.taskId))) {
            return;
        }
        workspace.add(task);
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

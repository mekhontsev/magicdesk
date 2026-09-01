package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Builds taskbar concealment z-orders without changing task window state. */
final class TaskbarTaskOrder {
    static final class DesktopPresentation {
        final List<Integer> physicalOrder;
        final List<Integer> restoreOrder;
        final Set<Integer> newlyConcealedTaskIds;

        DesktopPresentation(
                final List<Integer> physicalOrder,
                final List<Integer> restoreOrder,
                final Set<Integer> newlyConcealedTaskIds) {
            this.physicalOrder = physicalOrder;
            this.restoreOrder = restoreOrder;
            this.newlyConcealedTaskIds = newlyConcealedTaskIds;
        }
    }

    private TaskbarTaskOrder() {
    }

    static List<Integer> concealActiveTask(
            final TaskRepository.Snapshot snapshot,
            final int activeTaskId,
            final List<TaskRepository.TaskEntry> savedWorkspaceTopFirst,
            final Set<Integer> concealedTaskIds,
            final boolean includeFlattenedFullscreenPlanes) {
        return concealActiveTask(
                snapshot,
                activeTaskId,
                savedWorkspaceTopFirst,
                concealedTaskIds,
                includeFlattenedFullscreenPlanes,
                -1);
    }

    static List<Integer> concealActiveTask(
            final TaskRepository.Snapshot snapshot,
            final int activeTaskId,
            final List<TaskRepository.TaskEntry> savedWorkspaceTopFirst,
            final Set<Integer> concealedTaskIds,
            final boolean includeFlattenedFullscreenPlanes,
            final int focusedTaskId) {
        final List<Integer> order = new ArrayList<>();
        if (snapshot == null || !snapshot.available || activeTaskId < 0) {
            return order;
        }
        final TaskRepository.TaskEntry activeTask = findTask(
                snapshot.tasks, activeTaskId);
        final TaskRepository.TaskEntry desktopHost = findDesktopHost(
                snapshot.tasks);
        if (activeTask == null
                || (!activeTask.active && activeTask.taskId != focusedTaskId)
                || desktopHost == null
                || activeTask.displayId != desktopHost.displayId
                || !DesktopManagedTaskPolicy
                        .isControllableApplicationTask(activeTask)) {
            return order;
        }

        final Set<Integer> concealed = new HashSet<>();
        if (concealedTaskIds != null) {
            concealed.addAll(concealedTaskIds);
        }
        concealed.add(Integer.valueOf(activeTaskId));
        final List<TaskRepository.TaskEntry> visibleTasksTopFirst =
                resolveVisibleTasks(
                        snapshot.tasks,
                        savedWorkspaceTopFirst,
                        activeTask.displayId,
                        activeTask.taskId,
                        concealed,
                        includeFlattenedFullscreenPlanes);
        final Set<Integer> includedTaskIds = new HashSet<>();
        for (final TaskRepository.TaskEntry task : snapshot.tasks) {
            if (task != null && task.displayId == activeTask.displayId
                    && concealed.contains(Integer.valueOf(task.taskId))) {
                addTask(order, includedTaskIds, task.taskId);
            }
        }
        addTask(order, includedTaskIds, desktopHost.taskId);
        for (int index = visibleTasksTopFirst.size() - 1;
                index >= 0;
                index--) {
            addTask(
                    order,
                    includedTaskIds,
                    visibleTasksTopFirst.get(index).taskId);
        }
        return order;
    }

    static DesktopPresentation presentDesktop(
            final TaskRepository.Snapshot snapshot,
            final List<TaskRepository.TaskEntry> savedWorkspaceTopFirst,
            final Set<Integer> concealedTaskIds,
            final boolean includeFlattenedFullscreenPlanes) {
        if (snapshot == null || !snapshot.available) {
            return null;
        }
        final TaskRepository.TaskEntry desktopHost = findDesktopHost(
                snapshot.tasks);
        if (desktopHost == null) {
            return null;
        }
        final Set<Integer> concealed = new HashSet<>();
        if (concealedTaskIds != null) {
            concealed.addAll(concealedTaskIds);
        }
        final List<TaskRepository.TaskEntry> workspaceTopFirst =
                resolveVisibleTasks(
                        snapshot.tasks,
                        savedWorkspaceTopFirst,
                        desktopHost.displayId,
                        -1,
                        concealed,
                        includeFlattenedFullscreenPlanes);
        final List<Integer> restoreOrder = new ArrayList<>();
        final Set<Integer> newlyConcealedTaskIds = new LinkedHashSet<>();
        for (int index = workspaceTopFirst.size() - 1;
                index >= 0;
                index--) {
            final Integer taskId = Integer.valueOf(
                    workspaceTopFirst.get(index).taskId);
            restoreOrder.add(taskId);
            newlyConcealedTaskIds.add(taskId);
        }
        final List<Integer> physicalOrder = new ArrayList<>(restoreOrder);
        physicalOrder.add(Integer.valueOf(desktopHost.taskId));
        return new DesktopPresentation(
                Collections.unmodifiableList(physicalOrder),
                Collections.unmodifiableList(restoreOrder),
                Collections.unmodifiableSet(newlyConcealedTaskIds));
    }

    private static List<TaskRepository.TaskEntry> resolveVisibleTasks(
            final List<TaskRepository.TaskEntry> liveTasks,
            final List<TaskRepository.TaskEntry> savedWorkspaceTopFirst,
            final int displayId,
            final int excludedTaskId,
            final Set<Integer> concealedTaskIds,
            final boolean includeFlattenedFullscreenPlanes) {
        final List<TaskRepository.TaskEntry> tasks = new ArrayList<>();
        final Set<Integer> includedTaskIds = new HashSet<>();
        boolean desktopHostSeen = false;
        if (liveTasks != null) {
            for (final TaskRepository.TaskEntry liveTask : liveTasks) {
                if (DesktopTaskController.isDesktopHostTask(liveTask)) {
                    desktopHostSeen = true;
                    continue;
                }
                // Independent fullscreen planes are siblings of the ordinary
                // workspace and may be flattened after its desktop host in a
                // task snapshot. They remain valid demotion targets; ordinary
                // tasks after the host are already concealed by that host.
                if (desktopHostSeen
                        && (!includeFlattenedFullscreenPlanes
                                || liveTask == null
                                || !liveTask.isFullscreen())) {
                    continue;
                }
                addVisibleTask(
                        tasks,
                        includedTaskIds,
                        liveTask,
                        displayId,
                        excludedTaskId,
                        concealedTaskIds);
            }
        }
        // A saved workspace can contain live freeform roots that a vendor dump
        // temporarily omits from the useful z-order while fullscreen is active.
        if (savedWorkspaceTopFirst != null) {
            for (final TaskRepository.TaskEntry savedTask
                    : savedWorkspaceTopFirst) {
                final TaskRepository.TaskEntry liveTask = savedTask == null
                        ? null : findTask(liveTasks, savedTask.taskId);
                addVisibleTask(
                        tasks,
                        includedTaskIds,
                        liveTask,
                        displayId,
                        excludedTaskId,
                        concealedTaskIds);
            }
        }
        return tasks;
    }

    private static void addVisibleTask(
            final List<TaskRepository.TaskEntry> tasks,
            final Set<Integer> includedTaskIds,
            final TaskRepository.TaskEntry task,
            final int displayId,
            final int excludedTaskId,
            final Set<Integer> concealedTaskIds) {
        if (task == null || task.taskId == excludedTaskId
                || task.displayId != displayId
                || concealedTaskIds.contains(Integer.valueOf(task.taskId))
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

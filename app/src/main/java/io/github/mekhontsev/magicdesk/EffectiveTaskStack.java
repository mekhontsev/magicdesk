package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Resolves user-visible foreground state from a top-first desktop snapshot. */
final class EffectiveTaskStack {
    private EffectiveTaskStack() {
    }

    static boolean shouldActivateTaskbarTarget(
            final TaskRepository.Snapshot snapshot,
            final TaskRepository.TaskEntry target,
            final Set<Integer> demotedTaskIds) {
        return shouldActivateTaskbarTarget(
                snapshot, target, demotedTaskIds, -1);
    }

    static boolean shouldActivateTaskbarTarget(
            final TaskRepository.Snapshot snapshot,
            final TaskRepository.TaskEntry target,
            final Set<Integer> demotedTaskIds,
            final int focusedTaskId) {
        if (target == null || !target.visible
                || isDemoted(target.taskId, demotedTaskIds)) {
            return true;
        }
        // Organizer-owned fullscreen planes can retain a synthetic active
        // flag while a freeform task already owns framework focus. Pending
        // focus is also newer than the asynchronously loaded task snapshot.
        if (focusedTaskId >= 0) {
            return target.taskId != focusedTaskId;
        }
        return !isEffectiveForeground(
                snapshot == null ? null : snapshot.tasks,
                target,
                demotedTaskIds);
    }

    static boolean isEffectiveForeground(
            final List<TaskRepository.TaskEntry> topFirstTasks,
            final TaskRepository.TaskEntry target,
            final Set<Integer> demotedTaskIds) {
        if (target == null || topFirstTasks == null
                || !target.visible || !target.active
                || (!target.isFreeform() && !target.isFullscreen())
                || isDemoted(target.taskId, demotedTaskIds)
                || !DesktopManagedTaskPolicy
                        .isControllableApplicationTask(target)) {
            return false;
        }
        return foregroundBlockersTopFirst(
                topFirstTasks, target, demotedTaskIds).isEmpty();
    }

    static List<TaskRepository.TaskEntry> foregroundBlockersTopFirst(
            final List<TaskRepository.TaskEntry> topFirstTasks,
            final TaskRepository.TaskEntry target,
            final Set<Integer> demotedTaskIds) {
        if (target == null || topFirstTasks == null) {
            return Collections.emptyList();
        }
        final List<TaskRepository.TaskEntry> blockers = new ArrayList<>();
        for (final TaskRepository.TaskEntry task : topFirstTasks) {
            if (task == null || task.displayId != target.displayId) {
                continue;
            }
            if (task.taskId == target.taskId) {
                return blockers;
            }
            if (task.visible
                    && !isDemoted(task.taskId, demotedTaskIds)
                    && DesktopManagedTaskPolicy
                            .isControllableApplicationTask(task)) {
                blockers.add(task);
            }
        }
        return Collections.emptyList();
    }

    private static boolean isDemoted(
            final int taskId,
            final Set<Integer> demotedTaskIds) {
        return demotedTaskIds != null
                && demotedTaskIds.contains(Integer.valueOf(taskId));
    }
}

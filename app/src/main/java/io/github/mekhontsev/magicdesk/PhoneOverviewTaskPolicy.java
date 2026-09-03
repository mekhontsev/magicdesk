package io.github.mekhontsev.magicdesk;

import android.view.Display;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Selects user tasks that belong on the launcher-owned phone Overview. */
final class PhoneOverviewTaskPolicy {
    private PhoneOverviewTaskPolicy() {
    }

    static List<TaskRepository.TaskEntry> select(
            final List<TaskRepository.TaskEntry> tasks,
            final String magicDeskPackage,
            final String previousHomePackage) {
        return select(
                tasks,
                magicDeskPackage,
                previousHomePackage,
                Collections.emptyList());
    }

    static List<TaskRepository.TaskEntry> select(
            final List<TaskRepository.TaskEntry> tasks,
            final String magicDeskPackage,
            final String previousHomePackage,
            final List<TaskRepository.TaskEntry> desktopTasks) {
        final List<TaskRepository.TaskEntry> selected = new ArrayList<>();
        final Set<Integer> roots = new HashSet<>();
        final Set<Integer> desktopTaskIds = taskIds(desktopTasks);
        if (tasks == null) {
            return selected;
        }
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task == null
                    || task.displayId != Display.DEFAULT_DISPLAY
                    || task.taskId < 0
                    || task.rootTaskId < 0
                    || task.home
                    || task.isFreeform()
                    || desktopTaskIds.contains(Integer.valueOf(task.taskId))
                    || desktopTaskIds.contains(
                            Integer.valueOf(task.rootTaskId))
                    || !PackageNameValidator.isSafe(task.packageName)
                    || task.packageName.equals(magicDeskPackage)
                    || task.packageName.equals(previousHomePackage)
                    || !roots.add(Integer.valueOf(task.rootTaskId))) {
                continue;
            }
            selected.add(task);
        }
        return selected;
    }

    private static Set<Integer> taskIds(
            final List<TaskRepository.TaskEntry> tasks) {
        final Set<Integer> taskIds = new HashSet<>();
        if (tasks == null) {
            return taskIds;
        }
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task == null) {
                continue;
            }
            taskIds.add(Integer.valueOf(task.taskId));
            taskIds.add(Integer.valueOf(task.rootTaskId));
        }
        return taskIds;
    }
}

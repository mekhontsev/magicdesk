package io.github.mekhontsev.magicdesk;

import android.view.Display;

import java.util.ArrayList;
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
        final List<TaskRepository.TaskEntry> selected = new ArrayList<>();
        final Set<Integer> roots = new HashSet<>();
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
}

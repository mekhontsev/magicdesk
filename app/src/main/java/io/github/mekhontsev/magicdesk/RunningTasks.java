package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Live windows in snapshot order, independent of launcher entries and launch history. */
final class RunningTasks {
    private RunningTasks() { }

    /** A negative display selects all displays; HOME supplies its local display and previous launcher. */
    static List<TaskRepository.TaskEntry> select(List<TaskRepository.TaskEntry> tasks,
            AppProfile profile, int displayId, String previousHomePackage) {
        final var result = new ArrayList<TaskRepository.TaskEntry>();
        final var seen = new HashSet<Integer>();
        for (final var task : tasks) {
            if (task == null || task.taskId < 0 || task.rootTaskId < 0 || task.displayId < 0
                    || !profile.owns(task.userId)
                    || (displayId >= 0 && task.displayId != displayId)
                    || task.activityType != FrameworkTaskSnapshot.ACTIVITY_TYPE_STANDARD
                    || !DesktopManagedTaskPolicy.isManagedApplicationTask(task)
                    || task.packageName.equals(previousHomePackage)
                    || !seen.add(task.taskId)) continue;
            result.add(task);
        }
        return List.copyOf(result);
    }
}

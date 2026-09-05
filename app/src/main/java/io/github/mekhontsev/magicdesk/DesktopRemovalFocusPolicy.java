package io.github.mekhontsev.magicdesk;

import java.util.List;

/** Selects the exposed surface, not a stale focused task behind it. */
final class DesktopRemovalFocusPolicy {
    private DesktopRemovalFocusPolicy() {
    }

    static FrameworkTaskSnapshot exposedTask(
            final List<FrameworkTaskSnapshot> tasks,
            final int displayId,
            final int removedTaskId) {
        // A removal callback can precede the observer snapshot. Do not select
        // a successor until that snapshot has stopped exposing the old task.
        for (final FrameworkTaskSnapshot task : tasks) {
            if (task.displayId == displayId && task.taskId == removedTaskId) {
                return null;
            }
        }
        for (final FrameworkTaskSnapshot task : tasks) {
            if (task.displayId == displayId && task.visible
                    && !DesktopInfrastructureTasks.isTask(task)) {
                // HOME and unrelated phone apps are opaque boundaries too.
                // The caller checks ownership; never search behind this task
                // for a desktop app which still happens to report focused.
                return task;
            }
        }
        return null;
    }
}

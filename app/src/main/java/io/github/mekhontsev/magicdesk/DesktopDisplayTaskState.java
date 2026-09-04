package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Transient visible-task state owned by one desktop display session. */
final class DesktopDisplayTaskState {
    private List<TaskRepository.TaskEntry> mVisibleTasks;
    private List<TaskRepository.TaskEntry> mLastVisibleTasks =
            Collections.emptyList();
    private Boolean mHasVisibleAppTask;
    private boolean mLastVisibleStackFrozen;

    synchronized List<TaskRepository.TaskEntry> visibleTasks() {
        return mVisibleTasks == null ? null : new ArrayList<>(mVisibleTasks);
    }

    synchronized List<TaskRepository.TaskEntry> lastVisibleTasks() {
        return copyTasks(mLastVisibleTasks);
    }

    synchronized Boolean hasVisibleAppTask() {
        return mHasVisibleAppTask;
    }

    synchronized void beginFullscreenTransition(
            final List<TaskRepository.TaskEntry> visibleTasks,
            final int excludedTaskId) {
        final List<TaskRepository.TaskEntry> workspace = new ArrayList<>();
        if (visibleTasks != null) {
            for (final TaskRepository.TaskEntry task : visibleTasks) {
                if (task != null && task.taskId != excludedTaskId) {
                    workspace.add(task);
                }
            }
        }
        mLastVisibleTasks = immutableTaskCopy(workspace);
        mLastVisibleStackFrozen = true;
    }

    synchronized void finishFullscreenTransition(final boolean success) {
        mLastVisibleStackFrozen = false;
        if (!success && mVisibleTasks != null) {
            mLastVisibleTasks = immutableTaskCopy(mVisibleTasks);
        }
    }

    synchronized void forgetVisibleTasks() {
        mVisibleTasks = Collections.emptyList();
    }

    synchronized void publish(
            final List<TaskRepository.TaskEntry> tasks,
            final boolean hasVisibleAppTask) {
        mVisibleTasks = Collections.unmodifiableList(new ArrayList<>(tasks));
        mHasVisibleAppTask = Boolean.valueOf(hasVisibleAppTask);
        if (!mLastVisibleStackFrozen && tasks != null && !tasks.isEmpty()) {
            mLastVisibleTasks = immutableTaskCopy(tasks);
        }
    }

    synchronized void clear() {
        mVisibleTasks = null;
        mLastVisibleTasks = Collections.emptyList();
        mHasVisibleAppTask = null;
        mLastVisibleStackFrozen = false;
    }

    private static List<TaskRepository.TaskEntry> immutableTaskCopy(
            final List<TaskRepository.TaskEntry> tasks) {
        return Collections.unmodifiableList(copyTasks(tasks));
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
                    task.activityType,
                    task.home,
                    task.visible,
                    task.active));
        }
        return copies;
    }
}

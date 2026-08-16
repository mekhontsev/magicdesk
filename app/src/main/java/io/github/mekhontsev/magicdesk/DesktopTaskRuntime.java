package io.github.mekhontsev.magicdesk;

import java.util.List;

/** Task operations owned by the active process runtime. */
interface DesktopTaskRuntime {
    List<TaskRepository.TaskEntry> getVisibleFreeformTasks(int displayId);

    List<TaskRepository.TaskEntry> getLastVisibleFreeformTasks(int displayId);

    Boolean hasVisibleAppTaskSnapshot(int displayId);

    void beginFullscreenTransition(
            int displayId,
            List<TaskRepository.TaskEntry> visibleTasks,
            int excludedTaskId);

    void finishFullscreenTransition(int displayId, boolean success);

    void forgetVisibleFreeformTasks(int displayId);

    void focusStack(
            List<TaskRepository.TaskEntry> topFirstTasks,
            TaskRepository.TaskEntry topTask,
            TaskRepository.ActionCallback callback);

    void focusDesktopTasks(
            int displayId,
            List<Integer> taskIds,
            TaskRepository.ActionCallback callback);

    boolean handleActiveTaskShortcut(int shortcut);

    void noteManualFreeformTransition(int taskId);

    void beginExplicitWindowedLaunch(int taskId);

    void finishExplicitWindowedLaunch(int taskId);

    void expectTouchpadDisplacement();

    void finishTouchpadPreservation();

    void disableExternalTaskMigrationProtection();

    void restoreExternalTaskMigrationProtection();

    boolean dismissTransientActivity();

    boolean sendSystemBack();
}

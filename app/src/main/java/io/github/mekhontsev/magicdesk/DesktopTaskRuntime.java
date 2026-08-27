package io.github.mekhontsev.magicdesk;

import android.content.Intent;
import android.graphics.Rect;

import java.io.IOException;
import java.util.List;

/** Task operations owned by the active process runtime. */
interface DesktopTaskRuntime {
    boolean isTaskObserverReady();

    int launchWindowedTask(
            int displayId, Intent intent, Rect bounds) throws IOException;

    int launchFullscreenTaskInDesktopArea(
            int displayId, Intent intent) throws IOException;

    int launchFullscreenTask(
            int displayId, Intent intent) throws IOException;

    void noteTaskLaunchFocus(int displayId, int taskId);

    void launchTaskAction(
            int displayId, int taskId, Intent intent) throws IOException;

    void placeTaskInDesktopArea(
            int taskId,
            int sourceDisplayId,
            int targetDisplayId,
            Rect bounds) throws IOException;

    void placeFullscreenTaskInDesktopArea(
            int taskId,
            int sourceDisplayId,
            int targetDisplayId) throws IOException;

    boolean closeTask(
            TaskRepository.TaskEntry task,
            TaskRepository.ActionCallback callback);

    boolean forceStopPackage(
            String packageName,
            TaskRepository.ActionCallback callback);

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

    void showDesktop(
            int displayId,
            int desktopHostTaskId,
            TaskRepository.ActionCallback callback);

    void restoreShowDesktopWorkspace(
            int displayId,
            int desktopHostTaskId,
            TaskRepository.ActionCallback callback);

    void toggleTaskbarTask(
            int displayId,
            int taskId,
            TaskRepository.ActionCallback callback);

    boolean handleActiveTaskShortcut(int shortcut);

    boolean arrangeTask(int taskId, int shortcut);

    void noteManualFreeformTransition(int taskId);

    void beginExplicitWindowedLaunch(int taskId);

    void finishExplicitWindowedLaunch(int taskId);

    boolean protectExplicitFullscreenTask(int displayId, int taskId);

    void expectTouchpadDisplacement();

    void finishTouchpadPreservation();

    void setPhoneTouchpadRequested(boolean requested);

    void disableExternalTaskMigrationProtection();

    void restoreExternalTaskMigrationProtection();

    boolean dismissTransientActivity();

    boolean sendSystemBack();

    boolean startSelfTestTaskStackGuard(
            int displayId, int hostTaskId, String stage);

    void setSelfTestTaskStackGuardStage(String stage);

    SelfTestTaskStackReport stopSelfTestTaskStackGuard();

    TaskWindowSnapshot inspectTaskWindow(int displayId, int taskId);
}

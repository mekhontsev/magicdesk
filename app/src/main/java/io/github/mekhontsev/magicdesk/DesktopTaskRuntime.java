package io.github.mekhontsev.magicdesk;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.UserHandle;

import java.io.IOException;
import java.util.List;

/** Task operations owned by the active process runtime. */
interface DesktopTaskRuntime {
    boolean isTaskObserverReady();

    TaskRepository.Snapshot selectDesktopTaskSnapshot(
            int displayId, TaskRepository.Snapshot snapshot);

    int launchWindowedTask(
            int displayId, Intent intent, Rect bounds) throws IOException;

    int launchFullscreenTask(
            int displayId, Intent intent) throws IOException;

    boolean attachWindowedTask(
            int displayId, int taskId, Rect bounds) throws IOException;

    boolean attachFullscreenTask(
            int displayId, int taskId) throws IOException;

    int launchAppShortcut(
            int displayId,
            String packageName,
            String shortcutId,
            UserHandle user,
            int windowingMode,
            Rect bounds,
            int existingTaskId) throws IOException;

    int launchPendingActivity(
            int displayId,
            AppLaunchTarget target,
            PendingIntent pendingIntent,
            int windowingMode,
            Rect bounds,
            int existingTaskId) throws IOException;

    void noteTaskLaunchFocus(int displayId, int taskId);

    void launchTaskAction(
            int displayId, int taskId, Intent intent) throws IOException;

    boolean closeTask(
            TaskRepository.TaskEntry task,
            TaskRepository.ActionCallback callback);

    boolean makeTaskFullscreen(
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

    void presentDesktopWorkspace(
            int displayId,
            int desktopHostTaskId,
            TaskRepository.ActionCallback callback);

    void restoreShowDesktopWorkspace(
            int displayId,
            int desktopHostTaskId,
            TaskRepository.ActionCallback callback);

    void toggleShowDesktopWorkspace(
            int displayId,
            int desktopHostTaskId,
            TaskRepository.ActionCallback callback);

    void restoreDesktopWorkspace(
            int displayId,
            List<Integer> backToFrontTaskIds,
            TaskRepository.ActionCallback callback);

    void toggleTaskbarTask(
            int displayId,
            int taskId,
            TaskRepository.ActionCallback callback);

    boolean handleActiveTaskShortcut(int shortcut);

    boolean arrangeTask(int taskId, int shortcut);

    void setWindowBounds(
            int displayId,
            int taskId,
            Rect bounds,
            TaskRepository.ActionCallback callback);

    void noteManualFreeformTransition(int taskId);

    void beginExplicitWindowedLaunch(int taskId);

    boolean protectExplicitFullscreenTask(int displayId, int taskId);

    void expectTouchpadDisplacement();

    void finishTouchpadPreservation();

    void setPhoneTouchpadRequested(boolean requested);

    void configureDesktopActivityInput(int displayId, IBinder activityToken);

    void prepareDesktopChromeHost(
            int displayId, TaskRepository.ActionCallback callback);

    void raiseDesktopChrome(int displayId);

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

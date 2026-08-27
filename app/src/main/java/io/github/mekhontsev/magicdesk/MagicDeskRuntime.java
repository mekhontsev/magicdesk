package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.graphics.Rect;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Stable process-local entry point for the optional runtime service. */
public final class MagicDeskRuntime {
    private static final String ACTION_START_AUTOMATION =
            BuildConfig.APPLICATION_ID + ".action.START_AUTOMATION";
    private static WeakReference<MagicDeskRuntimeBackend> sBackend =
            new WeakReference<>(null);

    private MagicDeskRuntime() {
    }

    public static void start(final Context context) {
        context.startForegroundService(
                new Intent(context, MagicDeskRuntimeService.class));
    }

    static void startAutomation(final Context context) {
        if (context == null
                || !MagicDeskMcpPreferences.isEnabled(context)) {
            return;
        }
        context.startForegroundService(
                new Intent(context, MagicDeskRuntimeService.class)
                        .setAction(ACTION_START_AUTOMATION));
    }

    static boolean isAutomationStart(final Intent intent) {
        return intent != null
                && ACTION_START_AUTOMATION.equals(intent.getAction());
    }

    static void retainAutomationOrStop(final Context context) {
        if (context == null) {
            return;
        }
        if (!MagicDeskMcpPreferences.isEnabled(context)) {
            stop(context);
            return;
        }
        final MagicDeskRuntimeBackend backend = backend();
        if (backend == null || !backend.isDesktopRuntimeInitialized()) {
            startAutomation(context);
            return;
        }
        stop(context, () -> startAutomation(context));
    }

    public static void stop(final Context context) {
        stop(context, null);
    }

    static void stop(
            final Context context,
            final Runnable completion) {
        final AtomicBoolean finished = new AtomicBoolean();
        final Runnable finish = () -> {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            context.stopService(
                    new Intent(context, MagicDeskRuntimeService.class));
            if (completion != null) {
                completion.run();
            }
        };
        final MagicDeskRuntimeBackend backend = backend();
        if (backend != null) {
            try {
                backend.prepareForStop(finish);
            } catch (RuntimeException error) {
                finish.run();
                throw error;
            }
        } else {
            finish.run();
        }
    }

    static void releaseDesktopTaskSession(final Runnable completion) {
        final AtomicBoolean finished = new AtomicBoolean();
        final Runnable finish = () -> {
            if (finished.compareAndSet(false, true) && completion != null) {
                completion.run();
            }
        };
        final MagicDeskRuntimeBackend backend = backend();
        if (backend == null) {
            finish.run();
            return;
        }
        try {
            backend.releaseDesktopTaskSession(finish);
        } catch (RuntimeException error) {
            finish.run();
            throw error;
        }
    }

    public static void refreshNotification() {
        final MagicDeskRuntimeBackend backend = backend();
        if (backend != null) {
            backend.refreshNotification();
        }
    }

    static void setOperationStatus(final String status) {
        final MagicDeskRuntimeBackend backend = backend();
        if (backend != null) {
            backend.setOperationStatus(status);
        }
    }

    static void refreshDesktopTasks() {
        final MagicDeskRuntimeBackend backend = backend();
        if (backend != null) {
            backend.refreshDesktopTasks();
        }
    }

    public static void refreshPlatformState() {
        final MagicDeskRuntimeBackend backend = backend();
        if (backend != null) {
            backend.refreshPlatformState();
        }
    }

    static void refreshSettings() {
        refreshSettings(null);
    }

    static void refreshSettings(final Runnable completion) {
        final MagicDeskRuntimeBackend backend = backend();
        if (backend != null) {
            backend.refreshSettings(completion);
        } else if (completion != null) {
            completion.run();
        }
    }

    static boolean isSessionWakeLockHeld() {
        final MagicDeskRuntimeBackend backend = backend();
        return backend != null && backend.isSessionWakeLockHeld();
    }

    static void reconcileFailedDesktopLaunch(final int displayId) {
        final MagicDeskRuntimeBackend backend = backend();
        if (backend != null) {
            backend.reconcileFailedDesktopLaunch(displayId);
        }
    }

    static void scheduleLocalDesktopCleanup() {
        final MagicDeskRuntimeBackend backend = backend();
        if (backend != null) {
            backend.scheduleLocalDesktopCleanup();
        }
    }

    static boolean isDesktopMouseBridgeReady() {
        final MagicDeskRuntimeBackend backend = backend();
        return backend != null && backend.isDesktopMouseBridgeReady();
    }

    static boolean capturePointerPosition() {
        final MagicDeskRuntimeBackend backend = backend();
        return backend != null && backend.capturePointerPosition();
    }

    static void restorePointerPositionOnNextMotion() {
        final MagicDeskRuntimeBackend backend = backend();
        if (backend != null) {
            backend.restorePointerPositionOnNextMotion();
        }
    }

    static void reactivatePointerOnNextMotion() {
        final MagicDeskRuntimeBackend backend = backend();
        if (backend != null) {
            backend.reactivatePointerOnNextMotion();
        }
    }

    static void preparePhysicalPointerHandoff(final int displayId) {
        final MagicDeskRuntimeBackend backend = backend();
        if (backend != null) {
            backend.preparePhysicalPointerHandoff(displayId);
        }
    }

    static boolean prepareDesktopDisplayRemoval(
            final int displayId) {
        final MagicDeskRuntimeBackend backend = backend();
        return backend != null
                && backend.prepareDesktopDisplayRemoval(displayId);
    }

    static void cancelDesktopDisplayRemoval(final int displayId) {
        final MagicDeskRuntimeBackend backend = backend();
        if (backend != null) {
            backend.cancelDesktopDisplayRemoval(displayId);
        }
    }

    static Point getDesktopPointerPosition(final int displayId) {
        final MagicDeskRuntimeBackend backend = backend();
        return backend == null
                ? null : backend.getDesktopPointerPosition(displayId);
    }

    static boolean updateDesktopPointerPosition(
            final int displayId,
            final int x,
            final int y,
            final int action,
            final long downTime) {
        final MagicDeskRuntimeBackend backend = backend();
        return backend != null && backend.updateDesktopPointerPosition(
                displayId, x, y, action, downTime);
    }

    static boolean activateDesktopPointer(final int displayId) {
        final MagicDeskRuntimeBackend backend = backend();
        return backend != null && backend.activateDesktopPointer(displayId);
    }

    static boolean clickDesktopPointer(
            final int displayId, final int button) {
        final MagicDeskRuntimeBackend backend = backend();
        return backend != null
                && backend.clickDesktopPointer(displayId, button);
    }

    static boolean scrollDesktopPointer(
            final int displayId, final float amount) {
        final MagicDeskRuntimeBackend backend = backend();
        return backend != null
                && backend.scrollDesktopPointer(displayId, amount);
    }

    static boolean updateDesktopTextInput(
            final int displayId,
            final int action,
            final String text,
            final int arg1,
            final int arg2,
            final int arg3) {
        final MagicDeskRuntimeBackend backend = backend();
        return backend != null && backend.updateDesktopTextInput(
                displayId, action, text, arg1, arg2, arg3);
    }

    static boolean beginDesktopTextInput(final int displayId) {
        final MagicDeskRuntimeBackend backend = backend();
        return backend != null && backend.beginDesktopTextInput(displayId);
    }

    static void endDesktopTextInput(final int displayId) {
        final MagicDeskRuntimeBackend backend = backend();
        if (backend != null) {
            backend.endDesktopTextInput(displayId);
        }
    }

    static boolean showStart() {
        final MagicDeskRuntimeBackend backend = backend();
        return backend != null && backend.showStart();
    }

    static boolean toggleDesktopWorkspace() {
        final MagicDeskRuntimeBackend backend = backend();
        return backend != null && backend.toggleDesktopWorkspace();
    }

    static boolean restoreLastVisibleWindows() {
        final MagicDeskRuntimeBackend backend = backend();
        return backend != null && backend.restoreLastVisibleWindows();
    }

    static boolean advanceAltTab(final boolean reverse) {
        final MagicDeskRuntimeBackend backend = backend();
        return backend != null && backend.advanceAltTab(reverse);
    }

    static boolean finishAltTab() {
        final MagicDeskRuntimeBackend backend = backend();
        return backend != null && backend.finishAltTab();
    }

    static boolean cancelAltTab() {
        final MagicDeskRuntimeBackend backend = backend();
        return backend != null && backend.cancelAltTab();
    }

    static boolean toggleShortcutHelp() {
        final MagicDeskRuntimeBackend backend = backend();
        return backend != null && backend.toggleShortcutHelp();
    }

    static boolean toggleNotificationCenter() {
        final MagicDeskRuntimeBackend backend = backend();
        return backend != null && backend.toggleNotificationCenter();
    }

    static boolean toggleSystemPanel() {
        final MagicDeskRuntimeBackend backend = backend();
        return backend != null && backend.toggleSystemPanel();
    }

    static boolean openSettings() {
        final MagicDeskRuntimeBackend backend = backend();
        return backend != null && backend.openSettings();
    }

    static void parkDesktopTasks(
            final DesktopDisplayTarget source,
            final DesktopTaskParkingRuntime.ResultCallback callback) {
        final DesktopTaskParkingRuntime parking = desktopTaskParking();
        if (parking != null) {
            parking.park(source, callback);
        } else if (callback != null) {
            callback.onComplete(false);
        }
    }

    static void preserveDesktopTasks(final int displayId) {
        final DesktopTaskParkingRuntime parking = desktopTaskParking();
        if (parking != null) {
            parking.preserve(displayId);
        }
    }

    static void restoreParkedDesktopTasksWhenReady(
            final DesktopDisplayTarget target) {
        final DesktopTaskParkingRuntime parking = desktopTaskParking();
        if (parking != null) {
            parking.restoreWhenReady(target);
        }
    }

    static void onDesktopHostReadyForParkedTasks(final int displayId) {
        final DesktopTaskParkingRuntime parking = desktopTaskParking();
        if (parking != null) {
            parking.onDesktopHostReady(displayId);
        }
    }

    static void clearParkedDesktopTasks() {
        final DesktopTaskParkingRuntime parking = desktopTaskParking();
        if (parking != null) {
            parking.clear();
        }
    }

    static List<TaskRepository.TaskEntry> getVisibleFreeformTasks(
            final int displayId) {
        final DesktopTaskRuntime tasks = desktopTasks();
        return tasks == null ? null : tasks.getVisibleFreeformTasks(displayId);
    }

    static boolean isTaskObserverReady() {
        final DesktopTaskRuntime tasks = desktopTasks();
        return tasks != null && tasks.isTaskObserverReady();
    }

    static int launchWindowedTask(
            final int displayId,
            final Intent intent,
            final Rect bounds) throws IOException {
        final DesktopTaskRuntime tasks = desktopTasks();
        if (tasks == null) {
            throw new IOException("desktop task runtime unavailable");
        }
        return tasks.launchWindowedTask(displayId, intent, bounds);
    }

    static int launchFullscreenTaskInDesktopArea(
            final int displayId,
            final Intent intent) throws IOException {
        final DesktopTaskRuntime tasks = desktopTasks();
        if (tasks == null) {
            throw new IOException("desktop task runtime unavailable");
        }
        return tasks.launchFullscreenTaskInDesktopArea(displayId, intent);
    }

    static int launchFullscreenTask(
            final int displayId,
            final Intent intent) throws IOException {
        final DesktopTaskRuntime tasks = desktopTasks();
        if (tasks == null) {
            throw new IOException("desktop task runtime unavailable");
        }
        return tasks.launchFullscreenTask(displayId, intent);
    }

    static void launchTaskAction(
            final int displayId,
            final int taskId,
            final Intent intent) throws IOException {
        final DesktopTaskRuntime tasks = desktopTasks();
        if (tasks == null) {
            throw new IOException("desktop task runtime unavailable");
        }
        tasks.launchTaskAction(displayId, taskId, intent);
    }

    static void placeTaskInDesktopArea(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final Rect bounds) throws IOException {
        final DesktopTaskRuntime tasks = desktopTasks();
        if (tasks == null) {
            throw new IOException("desktop task runtime unavailable");
        }
        tasks.placeTaskInDesktopArea(
                taskId, sourceDisplayId, targetDisplayId, bounds);
    }

    static void placeFullscreenTaskInDesktopArea(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId) throws IOException {
        final DesktopTaskRuntime tasks = desktopTasks();
        if (tasks == null) {
            throw new IOException("desktop task runtime unavailable");
        }
        tasks.placeFullscreenTaskInDesktopArea(
                taskId, sourceDisplayId, targetDisplayId);
    }

    static void closeTask(
            final TaskRepository.TaskEntry task,
            final TaskRepository.ActionCallback callback) {
        final DesktopTaskRuntime tasks = desktopTasks();
        if (tasks == null || !tasks.closeTask(task, callback)) {
            TaskRepository.closeTask(task, callback);
        }
    }

    static void forceStopPackage(
            final String packageName,
            final TaskRepository.ActionCallback callback) {
        final DesktopTaskRuntime tasks = desktopTasks();
        if (tasks == null
                || !tasks.forceStopPackage(packageName, callback)) {
            TaskRepository.forceStop(packageName, callback);
        }
    }

    static List<TaskRepository.TaskEntry> getLastVisibleFreeformTasks(
            final int displayId) {
        final DesktopTaskRuntime tasks = desktopTasks();
        return tasks == null
                ? Collections.emptyList()
                : tasks.getLastVisibleFreeformTasks(displayId);
    }

    static Boolean hasVisibleAppTaskSnapshot(final int displayId) {
        final DesktopTaskRuntime tasks = desktopTasks();
        return tasks == null ? null : tasks.hasVisibleAppTaskSnapshot(displayId);
    }

    static void beginFullscreenTransition(
            final int displayId,
            final List<TaskRepository.TaskEntry> visibleTasks,
            final int excludedTaskId) {
        final DesktopTaskRuntime tasks = desktopTasks();
        if (tasks != null) {
            tasks.beginFullscreenTransition(
                    displayId, visibleTasks, excludedTaskId);
        }
    }

    static void finishFullscreenTransition(
            final int displayId, final boolean success) {
        final DesktopTaskRuntime tasks = desktopTasks();
        if (tasks != null) {
            tasks.finishFullscreenTransition(displayId, success);
        }
    }

    static void forgetVisibleFreeformTasks(final int displayId) {
        final DesktopTaskRuntime tasks = desktopTasks();
        if (tasks != null) {
            tasks.forgetVisibleFreeformTasks(displayId);
        }
    }

    static void focusStack(
            final List<TaskRepository.TaskEntry> topFirstTasks,
            final TaskRepository.TaskEntry topTask,
            final TaskRepository.ActionCallback callback) {
        final DesktopTaskRuntime tasks = desktopTasks();
        if (tasks != null) {
            tasks.focusStack(topFirstTasks, topTask, callback);
        } else {
            TaskRepository.bringStackToFront(
                    topFirstTasks, topTask, callback);
        }
    }

    static void focusDesktopTask(
            final int displayId,
            final int taskId,
            final TaskRepository.ActionCallback callback) {
        focusDesktopTasks(
                displayId,
                Collections.singletonList(Integer.valueOf(taskId)),
                callback);
    }

    static void focusDesktopTasks(
            final int displayId,
            final List<Integer> taskIds,
            final TaskRepository.ActionCallback callback) {
        if (taskIds == null || taskIds.isEmpty()) {
            completeTaskAction(callback, false, "no tasks");
            return;
        }
        final DesktopTaskRuntime tasks = desktopTasks();
        if (tasks != null) {
            tasks.focusDesktopTasks(displayId, taskIds, callback);
        } else {
            TaskRepository.runFocusAction(displayId, taskIds, callback);
        }
    }

    static void toggleTaskbarTask(
            final int displayId,
            final int taskId,
            final TaskRepository.ActionCallback callback) {
        final DesktopTaskRuntime tasks = desktopTasks();
        if (tasks == null) {
            completeTaskAction(callback, false, "desktop task runtime unavailable");
            return;
        }
        tasks.toggleTaskbarTask(displayId, taskId, callback);
    }

    static boolean handleActiveTaskShortcut(final int shortcut) {
        final DesktopTaskRuntime tasks = desktopTasks();
        return tasks != null && tasks.handleActiveTaskShortcut(shortcut);
    }

    static boolean arrangeTask(final int taskId, final int shortcut) {
        final DesktopTaskRuntime tasks = desktopTasks();
        return tasks != null && tasks.arrangeTask(taskId, shortcut);
    }

    static void noteManualFreeformTransition(final int taskId) {
        final DesktopTaskRuntime tasks = desktopTasks();
        if (tasks != null) {
            tasks.noteManualFreeformTransition(taskId);
        }
    }

    static void beginExplicitWindowedLaunch(final int taskId) {
        final DesktopTaskRuntime tasks = desktopTasks();
        if (tasks != null) {
            tasks.beginExplicitWindowedLaunch(taskId);
        }
    }

    static void finishExplicitWindowedLaunch(final int taskId) {
        final DesktopTaskRuntime tasks = desktopTasks();
        if (tasks != null) {
            tasks.finishExplicitWindowedLaunch(taskId);
        }
    }

    static boolean protectExplicitFullscreenTask(
            final int displayId,
            final int taskId) {
        final DesktopTaskRuntime tasks = desktopTasks();
        return tasks != null
                && tasks.protectExplicitFullscreenTask(displayId, taskId);
    }

    static void expectTouchpadDisplacement() {
        final DesktopTaskRuntime tasks = desktopTasks();
        if (tasks != null) {
            tasks.expectTouchpadDisplacement();
        }
    }

    static void finishTouchpadPreservation() {
        final DesktopTaskRuntime tasks = desktopTasks();
        if (tasks != null) {
            tasks.finishTouchpadPreservation();
        }
    }

    static void setPhoneTouchpadRequested(final boolean requested) {
        final DesktopTaskRuntime tasks = desktopTasks();
        if (tasks != null) {
            tasks.setPhoneTouchpadRequested(requested);
        }
    }

    static void disableExternalTaskMigrationProtection() {
        final DesktopTaskRuntime tasks = desktopTasks();
        if (tasks != null) {
            tasks.disableExternalTaskMigrationProtection();
        }
    }

    static void restoreExternalTaskMigrationProtection() {
        final DesktopTaskRuntime tasks = desktopTasks();
        if (tasks != null) {
            tasks.restoreExternalTaskMigrationProtection();
        }
    }

    static boolean dismissTransientActivity() {
        final DesktopTaskRuntime tasks = desktopTasks();
        return tasks != null && tasks.dismissTransientActivity();
    }

    static boolean sendSystemBack() {
        final DesktopTaskRuntime tasks = desktopTasks();
        return tasks != null && tasks.sendSystemBack();
    }

    static boolean startSelfTestTaskStackGuard(
            final int displayId,
            final int hostTaskId,
            final String stage) {
        final DesktopTaskRuntime tasks = desktopTasks();
        return tasks != null && tasks.startSelfTestTaskStackGuard(
                displayId, hostTaskId, stage);
    }

    static void setSelfTestTaskStackGuardStage(final String stage) {
        final DesktopTaskRuntime tasks = desktopTasks();
        if (tasks != null) {
            tasks.setSelfTestTaskStackGuardStage(stage);
        }
    }

    static SelfTestTaskStackReport stopSelfTestTaskStackGuard() {
        final DesktopTaskRuntime tasks = desktopTasks();
        return tasks == null
                ? SelfTestTaskStackReport.unavailable(
                        "desktop task runtime unavailable")
                : tasks.stopSelfTestTaskStackGuard();
    }

    static TaskWindowSnapshot inspectTaskWindow(
            final int displayId,
            final int taskId) {
        final DesktopTaskRuntime tasks = desktopTasks();
        return tasks == null
                ? null : tasks.inspectTaskWindow(displayId, taskId);
    }

    static synchronized void attach(
            final MagicDeskRuntimeBackend backend) {
        if (backend != null) {
            sBackend = new WeakReference<>(backend);
        }
    }

    static synchronized void detach(
            final MagicDeskRuntimeBackend backend) {
        if (sBackend.get() == backend) {
            sBackend.clear();
        }
    }

    private static synchronized MagicDeskRuntimeBackend backend() {
        final MagicDeskRuntimeBackend backend = sBackend.get();
        return backend != null && backend.isAvailable() ? backend : null;
    }

    private static DesktopTaskRuntime desktopTasks() {
        final MagicDeskRuntimeBackend backend = backend();
        return backend == null ? null : backend.desktopTasks();
    }

    private static DesktopTaskParkingRuntime desktopTaskParking() {
        final MagicDeskRuntimeBackend backend = backend();
        return backend == null ? null : backend.desktopTaskParking();
    }

    private static void completeTaskAction(
            final TaskRepository.ActionCallback callback,
            final boolean success,
            final String message) {
        if (callback != null) {
            callback.onComplete(
                    new TaskRepository.ActionResult(success, message));
        }
    }
}

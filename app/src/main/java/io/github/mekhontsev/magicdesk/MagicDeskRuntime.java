package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.Intent;
import android.graphics.Point;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;

/** Stable process-local entry point for the optional runtime service. */
public final class MagicDeskRuntime {
    private static WeakReference<MagicDeskRuntimeBackend> sBackend =
            new WeakReference<>(null);

    private MagicDeskRuntime() {
    }

    public static void start(final Context context) {
        context.startForegroundService(
                new Intent(context, MagicDeskRuntimeService.class));
    }

    public static void stop(final Context context) {
        context.stopService(
                new Intent(context, MagicDeskRuntimeService.class));
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

    static void refreshSettings() {
        final MagicDeskRuntimeBackend backend = backend();
        if (backend != null) {
            backend.refreshSettings();
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

    static List<TaskRepository.TaskEntry> getVisibleFreeformTasks(
            final int displayId) {
        final DesktopTaskRuntime tasks = desktopTasks();
        return tasks == null ? null : tasks.getVisibleFreeformTasks(displayId);
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

    static boolean handleActiveTaskShortcut(final int shortcut) {
        final DesktopTaskRuntime tasks = desktopTasks();
        return tasks != null && tasks.handleActiveTaskShortcut(shortcut);
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

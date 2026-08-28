package io.github.mekhontsev.magicdesk;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

/** Joins input-window state with shell process-failure observations. */
final class DesktopWindowObservation {
    private final TaskInputWindowParser.WindowSnapshot mWindows;
    private final String mError;

    private DesktopWindowObservation(
            final TaskInputWindowParser.WindowSnapshot windows,
            final String error) {
        mWindows = windows;
        mError = error == null ? "" : error;
    }

    static DesktopWindowObservation capture() {
        try {
            final TaskInputWindowParser.WindowSnapshot windows =
                    TaskInputWindowParser.readWindowSnapshot(
                            FrameworkInputSnapshotSource.readRemote());
            return windows.available
                    ? new DesktopWindowObservation(windows, "")
                    : unavailable("input dispatcher state unavailable");
        } catch (IOException | RuntimeException error) {
            return unavailable(ShellAccess.usefulMessage(error));
        }
    }

    static DesktopWindowObservation fromDump(final String dump) {
        final TaskInputWindowParser.WindowSnapshot windows =
                TaskInputWindowParser.readWindowSnapshot(dump);
        return windows.available
                ? new DesktopWindowObservation(windows, "")
                : unavailable("input dispatcher state unavailable");
    }

    private static DesktopWindowObservation unavailable(final String error) {
        return new DesktopWindowObservation(
                TaskInputWindowParser.WindowSnapshot.unavailable(), error);
    }

    boolean available() {
        return mWindows.available;
    }

    TaskHealth health(final TaskRepository.TaskEntry task) {
        if (task == null) {
            return TaskHealth.missing();
        }
        final TaskInputWindowParser.WindowState appWindow =
                mWindows.processWindow(
                        task.displayId, task.taskId, task.packageName);
        final TaskInputWindowParser.FocusedWindow focused =
                mWindows.focusedWindow(task.displayId);
        final TaskInputWindowParser.WindowState dialog =
                relatedFocusedDialog(task, focused);
        final DesktopProcessHealthRegistry.Failure failure =
                DesktopProcessHealthRegistry.resolve(
                        task.taskId, task.displayId, mWindows);
        final boolean focusedTask = focused != null
                && focused.effectiveTaskId() == task.taskId;
        final boolean inputFocused = focusedTask
                && focused.taskId == task.taskId
                && focused.packageName.equals(task.packageName);
        final boolean crossPackageWindow = focusedTask
                && !focused.packageName.isEmpty()
                && !focused.packageName.equals(task.packageName);
        final boolean blocked = dialog != null || crossPackageWindow;
        final boolean crashed = isKind(
                        dialog,
                        TaskInputWindowParser.WindowState.KIND_CRASH_DIALOG)
                || (failure != null && failure.crashed());
        final boolean notResponding = isKind(
                        dialog,
                        TaskInputWindowParser.WindowState.KIND_ANR_DIALOG)
                || (failure != null && failure.notResponding());
        final boolean rendered = appWindow != null;
        final Boolean processAlive;
        if (failure != null
                && failure.crashed()
                && failure.processPackage.equals(task.packageName)) {
            processAlive = Boolean.FALSE;
        } else if (appWindow != null && appWindow.ownerPid > 0) {
            processAlive = Boolean.TRUE;
        } else {
            processAlive = null;
        }
        final boolean ready = task.visible
                && rendered
                && !Boolean.FALSE.equals(processAlive)
                && !blocked
                && !crashed
                && !notResponding;
        return new TaskHealth(
                true,
                mWindows.available,
                rendered,
                inputFocused,
                processAlive,
                ready,
                blocked,
                crashed,
                notResponding,
                appWindow,
                focused,
                dialog,
                failure);
    }

    boolean hasBlockingSystemDialog(
            final Integer displayId,
            final Integer taskId,
            final String packageName,
            final TaskRepository.Snapshot tasks) {
        final TaskRepository.TaskEntry requestedTask = taskId == null
                ? null : findTask(tasks, taskId.intValue());
        final String requestedPackage = packageName.isEmpty()
                && requestedTask != null
                        ? requestedTask.packageName : packageName;
        for (final TaskInputWindowParser.WindowState dialog
                : mWindows.systemDialogs()) {
            if (!dialog.isErrorDialog()
                    || (displayId != null
                            && dialog.displayId != displayId.intValue())) {
                continue;
            }
            if (!requestedPackage.isEmpty()
                    && !requestedPackage.equals(dialog.packageName)) {
                continue;
            }
            if (taskId == null
                    || requestedTask != null
                    || !packageName.isEmpty()) {
                return true;
            }
        }
        for (final TaskInputWindowParser.FocusedWindow focused
                : mWindows.focusedWindows()) {
            if (displayId != null
                    && focused.displayId != displayId.intValue()) {
                continue;
            }
            final TaskRepository.TaskEntry task = findTask(
                    tasks, focused.effectiveTaskId());
            if (taskId != null
                    && focused.effectiveTaskId() != taskId.intValue()
                    && !packageName.equals(focused.packageName)) {
                continue;
            }
            if (!packageName.isEmpty()
                    && !packageName.equals(focused.packageName)
                    && (task == null
                            || !packageName.equals(task.packageName))) {
                continue;
            }
            if (focused.isSystemDialog()) {
                return true;
            }
            if (task != null
                    && !focused.packageName.isEmpty()
                    && !focused.packageName.equals(task.packageName)) {
                return taskId == null || task.taskId == taskId.intValue();
            }
        }
        return false;
    }

    JSONObject toJson() throws JSONException {
        final JSONArray focused = new JSONArray();
        for (final TaskInputWindowParser.FocusedWindow window
                : mWindows.focusedWindows()) {
            focused.put(windowJson(window)
                    .put("applicationTaskId", window.applicationTaskId));
        }
        final JSONArray dialogs = new JSONArray();
        for (final TaskInputWindowParser.WindowState dialog
                : mWindows.systemDialogs()) {
            final TaskInputWindowParser.FocusedWindow focusedWindow =
                    mWindows.focusedWindow(dialog.displayId);
            dialogs.put(windowJson(dialog).put(
                    "focused",
                    focusedWindow != null
                            && dialog.windowId.equals(
                                    focusedWindow.windowId)));
        }
        return new JSONObject()
                .put("available", available())
                .put("error", mError)
                .put("focused", focused)
                .put("systemDialogs", dialogs);
    }

    private TaskInputWindowParser.WindowState relatedFocusedDialog(
            final TaskRepository.TaskEntry task,
            final TaskInputWindowParser.FocusedWindow focused) {
        final TaskInputWindowParser.WindowState error =
                mWindows.errorDialogFor(task.displayId, task.packageName);
        if (error != null) {
            return error;
        }
        final TaskInputWindowParser.WindowState direct =
                mWindows.focusedDialogFor(
                        task.displayId, task.taskId, task.packageName);
        if (direct != null) {
            return direct;
        }
        if (focused != null
                && focused.effectiveTaskId() == task.taskId
                && !focused.packageName.isEmpty()
                && !focused.packageName.equals(task.packageName)) {
            return focused;
        }
        return null;
    }

    private static boolean isKind(
            final TaskInputWindowParser.WindowState window,
            final String kind) {
        return window != null && kind.equals(window.kind);
    }

    private static TaskRepository.TaskEntry findTask(
            final TaskRepository.Snapshot snapshot, final int taskId) {
        if (snapshot == null || !snapshot.available || taskId < 0) {
            return null;
        }
        for (final TaskRepository.TaskEntry task : snapshot.tasks) {
            if (task.taskId == taskId) {
                return task;
            }
        }
        return null;
    }

    private static JSONObject windowJson(
            final TaskInputWindowParser.WindowState window)
            throws JSONException {
        if (window == null) {
            return new JSONObject();
        }
        return new JSONObject()
                .put("displayId", window.displayId)
                .put("windowId", window.windowId)
                .put("title", window.title)
                .put("kind", window.kind)
                .put("package", window.packageName)
                .put("taskId", window.taskId)
                .put("ownerPid", window.ownerPid)
                .put("ownerUid", window.ownerUid);
    }

    static final class TaskHealth {
        final boolean taskPresent;
        final boolean windowStateAvailable;
        final boolean rendered;
        final boolean inputFocused;
        final Boolean processAlive;
        final boolean ready;
        final boolean blockedBySystemDialog;
        final boolean crashed;
        final boolean notResponding;
        final TaskInputWindowParser.WindowState appWindow;
        final TaskInputWindowParser.FocusedWindow focusedWindow;
        final TaskInputWindowParser.WindowState systemDialog;
        final DesktopProcessHealthRegistry.Failure failure;

        TaskHealth(
                final boolean taskPresent,
                final boolean windowStateAvailable,
                final boolean rendered,
                final boolean inputFocused,
                final Boolean processAlive,
                final boolean ready,
                final boolean blockedBySystemDialog,
                final boolean crashed,
                final boolean notResponding,
                final TaskInputWindowParser.WindowState appWindow,
                final TaskInputWindowParser.FocusedWindow focusedWindow,
                final TaskInputWindowParser.WindowState systemDialog,
                final DesktopProcessHealthRegistry.Failure failure) {
            this.taskPresent = taskPresent;
            this.windowStateAvailable = windowStateAvailable;
            this.rendered = rendered;
            this.inputFocused = inputFocused;
            this.processAlive = processAlive;
            this.ready = ready;
            this.blockedBySystemDialog = blockedBySystemDialog;
            this.crashed = crashed;
            this.notResponding = notResponding;
            this.appWindow = appWindow;
            this.focusedWindow = focusedWindow;
            this.systemDialog = systemDialog;
            this.failure = failure;
        }

        static TaskHealth missing() {
            return new TaskHealth(
                    false, false, false, false, null, false, false, false,
                    false, null, null, null, null);
        }

        JSONObject toJson() throws JSONException {
            final JSONObject result = new JSONObject()
                    .put("state", state())
                    .put("windowStateAvailable", windowStateAvailable)
                    .put("rendered", rendered)
                    .put("inputFocused", inputFocused)
                    .put("processAlive", processAlive == null
                            ? JSONObject.NULL : processAlive)
                    .put("ready", ready)
                    .put("blockedBySystemDialog", blockedBySystemDialog)
                    .put("crashed", crashed)
                    .put("notResponding", notResponding);
            result.put("appWindow", appWindow == null
                    ? JSONObject.NULL : windowJson(appWindow));
            result.put("focusedWindow", focusedWindow == null
                    ? JSONObject.NULL : windowJson(focusedWindow)
                            .put("applicationTaskId",
                                    focusedWindow.applicationTaskId));
            result.put("systemDialog", systemDialog == null
                    ? JSONObject.NULL : windowJson(systemDialog));
            result.put("lastFailure", failure == null
                    ? JSONObject.NULL : failure.toJson());
            return result;
        }

        private String state() {
            if (!taskPresent) {
                return "missing";
            }
            if (!windowStateAvailable) {
                return "unknown";
            }
            if (crashed) {
                return "crashed";
            }
            if (notResponding) {
                return "not_responding";
            }
            if (blockedBySystemDialog) {
                return "blocked";
            }
            if (ready) {
                return "ready";
            }
            if (!rendered) {
                return "not_rendered";
            }
            return "unknown";
        }
    }
}

package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.StringReader;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Cached, typed state for the optional Termux:X11 companion process. */
final class TermuxX11RuntimeStatus {
    private static final long RESULT_TIMEOUT_MILLIS = 5_000L;
    private static final ExecutorService PROBE_EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(
                        runnable, "MagicDeskTermuxX11Probe");
                thread.setDaemon(true);
                return thread;
            });

    private static volatile Status sLastStatus = Status.unknown(
            false, false, false, "", "not probed");
    private static boolean sProbeRunning;

    private TermuxX11RuntimeStatus() {
    }

    static Status cached(
            final Context context,
            final TaskRepository.Snapshot tasks) {
        final Status baseline = baseline(context);
        final Status current = compatibleStatus(baseline);
        return tasks == null
                ? current : current.withViewer(findViewerTask(tasks));
    }

    static Status refreshBlocking(
            final Context context,
            final TaskRepository.Snapshot tasks) {
        final Status baseline = baseline(context);
        if (!baseline.available()) {
            final Status resolved = baseline.withViewer(
                    findViewerTask(tasks));
            publish(resolved);
            return resolved;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            refreshAsync(context);
            return compatibleStatus(baseline)
                    .withViewer(findViewerTask(tasks));
        }
        final CountDownLatch complete = new CountDownLatch(1);
        final Status[] result = new Status[1];
        runProbe(baseline, status -> {
            result[0] = status;
            complete.countDown();
        });
        try {
            if (!complete.await(
                    RESULT_TIMEOUT_MILLIS + 1_000L,
                    TimeUnit.MILLISECONDS)) {
                result[0] = baseline.withProbeError("status probe timed out");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            result[0] = baseline.withProbeError("status probe interrupted");
        }
        final Status resolved = result[0] == null
                ? baseline.withProbeError("status probe produced no result")
                : result[0];
        final Status withViewer = resolved.withViewer(findViewerTask(tasks));
        publish(withViewer);
        return withViewer;
    }

    static void refreshAsync(final Context context) {
        final Context appContext = context.getApplicationContext();
        final Status baseline = baseline(appContext);
        if (!baseline.available()) {
            publish(baseline);
            return;
        }
        synchronized (TermuxX11RuntimeStatus.class) {
            if (sProbeRunning) {
                return;
            }
            sProbeRunning = true;
        }
        runProbe(baseline, status -> {
            publish(status.withViewer(
                    compatibleStatus(baseline).viewerTask));
            synchronized (TermuxX11RuntimeStatus.class) {
                sProbeRunning = false;
            }
        });
    }

    static void reconnect(
            final Context context,
            final OperationCallback callback) {
        final Context appContext = context.getApplicationContext();
        final Status baseline = baseline(appContext);
        if (!baseline.available()) {
            notifyOperation(callback, OperationResult.failure(
                    baseline.detail));
            return;
        }
        final String command;
        try {
            command = TermuxX11StartupCommand.reconnect(
                    MagicDeskSettings.load().termuxX11StartupCommand);
        } catch (IllegalArgumentException error) {
            notifyOperation(callback, OperationResult.failure(
                    error.getMessage()));
            return;
        }
        try {
            TermuxIntegration.runBackgroundShellCommandForResult(
                    appContext,
                    command,
                    "MagicDesk Termux:X11 reconnect",
                    TermuxIntegration.HOME_DIRECTORY,
                    RESULT_TIMEOUT_MILLIS,
                    (result, error) -> {
                        if (error != null) {
                            final Status failed = baseline.withProbeError(
                                    ShellAccess.usefulMessage(error));
                            publish(failed);
                            notifyOperation(callback, OperationResult.failure(
                                    failed.error));
                            return;
                        }
                        if (result == null || !result.success()) {
                            final String message = result == null
                                    ? "missing Termux command result"
                                    : result.usefulMessage();
                            final Status failed = baseline.withProbeError(
                                    message);
                            publish(failed);
                            notifyOperation(callback, OperationResult.failure(
                                    message));
                            return;
                        }
                        refreshAsync(appContext);
                        notifyOperation(callback, OperationResult.success(
                                "Termux:X11 viewer reconnected"));
                    });
        } catch (RuntimeException error) {
            final Status failed = baseline.withProbeError(
                    ShellAccess.usefulMessage(error));
            publish(failed);
            notifyOperation(callback, OperationResult.failure(
                    failed.error));
        }
    }

    static OperationResult reconnectBlocking(final Context context) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            reconnect(context, null);
            return OperationResult.success(
                    "Termux:X11 reconnect requested");
        }
        final CountDownLatch complete = new CountDownLatch(1);
        final OperationResult[] result = new OperationResult[1];
        reconnect(context, operation -> {
            result[0] = operation;
            complete.countDown();
        });
        try {
            if (!complete.await(
                    RESULT_TIMEOUT_MILLIS + 1_000L,
                    TimeUnit.MILLISECONDS)) {
                return OperationResult.failure(
                        "Termux:X11 reconnect timed out");
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return OperationResult.failure(
                    "Termux:X11 reconnect interrupted");
        }
        return result[0] == null
                ? OperationResult.failure(
                        "Termux:X11 reconnect produced no result")
                : result[0];
    }

    private static void runProbe(
            final Status baseline,
            final StatusCallback callback) {
        final String command = TermuxX11StartupCommand.statusProbe(
                MagicDeskSettings.load().termuxX11StartupCommand);
        PROBE_EXECUTOR.execute(() -> {
            Status status;
            try {
                // Android hides global socket tables from ordinary app UIDs.
                // MagicDesk's shell service can inspect both the Termux-owned
                // process and its loopback listener without touching either.
                status = parseProbe(baseline, ShellAccess.run(command));
            } catch (IOException | RuntimeException error) {
                status = baseline.withProbeError(
                        ShellAccess.usefulMessage(error));
            }
            callback.onStatus(status);
        });
    }

    private static Status parseProbe(
            final Status baseline,
            final String output) {
        final Properties values = new Properties();
        try {
            values.load(new StringReader(output == null ? "" : output));
        } catch (IOException error) {
            return baseline.withProbeError("invalid status output");
        }
        if (!"1".equals(values.getProperty("format"))) {
            return baseline.withProbeError("unsupported status output");
        }
        final boolean serverFound = Boolean.parseBoolean(
                values.getProperty("serverFound", "false"));
        final boolean socketListening = Boolean.parseBoolean(
                values.getProperty("socketListening", "false"));
        final String requestedDisplay = values.getProperty(
                "requestedDisplay", baseline.requestedDisplay);
        final String state;
        final String detail;
        if ("unknown".equals(requestedDisplay)) {
            state = "unknown";
            detail = "startup command display is not directly detectable";
        } else if (serverFound && socketListening) {
            state = "running";
            detail = "server and reconnect listener are ready";
        } else if (!serverFound && !socketListening) {
            state = "stopped";
            detail = "server is not running";
        } else {
            state = "degraded";
            detail = serverFound
                    ? "server process has no reconnect listener"
                    : "reconnect listener has no matching display process";
        }
        return new Status(
                baseline.termuxInstalled,
                baseline.x11Installed,
                baseline.runCommandPermission,
                requestedDisplay,
                state,
                serverFound,
                parseInt(values.getProperty("serverPid"), -1),
                values.getProperty("serverDisplay", ""),
                socketListening,
                SystemClock.elapsedRealtime(),
                detail,
                "",
                null);
    }

    private static Status baseline(final Context context) {
        final boolean termuxInstalled = TermuxIntegration.isInstalled(context);
        final boolean x11Installed = TermuxX11Integration.isInstalled(context);
        final boolean permission = context.checkSelfPermission(
                TermuxIntegration.RUN_COMMAND_PERMISSION)
                == PackageManager.PERMISSION_GRANTED;
        final String parsedDisplay = TermuxX11StartupCommand.requestedDisplay(
                MagicDeskSettings.load().termuxX11StartupCommand);
        final String display = parsedDisplay.isEmpty()
                ? "unknown" : parsedDisplay;
        if (!termuxInstalled) {
            return Status.unknown(false, x11Installed, permission,
                    display, "Termux is not installed");
        }
        if (!x11Installed) {
            return Status.unknown(true, false, permission,
                    display, "Termux:X11 is not installed");
        }
        if (!permission) {
            return Status.unknown(true, true, false,
                    display, "Termux RUN_COMMAND permission is not granted");
        }
        return Status.unknown(true, true, true,
                display, "not probed");
    }

    private static Status compatibleStatus(final Status baseline) {
        final Status current = sLastStatus;
        if (current.termuxInstalled != baseline.termuxInstalled
                || current.x11Installed != baseline.x11Installed
                || current.runCommandPermission
                        != baseline.runCommandPermission
                || !current.requestedDisplay.equals(
                        baseline.requestedDisplay)) {
            return baseline;
        }
        return current;
    }

    private static synchronized void publish(final Status status) {
        if (status != null
                && status.probedAtElapsedMillis
                        >= sLastStatus.probedAtElapsedMillis) {
            sLastStatus = status;
        }
    }

    private static TaskRepository.TaskEntry findViewerTask(
            final TaskRepository.Snapshot snapshot) {
        if (snapshot == null || !snapshot.available) {
            return null;
        }
        TaskRepository.TaskEntry fallback = null;
        for (final TaskRepository.TaskEntry task : snapshot.tasks) {
            if (!TermuxX11Integration.PACKAGE_NAME.equals(task.packageName)) {
                continue;
            }
            if (task.active || task.visible) {
                return task;
            }
            fallback = task;
        }
        return fallback;
    }

    private static int parseInt(final String value, final int fallback) {
        try {
            return Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static void notifyOperation(
            final OperationCallback callback,
            final OperationResult result) {
        if (callback != null) {
            callback.onComplete(result);
        }
    }

    interface OperationCallback {
        void onComplete(OperationResult result);
    }

    private interface StatusCallback {
        void onStatus(Status status);
    }

    static final class OperationResult {
        final boolean success;
        final String message;

        private OperationResult(
                final boolean success,
                final String message) {
            this.success = success;
            this.message = message == null ? "" : message;
        }

        static OperationResult success(final String message) {
            return new OperationResult(true, message);
        }

        static OperationResult failure(final String message) {
            return new OperationResult(false, message);
        }
    }

    static final class Status {
        final boolean termuxInstalled;
        final boolean x11Installed;
        final boolean runCommandPermission;
        final String requestedDisplay;
        final String state;
        final boolean serverFound;
        final int serverPid;
        final String serverDisplay;
        final boolean socketListening;
        final long probedAtElapsedMillis;
        final String detail;
        final String error;
        final TaskRepository.TaskEntry viewerTask;

        private Status(
                final boolean termuxInstalled,
                final boolean x11Installed,
                final boolean runCommandPermission,
                final String requestedDisplay,
                final String state,
                final boolean serverFound,
                final int serverPid,
                final String serverDisplay,
                final boolean socketListening,
                final long probedAtElapsedMillis,
                final String detail,
                final String error,
                final TaskRepository.TaskEntry viewerTask) {
            this.termuxInstalled = termuxInstalled;
            this.x11Installed = x11Installed;
            this.runCommandPermission = runCommandPermission;
            this.requestedDisplay = requestedDisplay == null
                    ? "" : requestedDisplay;
            this.state = state == null ? "unknown" : state;
            this.serverFound = serverFound;
            this.serverPid = serverPid;
            this.serverDisplay = serverDisplay == null ? "" : serverDisplay;
            this.socketListening = socketListening;
            this.probedAtElapsedMillis = probedAtElapsedMillis;
            this.detail = detail == null ? "" : detail;
            this.error = error == null ? "" : error;
            this.viewerTask = viewerTask;
        }

        static Status unknown(
                final boolean termuxInstalled,
                final boolean x11Installed,
                final boolean runCommandPermission,
                final String requestedDisplay,
                final String detail) {
            return new Status(
                    termuxInstalled,
                    x11Installed,
                    runCommandPermission,
                    requestedDisplay,
                    "unknown",
                    false,
                    -1,
                    "",
                    false,
                    0L,
                    detail,
                    "",
                    null);
        }

        boolean available() {
            return termuxInstalled && x11Installed && runCommandPermission;
        }

        Status withProbeError(final String message) {
            return new Status(
                    termuxInstalled,
                    x11Installed,
                    runCommandPermission,
                    requestedDisplay,
                    "error",
                    false,
                    -1,
                    "",
                    false,
                    SystemClock.elapsedRealtime(),
                    "status unavailable",
                    message,
                    viewerTask);
        }

        Status withViewer(final TaskRepository.TaskEntry task) {
            return new Status(
                    termuxInstalled,
                    x11Installed,
                    runCommandPermission,
                    requestedDisplay,
                    state,
                    serverFound,
                    serverPid,
                    serverDisplay,
                    socketListening,
                    probedAtElapsedMillis,
                    detail,
                    error,
                    task);
        }

        JSONObject toJson() throws JSONException {
            final JSONObject viewer = new JSONObject()
                    .put("present", viewerTask != null);
            if (viewerTask != null) {
                viewer.put("taskId", viewerTask.taskId)
                        .put("displayId", viewerTask.displayId)
                        .put("mode", viewerTask.windowingMode)
                        .put("visible", viewerTask.visible)
                        .put("active", viewerTask.active);
            }
            return new JSONObject()
                    .put("state", state)
                    .put("available", available())
                    .put("termuxInstalled", termuxInstalled)
                    .put("viewerInstalled", x11Installed)
                    .put("runCommandPermission", runCommandPermission)
                    .put("requestedDisplay", requestedDisplay)
                    .put("serverFound", serverFound)
                    .put("serverPid", serverPid >= 0
                            ? serverPid : JSONObject.NULL)
                    .put("serverDisplay", serverDisplay)
                    .put("socketListening", socketListening)
                    .put("probed", probedAtElapsedMillis > 0L)
                    .put("probeAgeMillis", probedAtElapsedMillis > 0L
                            ? Math.max(
                                    0L,
                                    SystemClock.elapsedRealtime()
                                            - probedAtElapsedMillis)
                            : JSONObject.NULL)
                    .put("detail", detail)
                    .put("error", error)
                    .put("viewerTask", viewer);
        }

        String reportLine() {
            return "state=" + state
                    + ", available=" + available()
                    + ", termux=" + termuxInstalled
                    + ", x11=" + x11Installed
                    + ", runCommand=" + runCommandPermission
                    + ", requestedDisplay="
                    + (requestedDisplay.isEmpty()
                            ? "unknown" : requestedDisplay)
                    + ", serverFound=" + serverFound
                    + ", serverDisplay="
                    + (serverDisplay.isEmpty() ? "unknown" : serverDisplay)
                    + ", socketListening=" + socketListening
                    + ", viewerTask="
                    + (viewerTask == null
                            ? "none"
                            : viewerTask.taskId + "@" + viewerTask.displayId)
                    + (detail.isEmpty()
                            ? "" : ", detail=" + oneLine(detail))
                    + (error.isEmpty()
                            ? "" : ", error=" + oneLine(error));
        }

        private static String oneLine(final String value) {
            return value.replace('\n', ' ').replace('\r', ' ').trim();
        }
    }
}

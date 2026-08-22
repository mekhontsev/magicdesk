package io.github.mekhontsev.magicdesk;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded current-process view of failures reported by the shell observer. */
final class DesktopProcessHealthRegistry {
    private static final int MAX_FAILURES = 64;
    private static final Map<Integer, Failure> FAILURES =
            new LinkedHashMap<>();

    private DesktopProcessHealthRegistry() {
    }

    static synchronized void record(
            final int type,
            final String processName,
            final int pid,
            final int taskId,
            final int displayId,
            final String reason) {
        if (taskId < 0
                || (type != DesktopProcessFailure.CRASH
                        && type != DesktopProcessFailure.ANR)) {
            return;
        }
        FAILURES.remove(Integer.valueOf(taskId));
        FAILURES.put(Integer.valueOf(taskId), new Failure(
                type,
                processName,
                processPackage(processName),
                pid,
                taskId,
                displayId,
                System.currentTimeMillis(),
                DesktopProcessFailure.compactReason(reason)));
        while (FAILURES.size() > MAX_FAILURES) {
            final Iterator<Integer> iterator = FAILURES.keySet().iterator();
            if (!iterator.hasNext()) {
                break;
            }
            iterator.next();
            iterator.remove();
        }
    }

    static synchronized Failure resolve(
            final int taskId,
            final int displayId,
            final TaskInputWindowParser.WindowSnapshot windows) {
        final Failure failure = FAILURES.get(Integer.valueOf(taskId));
        if (failure == null || windows == null || !windows.available) {
            return failure;
        }
        final TaskInputWindowParser.WindowState processWindow =
                windows.processWindow(
                        displayId, taskId, failure.processPackage);
        if (processWindow != null
                && processWindow.ownerPid > 0
                && processWindow.ownerPid != failure.pid) {
            FAILURES.remove(Integer.valueOf(taskId));
            return null;
        }
        return failure;
    }

    static synchronized Failure find(final int taskId) {
        return FAILURES.get(Integer.valueOf(taskId));
    }

    static synchronized void clearForTest() {
        FAILURES.clear();
    }

    private static String processPackage(final String processName) {
        if (processName == null) {
            return "";
        }
        final int separator = processName.indexOf(':');
        return separator < 0 ? processName
                : processName.substring(0, separator);
    }

    static final class Failure {
        final int type;
        final String processName;
        final String processPackage;
        final int pid;
        final int taskId;
        final int displayId;
        final long timestampMillis;
        final String reason;

        Failure(
                final int type,
                final String processName,
                final String processPackage,
                final int pid,
                final int taskId,
                final int displayId,
                final long timestampMillis,
                final String reason) {
            this.type = type;
            this.processName = processName == null ? "" : processName;
            this.processPackage = processPackage == null
                    ? "" : processPackage;
            this.pid = pid;
            this.taskId = taskId;
            this.displayId = displayId;
            this.timestampMillis = timestampMillis;
            this.reason = reason == null ? "" : reason;
        }

        boolean crashed() {
            return type == DesktopProcessFailure.CRASH;
        }

        boolean notResponding() {
            return type == DesktopProcessFailure.ANR;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("type", crashed() ? "crash" : "anr")
                    .put("process", processName)
                    .put("pid", pid)
                    .put("taskId", taskId)
                    .put("displayId", displayId)
                    .put("timestampMillis", timestampMillis)
                    .put("reason", reason);
        }
    }
}

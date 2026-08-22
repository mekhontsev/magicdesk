package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.view.Display;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Correlates process failures with the last sampled desktop task stack. */
final class ShellDesktopProcessFailureTracker implements
        ShellActivityStartController.ProcessFailureListener {
    interface Listener {
        void onDesktopProcessFailure(
                int type,
                String processName,
                int pid,
                int taskId,
                int displayId,
                int windowingMode,
                String topActivity,
                String reason);
    }

    private static final int ACTIVITY_TYPE_STANDARD = 1;

    private final Listener mListener;
    private final List<TaskContext> mTasks = new ArrayList<>();
    private final Map<ProcessIdentity, PendingAnr> mPendingAnrs =
            new HashMap<>();

    private int mDisplayId = Display.INVALID_DISPLAY;

    ShellDesktopProcessFailureTracker(final Listener listener) {
        mListener = listener;
    }

    synchronized void configure(final int displayId) {
        if (mDisplayId == displayId) {
            return;
        }
        mDisplayId = displayId;
        mTasks.clear();
        mPendingAnrs.clear();
    }

    synchronized void observeTasks(
            final int displayId,
            final List<ShellTaskStateMonitor.TaskWindowState> tasks) {
        if (displayId != mDisplayId) {
            return;
        }
        mTasks.clear();
        if (tasks == null) {
            return;
        }
        for (final ShellTaskStateMonitor.TaskWindowState task : tasks) {
            if (task.activityType != ACTIVITY_TYPE_STANDARD
                    || (task.packageName == null
                            && task.topPackage == null)) {
                continue;
            }
            mTasks.add(new TaskContext(task, displayId));
        }
    }

    @Override
    public void onProcessCrashed(
            final String processName,
            final int pid,
            final String shortMessage) {
        final Failure failure;
        synchronized (this) {
            mPendingAnrs.remove(new ProcessIdentity(processName, pid));
            failure = createFailure(
                    DesktopProcessFailure.CRASH,
                    processName,
                    pid,
                    shortMessage,
                    null);
        }
        report(failure);
    }

    @Override
    public synchronized void onProcessEarlyNotResponding(
            final String processName,
            final int pid,
            final String annotation) {
        final TaskContext task = findTask(processName);
        if (task == null) {
            return;
        }
        mPendingAnrs.put(
                new ProcessIdentity(processName, pid),
                new PendingAnr(task, annotation));
    }

    @Override
    public void onProcessNotResponding(
            final String processName,
            final int pid) {
        final Failure failure;
        synchronized (this) {
            final PendingAnr pending = mPendingAnrs.remove(
                    new ProcessIdentity(processName, pid));
            failure = createFailure(
                    DesktopProcessFailure.ANR,
                    processName,
                    pid,
                    pending == null ? "" : pending.annotation,
                    pending == null ? null : pending.task);
        }
        report(failure);
    }

    private Failure createFailure(
            final int type,
            final String processName,
            final int pid,
            final String reason,
            final TaskContext capturedTask) {
        final TaskContext task = capturedTask == null
                ? findTask(processName) : capturedTask;
        if (task == null) {
            return null;
        }
        return new Failure(
                type,
                processName,
                pid,
                task,
                DesktopProcessFailure.compactReason(reason));
    }

    private TaskContext findTask(final String processName) {
        if (mDisplayId == Display.INVALID_DISPLAY) {
            return null;
        }
        final String packageName = packageFromProcessName(processName);
        if (packageName.isEmpty()) {
            return null;
        }
        TaskContext rootMatch = null;
        for (final TaskContext task : mTasks) {
            if (packageName.equals(task.topPackage)) {
                return task;
            }
            if (rootMatch == null
                    && packageName.equals(task.packageName)) {
                rootMatch = task;
            }
        }
        return rootMatch;
    }

    private void report(final Failure failure) {
        if (failure == null || mListener == null) {
            return;
        }
        final String operation = failure.type == DesktopProcessFailure.ANR
                ? "anr" : "crash";
        try {
            DesktopAutomationEventJournal.record(
                    "process",
                    operation,
                    false,
                    failure.processName,
                    new org.json.JSONObject()
                            .put("process", failure.processName)
                            .put("pid", failure.pid)
                            .put("taskId", failure.task.taskId)
                            .put("displayId", failure.task.displayId)
                            .put("windowingMode",
                                    failure.task.windowingMode)
                            .put("topActivity", failure.task.topActivity)
                            .put("reason", failure.reason));
        } catch (org.json.JSONException ignored) {
            DesktopAutomationEventJournal.record(
                    "process", operation, false, failure.processName);
        }
        mListener.onDesktopProcessFailure(
                failure.type,
                failure.processName,
                failure.pid,
                failure.task.taskId,
                failure.task.displayId,
                failure.task.windowingMode,
                failure.task.topActivity,
                failure.reason);
    }

    private static String packageFromProcessName(final String processName) {
        if (processName == null) {
            return "";
        }
        final int separator = processName.indexOf(':');
        return separator < 0 ? processName
                : processName.substring(0, separator);
    }

    private static final class TaskContext {
        final int taskId;
        final int displayId;
        final int windowingMode;
        final String packageName;
        final String topPackage;
        final String topActivity;

        TaskContext(
                final ShellTaskStateMonitor.TaskWindowState task,
                final int observedDisplayId) {
            taskId = task.taskId;
            displayId = observedDisplayId;
            windowingMode = task.windowingMode;
            packageName = task.packageName;
            topPackage = task.topPackage;
            final ComponentName component = task.topComponent;
            topActivity = component == null
                    ? "" : component.flattenToShortString();
        }
    }

    private static final class PendingAnr {
        final TaskContext task;
        final String annotation;

        PendingAnr(final TaskContext capturedTask, final String detail) {
            task = capturedTask;
            annotation = detail;
        }
    }

    private static final class Failure {
        final int type;
        final String processName;
        final int pid;
        final TaskContext task;
        final String reason;

        Failure(
                final int failureType,
                final String failedProcessName,
                final int failedPid,
                final TaskContext failedTask,
                final String failureReason) {
            type = failureType;
            processName = failedProcessName;
            pid = failedPid;
            task = failedTask;
            reason = failureReason;
        }
    }

    private static final class ProcessIdentity {
        final String processName;
        final int pid;

        ProcessIdentity(final String observedProcessName, final int observedPid) {
            processName = observedProcessName == null
                    ? "" : observedProcessName;
            pid = observedPid;
        }

        @Override
        public boolean equals(final Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ProcessIdentity)) {
                return false;
            }
            final ProcessIdentity identity = (ProcessIdentity) other;
            return pid == identity.pid
                    && processName.equals(identity.processName);
        }

        @Override
        public int hashCode() {
            return 31 * processName.hashCode() + pid;
        }
    }
}

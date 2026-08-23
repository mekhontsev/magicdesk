package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Intent;
import android.view.Display;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Correlates process failures with desktop tasks and the phone HOME process. */
final class ShellProcessFailureTracker implements
        ShellActivityStartController.ProcessFailureListener,
        ShellActivityStartController.Observer {
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

        void onPhoneLauncherEvent(
                int type, String processName, int pid, String reason);
    }

    private static final int ACTIVITY_TYPE_STANDARD = 1;

    private final Listener mListener;
    private final PhoneHomeComponents mPhoneHome;
    private final List<TaskContext> mTasks = new ArrayList<>();
    private final Map<ProcessIdentity, PendingAnr> mPendingAnrs =
            new HashMap<>();

    private int mDisplayId = Display.INVALID_DISPLAY;

    ShellProcessFailureTracker(
            final Listener listener,
            final PhoneHomeComponents phoneHome) {
        if (phoneHome == null) {
            throw new IllegalArgumentException("missing phone HOME components");
        }
        mListener = listener;
        mPhoneHome = phoneHome;
    }

    synchronized void configure(final int displayId) {
        if (mDisplayId == displayId) {
            return;
        }
        mDisplayId = displayId;
        mTasks.clear();
        mPendingAnrs.clear();
    }

    @Override
    public void onActivityStarting(
            final Intent intent,
            final String packageName,
            final boolean allowed) {
        final ComponentName component = intent == null
                ? null : intent.getComponent();
        synchronized (this) {
            if (mDisplayId == Display.INVALID_DISPLAY
                    || !mPhoneHome.isPrimaryHomeStart(
                            intent, packageName)) {
                return;
            }
        }
        reportPhoneLauncherEvent(
                allowed
                        ? PhoneLauncherEvent.HOME_START_ALLOWED
                        : PhoneLauncherEvent.HOME_START_BLOCKED,
                component == null
                        ? packageName : component.flattenToShortString(),
                -1,
                "");
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
        final boolean phoneLauncher;
        synchronized (this) {
            final ProcessIdentity identity =
                    new ProcessIdentity(processName, pid);
            mPendingAnrs.remove(identity);
            phoneLauncher = mDisplayId != Display.INVALID_DISPLAY
                    && mPhoneHome.isPrimaryProcess(processName);
            failure = createFailure(
                    DesktopProcessFailure.CRASH,
                    processName,
                    pid,
                    shortMessage,
                    null);
        }
        if (phoneLauncher) {
            reportPhoneLauncherEvent(
                    PhoneLauncherEvent.CRASH,
                    processName,
                    pid,
                    shortMessage);
        }
        report(failure);
    }

    @Override
    public synchronized void onProcessEarlyNotResponding(
            final String processName,
            final int pid,
            final String annotation) {
        final TaskContext task = findTask(processName);
        final boolean phoneLauncher = mDisplayId != Display.INVALID_DISPLAY
                && mPhoneHome.isPrimaryProcess(processName);
        if (task == null && !phoneLauncher) {
            return;
        }
        final ProcessIdentity identity = new ProcessIdentity(processName, pid);
        mPendingAnrs.put(
                identity,
                new PendingAnr(task, phoneLauncher, annotation));
    }

    @Override
    public void onProcessNotResponding(
            final String processName,
            final int pid) {
        final Failure failure;
        final boolean phoneLauncher;
        final String launcherReason;
        synchronized (this) {
            final ProcessIdentity identity =
                    new ProcessIdentity(processName, pid);
            final PendingAnr pending = mPendingAnrs.remove(identity);
            phoneLauncher = mDisplayId != Display.INVALID_DISPLAY
                    && ((pending != null && pending.phoneLauncher)
                            || mPhoneHome.isPrimaryProcess(processName));
            launcherReason = pending == null || !pending.phoneLauncher
                    ? "" : pending.annotation;
            failure = createFailure(
                    DesktopProcessFailure.ANR,
                    processName,
                    pid,
                    pending == null ? "" : pending.annotation,
                    pending == null ? null : pending.task);
        }
        if (phoneLauncher) {
            reportPhoneLauncherEvent(
                    PhoneLauncherEvent.ANR,
                    processName,
                    pid,
                    launcherReason);
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

    private void reportPhoneLauncherEvent(
            final int type,
            final String processName,
            final int pid,
            final String reason) {
        if (mListener == null) {
            return;
        }
        mListener.onPhoneLauncherEvent(
                type,
                processName == null ? "" : processName,
                pid,
                DesktopProcessFailure.compactReason(reason));
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
        final boolean phoneLauncher;
        final String annotation;

        PendingAnr(
                final TaskContext capturedTask,
                final boolean launcher,
                final String detail) {
            task = capturedTask;
            phoneLauncher = launcher;
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

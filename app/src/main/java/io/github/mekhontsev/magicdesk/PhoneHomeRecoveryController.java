package io.github.mekhontsev.magicdesk;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.Display;

import java.io.IOException;
import java.util.List;

final class PhoneHomeRecoveryController {
    private static final String TAG = "MagicDeskPhoneHome";
    private static final String AM = "/system/bin/am";
    private static final String SECONDARY_PHONE_HOME =
            "com.zte.mifavor.launcher/"
                    + "com.android.launcher3.secondarydisplay.SecondaryDisplayLauncher";
    private static final String PRIMARY_PHONE_HOME =
            "com.zte.mifavor.launcher/"
                    + "com.android.launcher3.uioverrides.QuickstepLauncher";
    private static final String MAGICDESK_DESKTOP_ACTIVITY =
            "io.github.mekhontsev.magicdesk/"
                    + "io.github.mekhontsev.magicdesk.DesktopActivity";

    private PhoneHomeRecoveryController() {
    }

    static void restoreAfterConsoleExit(final Context context) {
        if (context == null
                || ShellAccess.isReady()) {
            return;
        }
        try {
            final Intent intent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setComponent(ComponentName.unflattenFromString(
                            PRIMARY_PHONE_HOME))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            final ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
            context.startActivity(intent, options.toBundle());
            Log.i(TAG, "requested primary phone Home after Console exit");
        } catch (RuntimeException error) {
            Log.w(TAG, "public phone Home restore failed", error);
            CompatibilityDiagnostics.record(
                    "NUBIA-HOME-002",
                    "Could not restore the phone launcher after Console Mode",
                    error.getMessage());
        }
    }

    static void restoreIfNeeded(
            final boolean includeStrandedDesktop,
            final int removedDisplayId,
            final boolean localDesktopActive,
            final boolean allowUnsettledRemoval,
            final ResultCallback callback) {
        if (!ShellAccess.isReady()) {
            complete(callback, true);
            return;
        }
        final boolean ensureVisiblePhoneTask =
                includeStrandedDesktop || removedDisplayId > 0;
        if (!ensureVisiblePhoneTask) {
            loadAndRestoreSnapshot(
                    includeStrandedDesktop,
                    localDesktopActive,
                    false,
                    false,
                    callback);
            return;
        }
        final PhoneDesktopTaskRecovery.Callback recoveryComplete = result -> {
            if (result.pending) {
                Log.d(TAG, result.message);
                complete(callback, false);
                return;
            }
            if (!result.success) {
                Log.w(TAG, "phone desktop cleanup failed before Home: "
                        + result.message);
                CompatibilityDiagnostics.record(
                        "NUBIA-HOME-004",
                        "Could not clean phone desktop tasks before"
                                + " restoring the launcher",
                        result.message);
            }
            loadAndRestoreSnapshot(
                    includeStrandedDesktop,
                    localDesktopActive,
                    true,
                    !result.success,
                    callback);
        };
        if (removedDisplayId > 0) {
            if (allowUnsettledRemoval) {
                PhoneDesktopTaskRecovery.recoverRemovedDisplayAfterTimeout(
                        removedDisplayId, recoveryComplete);
            } else {
                PhoneDesktopTaskRecovery.recoverRemovedDisplay(
                        removedDisplayId, recoveryComplete);
            }
        } else {
            PhoneDesktopTaskRecovery.recover(recoveryComplete);
        }
    }

    static String primaryHomeCommand() {
        return AM + " start --display " + Display.DEFAULT_DISPLAY
                + " --activity-clear-top"
                + " --activity-single-top"
                + " -a android.intent.action.MAIN"
                + " -c android.intent.category.HOME"
                + " -n " + PRIMARY_PHONE_HOME;
    }

    static boolean shouldRestoreStrandedDesktop(
            final boolean consoleModeActive,
            final boolean consoleExitRecoveryPending) {
        return !consoleModeActive && consoleExitRecoveryPending;
    }

    static boolean needsPrimaryHomeRestore(
            final List<TaskRepository.TaskEntry> tasks,
            final boolean includeStrandedDesktop) {
        if (tasks == null) {
            return false;
        }
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task == null || task.displayId != Display.DEFAULT_DISPLAY
                    || !task.visible) {
                continue;
            }
            final boolean secondaryHome = task.home
                    && SECONDARY_PHONE_HOME.equals(task.topActivityName);
            final boolean strandedDesktop =
                    includeStrandedDesktop
                            && MAGICDESK_DESKTOP_ACTIVITY.equals(
                                    task.topActivityName);
            if (secondaryHome || strandedDesktop) {
                return true;
            }
        }
        return false;
    }

    static boolean isSecondaryPhoneHomeTask(
            final TaskRepository.TaskEntry task) {
        return task != null
                && task.displayId == Display.DEFAULT_DISPLAY
                && task.home
                && (SECONDARY_PHONE_HOME.equals(task.componentName)
                        || SECONDARY_PHONE_HOME.equals(task.topActivityName));
    }

    static boolean isStrandedDesktopTask(
            final TaskRepository.TaskEntry task,
            final boolean localDesktopActive) {
        return !localDesktopActive
                && task != null
                && task.displayId == Display.DEFAULT_DISPLAY
                && (MAGICDESK_DESKTOP_ACTIVITY.equals(task.componentName)
                        || MAGICDESK_DESKTOP_ACTIVITY.equals(
                                task.topActivityName));
    }

    private static void loadAndRestoreSnapshot(
            final boolean includeStrandedDesktop,
            final boolean localDesktopActive,
            final boolean ensureVisiblePhoneTask,
            final boolean forcePrimaryHome,
            final ResultCallback callback) {
        TaskRepository.load(Display.DEFAULT_DISPLAY, snapshot ->
                restoreSnapshot(
                        snapshot,
                        includeStrandedDesktop,
                        localDesktopActive,
                        ensureVisiblePhoneTask,
                        forcePrimaryHome,
                        callback));
    }

    private static void restoreSnapshot(
            final TaskRepository.Snapshot snapshot,
            final boolean includeStrandedDesktop,
            final boolean localDesktopActive,
            final boolean ensureVisiblePhoneTask,
            final boolean forcePrimaryHome,
            final ResultCallback callback) {
        if (!snapshot.available) {
            if (forcePrimaryHome) {
                restorePrimaryHome(callback);
            } else {
                complete(callback, false);
            }
            return;
        }
        removeSecondaryPhoneHomeTasks(snapshot.tasks);
        removeStrandedDesktopTasks(snapshot.tasks, localDesktopActive);
        final boolean needsPrimaryHome = needsPrimaryHomeRestore(
                snapshot.tasks, includeStrandedDesktop);
        if (!forcePrimaryHome
                && !needsPrimaryHome
                && (!ensureVisiblePhoneTask
                        || hasVisiblePhoneTaskAfterCleanup(
                                snapshot.tasks,
                                localDesktopActive))) {
            complete(callback, true);
            return;
        }
        restorePrimaryHome(callback);
    }

    private static void removeSecondaryPhoneHomeTasks(
            final List<TaskRepository.TaskEntry> tasks) {
        if (tasks == null) {
            return;
        }
        for (final TaskRepository.TaskEntry task : tasks) {
            if (!isSecondaryPhoneHomeTask(task)) {
                continue;
            }
            try {
                final String output = ShellAccess.run(AppProcessCommand.run(
                        "io.github.mekhontsev.magicdesk.TaskControlCommand",
                        "remove " + task.taskId));
                Log.i(TAG, "removed secondary phone Home task="
                        + task.taskId + ": " + output.trim());
            } catch (IOException error) {
                Log.w(TAG, "failed to remove secondary phone Home task="
                        + task.taskId, error);
                CompatibilityDiagnostics.record(
                        "NUBIA-HOME-005",
                        "Could not remove a secondary launcher task"
                                + " from the phone screen",
                        error.getMessage());
            }
        }
    }

    private static void removeStrandedDesktopTasks(
            final List<TaskRepository.TaskEntry> tasks,
            final boolean localDesktopActive) {
        if (tasks == null || localDesktopActive) {
            return;
        }
        for (final TaskRepository.TaskEntry task : tasks) {
            if (!isStrandedDesktopTask(task, localDesktopActive)) {
                continue;
            }
            try {
                final String output = ShellAccess.run(AppProcessCommand.run(
                        "io.github.mekhontsev.magicdesk.TaskControlCommand",
                        "remove " + task.taskId));
                Log.i(TAG, "removed stranded desktop task="
                        + task.taskId + ": " + output.trim());
            } catch (IOException error) {
                Log.w(TAG, "failed to remove stranded desktop task="
                        + task.taskId, error);
                CompatibilityDiagnostics.record(
                        "NUBIA-HOME-006",
                        "Could not remove a desktop task stranded"
                                + " on the phone screen",
                        error.getMessage());
            }
        }
    }

    private static void restorePrimaryHome(
            final ResultCallback callback) {
        try {
            final String output =
                    ShellAccess.run(primaryHomeCommand()).trim();
            if (output.startsWith("Error:")
                    || output.contains(
                            "Exception occurred while executing")) {
                throw new IOException(output);
            }
            Log.i(TAG, "restored primary phone Home: "
                    + output.replace('\n', ' '));
            complete(callback, true);
        } catch (IOException error) {
            Log.w(TAG, "failed to restore primary phone Home", error);
            CompatibilityDiagnostics.record(
                    "NUBIA-HOME-001",
                    "Could not restore the phone launcher after Console Mode",
                    error.getMessage());
            complete(callback, false);
        }
    }

    static boolean hasVisiblePhoneTaskAfterCleanup(
            final List<TaskRepository.TaskEntry> tasks,
            final boolean localDesktopActive) {
        if (tasks != null) {
            for (final TaskRepository.TaskEntry task : tasks) {
                if (task != null
                        && task.displayId == Display.DEFAULT_DISPLAY
                        && task.visible
                        && !isSecondaryPhoneHomeTask(task)
                        && !isStrandedDesktopTask(
                                task, localDesktopActive)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void complete(
            final ResultCallback callback,
            final boolean settled) {
        if (callback != null) {
            callback.onComplete(settled);
        }
    }

    interface ResultCallback {
        void onComplete(boolean settled);
    }
}

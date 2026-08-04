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
            final ResultCallback callback) {
        if (!ShellAccess.isReady()) {
            complete(callback, true);
            return;
        }
        TaskRepository.load(Display.DEFAULT_DISPLAY, snapshot ->
                restoreSnapshot(
                        snapshot, includeStrandedDesktop, callback));
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

    private static void restoreSnapshot(
            final TaskRepository.Snapshot snapshot,
            final boolean includeStrandedDesktop,
            final ResultCallback callback) {
        if (!snapshot.available) {
            complete(callback, false);
            return;
        }
        if (!needsPrimaryHomeRestore(
                snapshot.tasks, includeStrandedDesktop)) {
            complete(callback,
                    !includeStrandedDesktop
                            || hasVisiblePhoneTask(snapshot.tasks));
            return;
        }
        if (includeStrandedDesktop) {
            TaskRepository.recoverPhoneDesktopTasks(result -> {
                if (!result.success) {
                    Log.w(TAG, "phone desktop cleanup failed before Home: "
                            + result.message);
                    CompatibilityDiagnostics.record(
                            "NUBIA-HOME-004",
                            "Could not clean phone desktop tasks before"
                                    + " restoring the launcher",
                            result.message);
                    complete(callback, false);
                    return;
                }
                restorePrimaryHome(callback);
            });
            return;
        }
        restorePrimaryHome(callback);
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

    private static boolean hasVisiblePhoneTask(
            final List<TaskRepository.TaskEntry> tasks) {
        if (tasks != null) {
            for (final TaskRepository.TaskEntry task : tasks) {
                if (task != null
                        && task.displayId == Display.DEFAULT_DISPLAY
                        && task.visible) {
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

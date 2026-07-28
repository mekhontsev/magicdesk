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
    private static final String MAGICDESK_MAIN_ACTIVITY =
            "io.github.mekhontsev.magicdesk/"
                    + "io.github.mekhontsev.magicdesk.MainActivity";

    private PhoneHomeRecoveryController() {
    }

    static void restoreAfterConsoleExit(final Context context) {
        if (context == null
                || RuntimeAccess.has(RuntimeAccess.Capability.EXACT_TASKS)
                || !RuntimeAccess.has(
                        RuntimeAccess.Capability.PUBLIC_APP_LAUNCH)) {
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
            final boolean includeMigratedMagicDesk,
            final ResultCallback callback) {
        if (!RuntimeAccess.has(RuntimeAccess.Capability.EXACT_TASKS)) {
            complete(callback, true);
            return;
        }
        TaskRepository.load(Display.DEFAULT_DISPLAY, snapshot ->
                restoreSnapshot(
                        snapshot, includeMigratedMagicDesk, callback));
    }

    static String primaryHomeCommand() {
        return AM + " start --display " + Display.DEFAULT_DISPLAY
                + " --activity-clear-top"
                + " --activity-single-top"
                + " -a android.intent.action.MAIN"
                + " -c android.intent.category.HOME"
                + " -n " + PRIMARY_PHONE_HOME;
    }

    static boolean needsPrimaryHomeRestore(
            final List<TaskRepository.TaskEntry> tasks,
            final boolean includeMigratedMagicDesk) {
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
            final boolean migratedMagicDesk =
                    includeMigratedMagicDesk
                            && MAGICDESK_MAIN_ACTIVITY.equals(
                                    task.topActivityName);
            if (secondaryHome || migratedMagicDesk) {
                return true;
            }
        }
        return false;
    }

    private static void restoreSnapshot(
            final TaskRepository.Snapshot snapshot,
            final boolean includeMigratedMagicDesk,
            final ResultCallback callback) {
        if (!snapshot.rootAvailable) {
            complete(callback, false);
            return;
        }
        if (!needsPrimaryHomeRestore(
                snapshot.tasks, includeMigratedMagicDesk)) {
            complete(callback,
                    !includeMigratedMagicDesk
                            || hasVisiblePhoneTask(snapshot.tasks));
            return;
        }
        try {
            final String output =
                    PrivilegedCommandRunner.run(primaryHomeCommand()).trim();
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

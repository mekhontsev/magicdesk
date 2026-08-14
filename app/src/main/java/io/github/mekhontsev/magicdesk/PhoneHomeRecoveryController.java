package io.github.mekhontsev.magicdesk;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.Display;

import java.io.IOException;
import java.util.List;

final class PhoneHomeRecoveryController {
    private static final String TAG = "MagicDeskPhoneHome";
    private static final String AM = "/system/bin/am";
    private static final String MAGICDESK_DESKTOP_ACTIVITY =
            "io.github.mekhontsev.magicdesk/"
                    + "io.github.mekhontsev.magicdesk.DesktopActivity";
    private static final String SYSTEM_DESKTOP_WALLPAPER_ACTIVITY =
            "com.android.systemui/"
                    + "com.android.wm.shell.desktopmode.DesktopWallpaperActivity";

    private PhoneHomeRecoveryController() {
    }

    static void restoreAfterConsoleExit(final Context context) {
        if (context == null
                || ShellAccess.isReady()) {
            return;
        }
        try {
            final PhoneHomeComponents home =
                    PhoneHomeComponents.resolve(context);
            final Intent intent = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (home.hasPrimary()) {
                intent.setComponent(home.primaryComponentName());
            }
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
        final PhoneHomeComponents home = PhoneHomeComponents.resolve(
                MagicDeskApplication.applicationContext());
        if (!ensureVisiblePhoneTask) {
            loadAndRestoreSnapshot(
                    home,
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
                    home,
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
        return primaryHomeCommand(PhoneHomeComponents.resolve(
                MagicDeskApplication.applicationContext()));
    }

    static String primaryHomeCommand(final PhoneHomeComponents home) {
        return AM + " start --display " + Display.DEFAULT_DISPLAY
                + " --activity-clear-top"
                + " --activity-single-top"
                + " -a android.intent.action.MAIN"
                + " -c android.intent.category.HOME"
                + (home != null && home.hasPrimary()
                        ? " -n " + home.primaryComponent() : "");
    }

    static boolean shouldRestoreStrandedDesktop(
            final boolean consoleModeActive,
            final boolean consoleExitRecoveryPending) {
        return !consoleModeActive && consoleExitRecoveryPending;
    }

    static boolean needsPrimaryHomeRestore(
            final List<TaskRepository.TaskEntry> tasks,
            final boolean includeStrandedDesktop,
            final PhoneHomeComponents home) {
        if (tasks == null) {
            return false;
        }
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task == null || task.displayId != Display.DEFAULT_DISPLAY
                    || !task.visible) {
                continue;
            }
            final boolean secondaryHome = task.home
                    && home != null
                    && home.hasSecondaryHomeOnTop(task);
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

    static boolean isRemovableSecondaryPhoneHomeTask(
            final TaskRepository.TaskEntry task,
            final PhoneHomeComponents home) {
        return task != null
                && task.displayId == Display.DEFAULT_DISPLAY
                && task.home
                && home != null
                && home.isDedicatedSecondaryTask(task);
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
            final PhoneHomeComponents home,
            final boolean includeStrandedDesktop,
            final boolean localDesktopActive,
            final boolean ensureVisiblePhoneTask,
            final boolean forcePrimaryHome,
            final ResultCallback callback) {
        TaskRepository.load(Display.DEFAULT_DISPLAY, snapshot ->
                restoreSnapshot(
                        snapshot,
                        home,
                        includeStrandedDesktop,
                        localDesktopActive,
                        ensureVisiblePhoneTask,
                        forcePrimaryHome,
                        callback));
    }

    private static void restoreSnapshot(
            final TaskRepository.Snapshot snapshot,
            final PhoneHomeComponents home,
            final boolean includeStrandedDesktop,
            final boolean localDesktopActive,
            final boolean ensureVisiblePhoneTask,
            final boolean forcePrimaryHome,
            final ResultCallback callback) {
        if (!snapshot.available) {
            if (forcePrimaryHome) {
                restorePrimaryHome(home, callback);
            } else {
                complete(callback, false);
            }
            return;
        }
        final boolean localDesktopStillActive = localDesktopActive
                || DesktopRuntimeBridge.isLocalDesktopActiveOrStarting();
        final boolean secondaryHomeCleanupSucceeded =
                removeSecondaryPhoneHomeTasks(snapshot.tasks, home);
        final boolean desktopCleanupSucceeded =
                removeStrandedDesktopTasks(
                        snapshot.tasks, localDesktopStillActive);
        final boolean removeSystemDesktopWallpaper =
                ensureVisiblePhoneTask
                        && !forcePrimaryHome
                        && !localDesktopStillActive;
        final boolean wallpaperCleanupSucceeded =
                removeStrandedSystemDesktopWallpaperTasks(
                        snapshot.tasks, removeSystemDesktopWallpaper);
        final boolean cleanupSucceeded = allTaskCleanupSucceeded(
                secondaryHomeCleanupSucceeded,
                desktopCleanupSucceeded,
                wallpaperCleanupSucceeded);
        final boolean needsPrimaryHome = needsPrimaryHomeRestore(
                snapshot.tasks,
                includeStrandedDesktop && !localDesktopStillActive,
                home);
        if (!forcePrimaryHome
                && !needsPrimaryHome
                && (!ensureVisiblePhoneTask
                        || hasVisiblePhoneTaskAfterCleanup(
                                snapshot.tasks,
                                localDesktopStillActive,
                                home,
                                removeSystemDesktopWallpaper))) {
            complete(callback, cleanupSucceeded);
            return;
        }
        restorePrimaryHome(home, restored ->
                complete(callback, cleanupSucceeded && restored));
    }

    static boolean allTaskCleanupSucceeded(
            final boolean secondaryHome,
            final boolean desktop,
            final boolean wallpaper) {
        return secondaryHome && desktop && wallpaper;
    }

    private static boolean removeSecondaryPhoneHomeTasks(
            final List<TaskRepository.TaskEntry> tasks,
            final PhoneHomeComponents home) {
        if (tasks == null) {
            return true;
        }
        boolean succeeded = true;
        for (final TaskRepository.TaskEntry task : tasks) {
            if (!isRemovableSecondaryPhoneHomeTask(task, home)) {
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
                succeeded = false;
            }
        }
        return succeeded;
    }

    private static boolean removeStrandedDesktopTasks(
            final List<TaskRepository.TaskEntry> tasks,
            final boolean localDesktopActive) {
        if (tasks == null || localDesktopActive) {
            return true;
        }
        boolean succeeded = true;
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
                succeeded = false;
            }
        }
        return succeeded;
    }

    private static boolean removeStrandedSystemDesktopWallpaperTasks(
            final List<TaskRepository.TaskEntry> tasks,
            final boolean cleanupEnabled) {
        if (tasks == null || !cleanupEnabled) {
            return true;
        }
        boolean succeeded = true;
        for (final TaskRepository.TaskEntry task : tasks) {
            if (!isStrandedSystemDesktopWallpaperTask(
                    task, cleanupEnabled)) {
                continue;
            }
            try {
                final String output = ShellAccess.run(AppProcessCommand.run(
                        "io.github.mekhontsev.magicdesk.TaskControlCommand",
                        "remove " + task.taskId));
                Log.i(TAG, "removed stranded SystemUI desktop wallpaper task="
                        + task.taskId + ": " + output.trim());
            } catch (IOException error) {
                Log.w(TAG, "failed to remove stranded SystemUI desktop"
                        + " wallpaper task=" + task.taskId, error);
                CompatibilityDiagnostics.record(
                        "NUBIA-HOME-007",
                        "Could not remove a SystemUI desktop wallpaper task"
                                + " stranded on the phone screen",
                        error.getMessage());
                succeeded = false;
            }
        }
        return succeeded;
    }

    private static void restorePrimaryHome(
            final PhoneHomeComponents home,
            final ResultCallback callback) {
        try {
            final String output =
                    ShellAccess.run(primaryHomeCommand(home)).trim();
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
            final boolean localDesktopActive,
            final PhoneHomeComponents home,
            final boolean removeSystemDesktopWallpaper) {
        if (tasks != null) {
            for (final TaskRepository.TaskEntry task : tasks) {
                if (task != null
                        && task.displayId == Display.DEFAULT_DISPLAY
                        && task.visible
                        && !isRemovableSecondaryPhoneHomeTask(task, home)
                        && !isStrandedDesktopTask(
                                task, localDesktopActive)
                        && !isStrandedSystemDesktopWallpaperTask(
                                task, removeSystemDesktopWallpaper)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isStrandedSystemDesktopWallpaperTask(
            final TaskRepository.TaskEntry task,
            final boolean cleanupEnabled) {
        return cleanupEnabled
                && task != null
                && task.displayId == Display.DEFAULT_DISPLAY
                && (SYSTEM_DESKTOP_WALLPAPER_ACTIVITY.equals(
                            task.componentName)
                        || SYSTEM_DESKTOP_WALLPAPER_ACTIVITY.equals(
                                task.topActivityName));
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

package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.util.Log;

import java.io.IOException;

final class MagicDeskSessionController {
    private static final String TAG = "MagicDesk";
    private static final String AM = "/system/bin/am";
    private static final String KEYBOARD_WATCHER_SERVICE =
            "io.github.mekhontsev.magicdesk/.MagicDeskRuntimeService";

    private final MagicDeskSessionHost mHost;
    private final Activity mActivity;
    private boolean mExitInProgress;

    MagicDeskSessionController(final MagicDeskSessionHost host) {
        mHost = host;
        mActivity = host.sessionActivity();
    }

    void exit() {
        if (mExitInProgress) {
            return;
        }
        mExitInProgress = true;
        Log.i(TAG, "full MagicDesk exit requested");
        mHost.showSessionStatus(
                mActivity.getString(R.string.status_exiting));
        if (!RuntimeAccess.has(
                RuntimeAccess.Capability.CONSOLE_CONTROL)) {
            finishUnprivilegedExit();
            return;
        }
        ConsoleModeSwitcher.setPhoneScreenOff(
                false,
                success -> {
                    if (!success) {
                        abort(
                                "EXIT-001",
                                "Could not restore the phone screen",
                                null);
                        return;
                    }
                    ConsoleModeSwitcher.returnConsoleTasksToPhone(
                            tasksReturned -> {
                                if (!tasksReturned) {
                                    abort(
                                            "EXIT-002",
                                            "Could not return Console tasks"
                                                    + " to the phone",
                                            null);
                                    return;
                                }
                                ConsoleModeSwitcher.switchToMirror(
                                        mirrorActive -> {
                                            if (!mirrorActive) {
                                                abort(
                                                        "EXIT-003",
                                                        "Could not restore"
                                                                + " mirror mode",
                                                        null);
                                                return;
                                            }
                                            finishPrivilegedExit();
                                        });
                            });
                });
    }

    private void finishUnprivilegedExit() {
        DeviceSetupManager.revokeRuntimeAuthorization(mActivity);
        MagicDeskRuntimeService.stop(mActivity);
        mHost.releaseSessionUi();
        mExitInProgress = false;
        final ActivityManager manager = (ActivityManager)
                mActivity.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            for (final ActivityManager.AppTask task : manager.getAppTasks()) {
                task.finishAndRemoveTask();
            }
        } else {
            mActivity.finishAndRemoveTask();
        }
    }

    private void finishPrivilegedExit() {
        RootKeyboardShortcutWatcher.stop();
        MagicDeskRuntimeService.stop(mActivity);
        runRootCommandBestEffort(
                AM + " stop-service -n " + KEYBOARD_WATCHER_SERVICE);
        try {
            runRootCommand(
                    AM + " start --display 0"
                            + " -a android.intent.action.MAIN"
                            + " -c android.intent.category.HOME");
            runRootCommand(
                    AM + " force-stop --user 0 "
                            + mActivity.getPackageName());
        } catch (IOException e) {
            Log.w(TAG, "full MagicDesk exit failed", e);
            abort(
                    "EXIT-004",
                    mActivity.getString(
                            R.string.status_root_failed,
                            e.getMessage()),
                    e);
        }
    }

    private void abort(
            final String code,
            final String message,
            final Throwable error) {
        Log.w(TAG, "MagicDesk exit aborted: " + message, error);
        mActivity.runOnUiThread(() -> {
            mExitInProgress = false;
            mHost.showSessionError(code, message, error);
        });
    }

    private static String runRootCommand(final String command)
            throws IOException {
        return PrivilegedCommandRunner.run(command);
    }

    private static void runRootCommandBestEffort(
            final String command) {
        try {
            runRootCommand(command);
        } catch (IOException e) {
            Log.w(
                    TAG,
                    "best-effort root command failed: " + command,
                    e);
        }
    }
}

package io.github.mekhontsev.magicdesk;

import android.app.Activity;
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
        if (ShellAccess.isReady()) {
            RedmagicHardwareController.restoreChangedState(
                    success -> {
                        if (!success) {
                            abort(
                                    "REDMAGIC-HW-RESTORE-001",
                                    mActivity.getString(
                                            R.string.hardware_restore_failed),
                                    null);
                            return;
                        }
                        continueExit();
                    });
            return;
        }
        continueExit();
    }

    private void continueExit() {
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
                    continueConsoleExit();
                });
    }

    private void continueConsoleExit() {
        ConsoleModeSwitcher.returnConsoleTasksToPhone(
                tasksReturned -> {
                    if (!tasksReturned) {
                        abort(
                                "EXIT-002",
                                "Could not return Console tasks to the phone",
                                null);
                        return;
                    }
                    cleanupPhoneTasksBeforeExit(
                            () -> ConsoleModeSwitcher.switchToMirror(
                                    mirrorActive -> {
                                        if (!mirrorActive) {
                                            abort(
                                                    "EXIT-003",
                                                    "Could not restore mirror mode",
                                                    null);
                                            return;
                                        }
                                        finishExit();
                                    }));
                });
    }

    private void finishExit() {
        KeyboardShortcutWatcher.stop();
        MagicDeskRuntimeService.stop(mActivity);
        runCommandBestEffort(
                AM + " stop-service -n " + KEYBOARD_WATCHER_SERVICE);
        try {
            runCommand(
                    AM + " start --display 0"
                            + " -a android.intent.action.MAIN"
                            + " -c android.intent.category.HOME");
            runCommand(
                    AM + " force-stop --user 0 "
                            + mActivity.getPackageName());
        } catch (IOException e) {
            Log.w(TAG, "full MagicDesk exit failed", e);
            abort(
                    "EXIT-004",
                    mActivity.getString(
                            R.string.status_exit_failed,
                            e.getMessage()),
                    e);
        }
    }

    private void cleanupPhoneTasksBeforeExit(
            final Runnable continuation) {
        if (!ShellAccess.isReady()) {
            continuation.run();
            return;
        }
        TaskRepository.recoverPhoneDesktopTasks(result -> {
            if (!result.success) {
                abort(
                        "EXIT-005",
                        "Could not recover phone desktop tasks: "
                                + result.message,
                        null);
                return;
            }
            LocalDesktopSessionState.clearCleanupPending(mActivity);
            continuation.run();
        });
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

    private static String runCommand(final String command)
            throws IOException {
        return ShellAccess.run(command);
    }

    private static void runCommandBestEffort(
            final String command) {
        try {
            runCommand(command);
        } catch (IOException e) {
            Log.w(
                    TAG,
                    "best-effort privileged command failed: " + command,
                    e);
        }
    }
}

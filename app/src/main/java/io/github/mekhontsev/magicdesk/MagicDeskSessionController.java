package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.util.Log;
import android.view.Display;

final class MagicDeskSessionController {
    private static final String TAG = "MagicDesk";

    private final MagicDeskSessionHost mHost;
    private final Activity mActivity;
    private boolean mOperationInProgress;

    MagicDeskSessionController(final MagicDeskSessionHost host) {
        mHost = host;
        mActivity = host.sessionActivity();
    }

    void exit() {
        if (mOperationInProgress) {
            return;
        }
        mOperationInProgress = true;
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

    void closeDesktop() {
        if (mOperationInProgress) {
            return;
        }
        mOperationInProgress = true;
        Log.i(TAG, "desktop close requested");
        mHost.showSessionStatus(
                mActivity.getString(R.string.status_desktop_closing));
        final Display display = mActivity.getDisplay();
        if (display == null
                || display.getDisplayId() == Display.DEFAULT_DISPLAY) {
            mActivity.runOnUiThread(mActivity::finishAndRemoveTask);
            return;
        }
        ConsoleModeSwitcher.switchToMirrorWithControlPanel(success -> {
            if (!success) {
                abort(
                        "NUBIA-CONSOLE-001",
                        mActivity.getString(R.string.status_mirror_failed),
                        null);
            }
        });
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
        ShellAccess.disconnect();
        mActivity.runOnUiThread(this::openHomeAndFinishTasks);
    }

    private void openHomeAndFinishTasks() {
        try {
            final Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mActivity.startActivity(home);

            final ActivityManager activityManager =
                    mActivity.getSystemService(ActivityManager.class);
            if (activityManager == null) {
                mActivity.finishAndRemoveTask();
                return;
            }
            for (final ActivityManager.AppTask task
                    : activityManager.getAppTasks()) {
                task.finishAndRemoveTask();
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "full MagicDesk exit failed", error);
            abort(
                    "EXIT-004",
                    mActivity.getString(
                            R.string.status_exit_failed,
                            error.getMessage()),
                    error);
        }
    }

    private void cleanupPhoneTasksBeforeExit(
            final Runnable continuation) {
        if (!ShellAccess.isReady()) {
            continuation.run();
            return;
        }
        mActivity.runOnUiThread(() -> {
            final int anchorTaskId =
                    FreeformLaunchAnchorActivity.releaseForCleanup();
            recoverPhoneTasksAfterAnchorRelease(
                    anchorTaskId, continuation);
        });
    }

    private void recoverPhoneTasksAfterAnchorRelease(
            final int anchorTaskId,
            final Runnable continuation) {
        PhoneDesktopTaskRecovery.recover(anchorTaskId, result -> {
            if (!result.success) {
                final String detail =
                        "Could not recover phone desktop tasks: "
                                + result.message;
                Log.w(TAG, detail);
                CompatibilityDiagnostics.record(
                        "EXIT-005",
                        "Phone desktop cleanup remains pending",
                        detail);
            }
            LocalDesktopNavigationController.release((released, message) -> {
                if (!released) {
                    abort(
                            "EXIT-006",
                            "Could not restore system navigation: " + message,
                            null);
                    return;
                }
                if (result.success) {
                    LocalDesktopSessionState.clearCleanupPending(mActivity);
                }
                continuation.run();
            });
        });
    }

    private void abort(
            final String code,
            final String message,
            final Throwable error) {
        Log.w(TAG, "MagicDesk exit aborted: " + message, error);
        mActivity.runOnUiThread(() -> {
            mOperationInProgress = false;
            mHost.showSessionError(code, message, error);
        });
    }

}

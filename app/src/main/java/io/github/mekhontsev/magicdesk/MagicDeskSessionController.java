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
        final int desktopDisplayId = resolveExternalDesktopDisplayId();
        final DesktopDisplayTarget.Kind targetKind = desktopDisplayId > 0
                ? DesktopRuntimeBridge.getDesktopTargetKind(desktopDisplayId)
                : null;
        new MagicDeskExitCoordinator(
                new MagicDeskExitCoordinator.Operations() {
                    @Override
                    public void restoreHardware(
                            final MagicDeskExitCoordinator.Callback callback) {
                        if (ShellAccess.isReady()) {
                            RedmagicHardwareController.restoreChangedState(
                                    callback::onComplete);
                        } else {
                            callback.onComplete(true);
                        }
                    }

                    @Override
                    public void restorePhoneScreen(
                            final MagicDeskExitCoordinator.Callback callback) {
                        ConsoleModeSwitcher.setPhoneScreenOff(
                                false, callback::onComplete);
                    }

                    @Override
                    public void returnConsoleTasks(
                            final MagicDeskExitCoordinator.Callback callback) {
                        ConsoleModeSwitcher.returnConsoleTasksToPhone(
                                callback::onComplete);
                    }

                    @Override
                    public void closeDesktop(
                            final MagicDeskExitCoordinator.Callback callback) {
                        closeDesktopBeforeExit(
                                desktopDisplayId, targetKind, callback);
                    }

                    @Override
                    public void cleanPhoneTasks(
                            final MagicDeskExitCoordinator.Callback callback) {
                        cleanupPhoneTasksBeforeExit(callback::onComplete);
                    }

                    @Override
                    public void finishExit() {
                        MagicDeskSessionController.this.finishExit();
                    }
                },
                this::reportExitStepFailure)
                .start();
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
        final int displayId = display.getDisplayId();
        final DesktopDisplayTarget.Kind targetKind =
                DesktopRuntimeBridge.getDesktopTargetKind(displayId);
        FreeformLaunchAnchorActivity.releaseBeforeDisplayRemoval(
                () -> closeExternalDesktop(
                        displayId,
                        targetKind,
                        true,
                        success -> finishCloseDesktop(
                                success,
                                targetKind == DesktopDisplayTarget.Kind.WIRELESS
                                        ? "WIRELESS-DISPLAY-002"
                                        : "NUBIA-CONSOLE-001",
                                targetKind == DesktopDisplayTarget.Kind.WIRELESS
                                        ? R.string.status_close_desktop_failed
                                        : R.string.status_mirror_failed)));
    }

    private void closeExternalDesktop(
            final int displayId,
            final DesktopDisplayTarget.Kind targetKind,
            final boolean restorePhonePanel,
            final ConsoleModeSwitcher.ResultCallback callback) {
        if (targetKind == DesktopDisplayTarget.Kind.SIMULATED) {
            DesktopRuntimeBridge.closeExternalDesktopSession(displayId);
            callback.onComplete(true);
            return;
        }
        if (targetKind == DesktopDisplayTarget.Kind.WIRELESS) {
            ConsoleModeSwitcher.disconnectWirelessDisplay(
                    success -> {
                        if (success && restorePhonePanel) {
                            MagicDeskRuntimeService
                                    .restorePhonePanelAfterExternalDesktopRemovalIfRunning(
                                            displayId);
                        }
                        callback.onComplete(success);
                    });
            return;
        }
        if (restorePhonePanel) {
            ConsoleModeSwitcher.switchToMirrorWithControlPanel(
                    callback::onComplete);
        } else {
            ConsoleModeSwitcher.switchToMirror(callback::onComplete);
        }
    }

    private void closeDesktopBeforeExit(
            final int displayId,
            final DesktopDisplayTarget.Kind targetKind,
            final MagicDeskExitCoordinator.Callback callback) {
        if (displayId <= Display.DEFAULT_DISPLAY) {
            callback.onComplete(true);
            return;
        }
        FreeformLaunchAnchorActivity.releaseBeforeDisplayRemoval(
                () -> closeExternalDesktop(
                        displayId,
                        targetKind,
                        false,
                        callback::onComplete));
    }

    private int resolveExternalDesktopDisplayId() {
        final Display display = mActivity.getDisplay();
        if (display != null
                && display.getDisplayId() > Display.DEFAULT_DISPLAY) {
            return display.getDisplayId();
        }
        final int activeDisplayId =
                DesktopRuntimeBridge.getActiveDesktopDisplayId();
        return activeDisplayId > Display.DEFAULT_DISPLAY
                ? activeDisplayId : Display.INVALID_DISPLAY;
    }

    private void finishCloseDesktop(
            final boolean success,
            final String code,
            final int messageResource) {
        if (!success) {
            abort(
                    code,
                    mActivity.getString(messageResource),
                    null);
        }
    }

    private void finishExit() {
        runExitFinalizer(
                "EXIT-007",
                "Could not stop the keyboard input bridge",
                KeyboardShortcutWatcher::stop);
        runExitFinalizer(
                "EXIT-008",
                "Could not stop the MagicDesk runtime",
                () -> MagicDeskRuntimeService.stop(mActivity));
        runExitFinalizer(
                "EXIT-009",
                "Could not disconnect the Shizuku service",
                ShellAccess::disconnect);
        mActivity.runOnUiThread(this::openHomeAndFinishTasks);
    }

    private void openHomeAndFinishTasks() {
        try {
            final Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mActivity.startActivity(home);
        } catch (RuntimeException error) {
            reportExitFailure(
                    "EXIT-004",
                    mActivity.getString(
                            R.string.status_exit_failed,
                            error.getMessage()),
                    error);
        }
        try {
            final ActivityManager activityManager =
                    mActivity.getSystemService(ActivityManager.class);
            if (activityManager == null) {
                mActivity.finishAndRemoveTask();
                return;
            }
            for (final ActivityManager.AppTask task
                    : activityManager.getAppTasks()) {
                try {
                    task.finishAndRemoveTask();
                } catch (RuntimeException error) {
                    reportExitFailure(
                            "EXIT-010",
                            "Could not remove a MagicDesk task",
                            error);
                }
            }
        } catch (RuntimeException error) {
            reportExitFailure(
                    "EXIT-010",
                    "Could not finish MagicDesk tasks",
                    error);
            try {
                mActivity.finishAndRemoveTask();
            } catch (RuntimeException finishError) {
                Log.w(TAG, "final activity finish failed", finishError);
            }
        }
    }

    private void cleanupPhoneTasksBeforeExit(
            final MagicDeskExitCoordinator.Callback continuation) {
        if (!ShellAccess.isReady()) {
            continuation.onComplete(true);
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
            final MagicDeskExitCoordinator.Callback continuation) {
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
                    Log.w(TAG, "Could not restore system navigation: " + message);
                }
                if (result.success) {
                    LocalDesktopSessionState.clearCleanupPending(mActivity);
                }
                continuation.onComplete(released);
            });
        });
    }

    private void reportExitStepFailure(
            final MagicDeskExitCoordinator.Step step,
            final Throwable error) {
        switch (step) {
            case RESTORE_HARDWARE:
                reportExitFailure(
                        "REDMAGIC-HW-RESTORE-001",
                        mActivity.getString(R.string.hardware_restore_failed),
                        error);
                break;
            case RESTORE_PHONE_SCREEN:
                reportExitFailure(
                        "EXIT-001",
                        "Could not restore the phone screen",
                        error);
                break;
            case RETURN_CONSOLE_TASKS:
                reportExitFailure(
                        "EXIT-002",
                        "Could not return Console tasks to the phone",
                        error);
                break;
            case CLOSE_DESKTOP:
                reportExitFailure(
                        "EXIT-003",
                        "Could not close the desktop session",
                        error);
                break;
            case CLEAN_PHONE_TASKS:
                reportExitFailure(
                        "EXIT-006",
                        "Could not fully restore phone desktop state",
                        error);
                break;
            default:
                throw new AssertionError(step);
        }
    }

    private void runExitFinalizer(
            final String code,
            final String message,
            final Runnable finalizer) {
        try {
            finalizer.run();
        } catch (RuntimeException error) {
            reportExitFailure(code, message, error);
        }
    }

    private void reportExitFailure(
            final String code,
            final String message,
            final Throwable error) {
        Log.w(TAG, "MagicDesk exit cleanup failed: " + message, error);
        CompatibilityDiagnostics.record(code, message, "", error);
        mActivity.runOnUiThread(() ->
                mHost.showSessionError(code, message, error));
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

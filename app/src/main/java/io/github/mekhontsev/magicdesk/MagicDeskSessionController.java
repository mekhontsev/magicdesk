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
    private final PlatformDriver mPlatform;
    private final PlatformPhoneUiDriver mPhoneUi;
    private volatile boolean mOperationInProgress;

    MagicDeskSessionController(final MagicDeskSessionHost host) {
        mHost = host;
        mActivity = host.sessionActivity();
        mPlatform = PlatformDrivers.current();
        mPhoneUi = mPlatform.phoneUi();
    }

    void exit() {
        if (mOperationInProgress) {
            return;
        }
        mOperationInProgress = true;
        Log.i(TAG, "full MagicDesk exit requested");
        mHost.showSessionStatus(
                mActivity.getString(R.string.status_exiting));
        MagicDeskRuntime.clearParkedDesktopTasks();
        BuiltInWindowRegistry.finishAll(this::startExit);
    }

    private void startExit() {
        final DesktopDisplayTarget desktopTarget =
                resolveExternalDesktopTarget();
        new MagicDeskExitCoordinator(
                new MagicDeskExitCoordinator.Operations() {
                    @Override
                    public void restoreHardware(
                            final MagicDeskExitCoordinator.Callback callback) {
                        if (ShellAccess.isReady()) {
                            mPlatform.restoreRuntimeState(
                                    success -> callback.onComplete(
                                            success.booleanValue()));
                        } else {
                            callback.onComplete(true);
                        }
                    }

                    @Override
                    public void restorePhoneScreen(
                            final MagicDeskExitCoordinator.Callback callback) {
                        if (!mPhoneUi.isAvailable()) {
                            callback.onComplete(true);
                            return;
                        }
                        DesktopOperations.setPhoneScreenOff(
                                false, callback::onComplete);
                    }

                    @Override
                    public void returnDesktopTasks(
                            final MagicDeskExitCoordinator.Callback callback) {
                        DesktopOperations.returnDesktopTasksToPhone(
                                desktopTarget, callback::onComplete);
                    }

                    @Override
                    public void closeDesktop(
                            final MagicDeskExitCoordinator.Callback callback) {
                        closeDesktopBeforeExit(
                                desktopTarget, callback);
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
        final Display display = mActivity.getDisplay();
        if (display == null) {
            mActivity.runOnUiThread(mActivity::finishAndRemoveTask);
            return;
        }
        final int displayId = display.getDisplayId();
        final DesktopDisplayTarget target = displayId == Display.DEFAULT_DISPLAY
                ? DesktopDisplayTarget.phone()
                : DesktopRuntimeBridge.getDesktopTarget(displayId);
        closeDesktop(target);
    }

    void closeDesktop(final DesktopDisplayTarget target) {
        if (mOperationInProgress) {
            return;
        }
        mOperationInProgress = true;
        Log.i(TAG, "desktop close requested");
        mHost.showSessionStatus(
                mActivity.getString(R.string.status_desktop_closing));
        if (target == null) {
            mOperationInProgress = false;
            finishCloseDesktop(
                    false,
                    "DISPLAY-CLOSE-001",
                    R.string.status_close_desktop_failed);
            return;
        }
        DesktopOperations.closeDesktop(
                target,
                true,
                success -> {
                    mOperationInProgress = false;
                    finishCloseDesktop(
                            success,
                            closeFailureCode(target),
                            R.string.status_close_desktop_failed);
                });
    }

    private void closeDesktopBeforeExit(
            final DesktopDisplayTarget target,
            final MagicDeskExitCoordinator.Callback callback) {
        if (target == null) {
            callback.onComplete(true);
            return;
        }
        DesktopOperations.closeDesktop(
                target,
                false,
                callback::onComplete);
    }

    private DesktopDisplayTarget resolveExternalDesktopTarget() {
        final Display display = mActivity.getDisplay();
        if (display != null
                && display.getDisplayId() > Display.DEFAULT_DISPLAY) {
            return DesktopRuntimeBridge.getDesktopTarget(
                    display.getDisplayId());
        }
        final DesktopDisplayTarget target =
                DesktopRuntimeBridge.getActiveDesktopTarget();
        return target != null
                        && target.displayId > Display.DEFAULT_DISPLAY
                ? target : null;
    }

    private String closeFailureCode(
            final DesktopDisplayTarget target) {
        if (target != null
                && target.kind == DesktopDisplayTarget.Kind.WIRELESS) {
            return "WIRELESS-DISPLAY-002";
        }
        return "DISPLAY-CLOSE-001";
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
        try {
            MagicDeskRuntime.stop(mActivity, this::finishRuntimeExit);
        } catch (RuntimeException error) {
            reportExitFailure(
                    "EXIT-008",
                    "Could not stop the MagicDesk runtime",
                    error);
        }
    }

    private void finishRuntimeExit() {
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
        recoverPhoneTasksBeforeExit(continuation);
    }

    private void recoverPhoneTasksBeforeExit(
            final MagicDeskExitCoordinator.Callback continuation) {
        PhoneDesktopTaskRecovery.recover(result -> {
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
            if (result.success) {
                LocalDesktopSessionState.clearCleanupPending(mActivity);
            }
            continuation.onComplete(result.success);
        });
    }

    private void reportExitStepFailure(
            final MagicDeskExitCoordinator.Step step,
            final Throwable error) {
        switch (step) {
            case RESTORE_HARDWARE:
                reportExitFailure(
                        "PLATFORM-HW-RESTORE-001",
                        mActivity.getString(R.string.hardware_restore_failed),
                        error);
                break;
            case RESTORE_PHONE_SCREEN:
                reportExitFailure(
                        "EXIT-001",
                        "Could not restore the phone screen",
                        error);
                break;
            case RETURN_DESKTOP_TASKS:
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

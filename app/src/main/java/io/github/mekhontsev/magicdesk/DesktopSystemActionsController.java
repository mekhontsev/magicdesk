package io.github.mekhontsev.magicdesk;

import android.app.ActivityOptions;
import android.view.View;

final class DesktopSystemActionsController {
    private final DesktopShellActivity mActivity;

    DesktopSystemActionsController(final DesktopShellActivity activity) {
        mActivity = activity;
    }

    void showDesktop() {
        DesktopOperations.toggleDesktopWorkspace();
    }

    void captureScreenshot() {
        mActivity.hideAllPanels();
        final View decor = mActivity.getWindow().getDecorView();
        if (!decor.isAttachedToWindow()) {
            DesktopOperations.captureScreenshot();
            return;
        }
        // Give WindowManager two frames to remove overlay windows from the capture.
        decor.postOnAnimation(() ->
                decor.postOnAnimation(DesktopOperations::captureScreenshot));
    }

    void toggleRecording() {
        final DisplayRecordingController controller =
                DisplayRecordingController.get();
        final boolean starting = controller.snapshot().state
                == DisplayRecordingController.State.IDLE;
        mActivity.hideAllPanels();
        if (!starting) {
            controller.toggle();
            return;
        }
        final View decor = mActivity.getWindow().getDecorView();
        if (!decor.isAttachedToWindow()) {
            controller.toggle();
            return;
        }
        decor.postOnAnimation(() ->
                decor.postOnAnimation(controller::toggle));
    }

    void openDeviceSetup() {
        mActivity.hideAllPanels();
        mActivity.startActivity(
                DeviceSetupActivity.createManualIntent(mActivity),
                optionsForCurrentDisplay().toBundle());
    }

    void openControlPanel() {
        mActivity.hideAllPanels();
        PhoneControlPanelLauncher.open(mActivity);
    }

    void openFiles() {
        mActivity.hideAllPanels();
        try {
            mActivity.launchInternalWindow(
                    FileManagerActivity.createIntent(mActivity),
                    BuiltInDesktopAppCatalog.filesTarget(),
                    mActivity.getString(R.string.file_manager_title));
        } catch (RuntimeException error) {
            mActivity.setErrorStatus(
                    "FILES-001",
                    "Cannot open Files",
                    "display=" + mActivity.getCurrentDisplayId(),
                    error);
        }
    }

    void openDiagnostics() {
        mActivity.hideAllPanels();
        try {
            mActivity.launchInternalWindow(
                    DiagnosticsActivity.createIntent(mActivity),
                    BuiltInDesktopAppCatalog.diagnosticsTarget(),
                    mActivity.getString(R.string.diagnostics_title));
        } catch (RuntimeException error) {
            mActivity.setErrorStatus(
                    "DIAGNOSTICS-001",
                    "Cannot open compatibility diagnostics",
                    "display=" + mActivity.getCurrentDisplayId(),
                    error);
        }
    }

    void openConsole() {
        openConsole(CommandConsoleActivity.createIntent(mActivity));
    }

    void openTermuxConsole() {
        openConsole(CommandConsoleActivity.createTermuxIntent(mActivity));
    }

    void openConsole(final android.content.Intent intent) {
        mActivity.hideAllPanels();
        try {
            mActivity.launchInternalWindow(
                    intent,
                    CommandConsoleActivity.launchTarget(),
                    mActivity.getString(R.string.console_title));
        } catch (RuntimeException error) {
            mActivity.setErrorStatus(
                    "CONSOLE-001",
                    "Cannot open Console",
                    "display=" + mActivity.getCurrentDisplayId(),
                    error);
        }
    }

    void openTaskManager() {
        mActivity.hideAllPanels();
        try {
            mActivity.launchInternalWindow(
                    TaskManagerActivity.createIntent(mActivity),
                    TaskManagerActivity.launchTarget(),
                    mActivity.getString(R.string.task_manager_title));
        } catch (RuntimeException error) {
            mActivity.setErrorStatus(
                    "TASK-MANAGER-001",
                    "Cannot open Task Manager",
                    "display=" + mActivity.getCurrentDisplayId(),
                    error);
        }
    }

    void openSettings() {
        mActivity.hideAllPanels();
        try {
            mActivity.launchInternalWindow(
                    SettingsActivity.createIntent(mActivity),
                    SettingsActivity.launchTarget(mActivity),
                    mActivity.getString(R.string.settings_title));
        } catch (RuntimeException error) {
            mActivity.setErrorStatus(
                    "SETTINGS-001",
                    "Cannot open MagicDesk settings",
                    "display=" + mActivity.getCurrentDisplayId(),
                    error);
        }
    }

    private ActivityOptions optionsForCurrentDisplay() {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(mActivity.getCurrentDisplayId());
        return options;
    }
}

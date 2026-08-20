package io.github.mekhontsev.magicdesk;

import android.app.ActivityOptions;
import android.view.View;

final class DesktopSystemActionsController {
    private final DesktopShellActivity mActivity;

    DesktopSystemActionsController(final DesktopShellActivity activity) {
        mActivity = activity;
    }

    void showDesktop() {
        ConsoleModeSwitcher.toggleDesktopWorkspace();
    }

    void captureScreenshot() {
        mActivity.hideAllPanels();
        final View decor = mActivity.getWindow().getDecorView();
        if (!decor.isAttachedToWindow()) {
            ConsoleModeSwitcher.captureScreenshot();
            return;
        }
        // Give WindowManager two frames to remove overlay windows from the capture.
        decor.postOnAnimation(() ->
                decor.postOnAnimation(ConsoleModeSwitcher::captureScreenshot));
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

    void openDiagnostics() {
        mActivity.hideAllPanels();
        try {
            mActivity.startActivity(
                    DiagnosticsActivity.createIntent(mActivity),
                    optionsForCurrentDisplay().toBundle());
        } catch (RuntimeException error) {
            mActivity.setErrorStatus(
                    "DIAGNOSTICS-001",
                    "Cannot open compatibility diagnostics",
                    "display=" + mActivity.getCurrentDisplayId(),
                    error);
        }
    }

    void openConsole() {
        mActivity.hideAllPanels();
        try {
            mActivity.launchInternalWindow(
                    CommandConsoleActivity.createIntent(mActivity),
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

    void openTermuxX11(final AppItem app) {
        mActivity.hideAllPanels();
        if (!TermuxX11Integration.handlesDefaultLaunch(mActivity, app)) {
            mActivity.setErrorStatus(
                    "TERMUX-X11-001",
                    mActivity.getString(R.string.status_termux_x11_unavailable));
            return;
        }
        try {
            if (!TermuxX11Integration.ensureRunCommandPermission(mActivity)) {
                return;
            }
            // The viewer remains a normal AppItem so every window transition,
            // taskbar action, and saved launch mode follows the shared path.
            mActivity.launchDefault(
                    app,
                    () -> TaskCommandQueue.execute(() ->
                            TermuxX11Integration.startOrReconnect(mActivity)));
        } catch (RuntimeException error) {
            mActivity.setErrorStatus(
                    "TERMUX-X11-002",
                    mActivity.getString(R.string.status_termux_x11_failed),
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

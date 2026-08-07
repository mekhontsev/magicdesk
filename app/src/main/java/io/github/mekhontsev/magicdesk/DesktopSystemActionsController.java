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

    private ActivityOptions optionsForCurrentDisplay() {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(mActivity.getCurrentDisplayId());
        return options;
    }
}

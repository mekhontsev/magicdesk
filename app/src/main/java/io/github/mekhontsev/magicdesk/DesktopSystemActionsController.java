package io.github.mekhontsev.magicdesk;

import android.app.ActivityOptions;
import android.view.View;

final class DesktopSystemActionsController {
    private final DesktopShellActivity mActivity;

    DesktopSystemActionsController(final DesktopShellActivity activity) {
        mActivity = activity;
    }

    void showDesktop() {
        mActivity.hideAllPanels();
        ConsoleModeSwitcher.showMagicDesk();
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

package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;

public final class ControlActivity extends Activity
        implements PhoneControlPanelController.Actions,
        MagicDeskSessionHost {
    private PhoneControlPanelController mPanel;
    private MagicDeskSessionController mSessionController;
    private ContentObserver mConsoleStateObserver;
    private String mStatus;

    static Intent createLaunchIntent(final android.content.Context context) {
        return new Intent(context, ControlActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!DeviceSetupManager.isRuntimeAuthorized()) {
            startActivity(DeviceSetupActivity.createLaunchIntent(this));
            finish();
            return;
        }
        final DesktopUiFactory ui = new DesktopUiFactory(this);
        mPanel = new PhoneControlPanelController(this, ui, this);
        mSessionController = new MagicDeskSessionController(this);
        mStatus = getString(R.string.control_status_ready);
        setContentView(mPanel.createView());
        registerConsoleStateObserver();
        MagicDeskRuntimeService.start(this);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        MagicDeskRuntimeService.refreshNotificationIfRunning();
        mStatus = getString(ConsoleModeState.isActive(this)
                ? R.string.control_status_console_active
                : R.string.control_status_ready);
        refresh();
    }

    @Override
    protected void onDestroy() {
        if (mConsoleStateObserver != null) {
            getContentResolver().unregisterContentObserver(
                    mConsoleStateObserver);
            mConsoleStateObserver = null;
        }
        super.onDestroy();
    }

    @Override
    public void openDesktopHere() {
        mStatus = getString(R.string.status_desktop_opening);
        refresh();
        DesktopActivity.launchOnDisplay(this, currentDisplayId());
    }

    @Override
    public void toggleConsoleMode() {
        if (!RuntimeAccess.has(RuntimeAccess.Capability.CONSOLE_CONTROL)) {
            return;
        }
        if (!ConsoleModeState.isActive(this)) {
            mStatus = getString(R.string.status_console_starting);
            refresh();
            ConsoleModeSwitcher.showMagicDesk();
            return;
        }
        mStatus = getString(R.string.status_mirror_switching);
        refresh();
        ConsoleModeSwitcher.switchToMirror(
                success -> runOnUiThread(() -> {
                    mStatus = getString(success
                            ? R.string.status_mirror_active
                            : R.string.status_mirror_failed);
                    if (!success) {
                        CompatibilityDiagnostics.record(
                                "NUBIA-CONSOLE-001",
                                mStatus,
                                "Control panel mirror transition");
                    }
                    refresh();
                }));
    }

    @Override
    public void openTouchpad() {
        mStatus = getString(R.string.status_touchpad_opening);
        refresh();
        ConsoleModeSwitcher.openTouchpad();
    }

    @Override
    public void togglePhoneScreen() {
        if (!RuntimeAccess.has(
                RuntimeAccess.Capability.PHONE_SCREEN_CONTROL)) {
            return;
        }
        final boolean screenOff =
                !ConsoleModeState.isPhoneScreenOff(this);
        mStatus = getString(R.string.status_phone_screen_applying);
        refresh();
        ConsoleModeSwitcher.setPhoneScreenOff(
                screenOff,
                success -> runOnUiThread(() -> {
                    final int result;
                    if (!success) {
                        result = R.string.status_phone_screen_failed;
                    } else if (screenOff) {
                        result = R.string.status_phone_screen_off;
                    } else {
                        result = R.string.status_phone_screen_on;
                    }
                    mStatus = getString(result);
                    if (!success) {
                        CompatibilityDiagnostics.record(
                                "NUBIA-SCREEN-001",
                                mStatus,
                                "Control panel phone screen command");
                    }
                    refresh();
                }));
    }

    @Override
    public void openDeviceSetup() {
        startActivity(DeviceSetupActivity.createManualIntent(this));
    }

    @Override
    public void openDiagnostics() {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(currentDisplayId());
        startActivity(
                DiagnosticsActivity.createIntent(this),
                options.toBundle());
    }

    @Override
    public void exitMagicDesk() {
        mSessionController.exit();
    }

    @Override
    public Activity sessionActivity() {
        return this;
    }

    @Override
    public void showSessionStatus(final String message) {
        mStatus = message;
        refresh();
    }

    @Override
    public void showSessionError(
            final String code,
            final String message,
            final Throwable error) {
        CompatibilityDiagnostics.record(code, message, "", error);
        mStatus = message + " [" + code + "]";
        refresh();
    }

    @Override
    public void releaseSessionUi() {
        // The control panel does not own desktop overlay windows.
    }

    private void refresh() {
        if (mPanel == null) {
            return;
        }
        final int consoleDisplayId =
                ConsoleModeState.activeDisplayId(this);
        mPanel.render(new PhoneControlPanelController.State(
                consoleDisplayId > Display.DEFAULT_DISPLAY,
                RuntimeAccess.has(
                        RuntimeAccess.Capability.CONSOLE_CONTROL),
                ConsoleModeState.isPhoneScreenOff(this),
                RuntimeAccess.has(
                        RuntimeAccess.Capability.PHONE_SCREEN_CONTROL),
                mStatus,
                RuntimeAccess.backendName(),
                currentDisplayId(),
                consoleDisplayId));
    }

    private void registerConsoleStateObserver() {
        mConsoleStateObserver = new ContentObserver(
                new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(final boolean selfChange) {
                mStatus = getString(ConsoleModeState.isActive(
                        ControlActivity.this)
                        ? R.string.control_status_console_active
                        : R.string.control_status_ready);
                refresh();
            }
        };
        getContentResolver().registerContentObserver(
                android.provider.Settings.Global.getUriFor(
                        ConsoleModeState.DISPLAY_ID_SETTING),
                false,
                mConsoleStateObserver);
        getContentResolver().registerContentObserver(
                android.provider.Settings.Global.getUriFor(
                        ConsoleModeState.PHONE_SCREEN_OFF_SETTING),
                false,
                mConsoleStateObserver);
    }

    private int currentDisplayId() {
        final Display display = getDisplay();
        return display == null
                ? Display.DEFAULT_DISPLAY : display.getDisplayId();
    }
}

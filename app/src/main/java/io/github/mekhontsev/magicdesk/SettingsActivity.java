package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

public final class SettingsActivity extends Activity
        implements SettingsView.Actions {
    private SettingsView mView;

    static Intent createIntent(final Context context) {
        return new Intent(context, SettingsActivity.class);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mView = new SettingsView(this, this);
        setContentView(mView.create());
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    public void setTaskbarAutoHide(final boolean enabled) {
        saveSetting(MagicDeskSettings.setTaskbarAutoHide(enabled));
    }

    @Override
    public void setKeepDesktopAwake(final boolean enabled) {
        saveSetting(MagicDeskSettings.setKeepDesktopAwake(enabled));
    }

    @Override
    public void setOpenTouchpadAutomatically(final boolean enabled) {
        saveSetting(
                MagicDeskSettings.setOpenTouchpadAutomatically(enabled));
    }

    @Override
    public void setOpenFilesWithSingleClick(final boolean enabled) {
        saveSetting(MagicDeskSettings.setOpenFilesWithSingleClick(enabled));
    }

    @Override
    public void openDeviceSetup() {
        startActivityOnCurrentDisplay(
                DeviceSetupActivity.createManualIntent(this));
    }

    @Override
    public void openDiagnostics() {
        startActivityOnCurrentDisplay(
                DiagnosticsActivity.createIntent(this));
    }

    @Override
    public void showAbout() {
        AboutDialog.show(this);
    }

    @Override
    public void closeSettings() {
        finish();
    }

    private void saveSetting(final boolean saved) {
        if (!saved) {
            Toast.makeText(
                    this,
                    R.string.settings_save_failed,
                    Toast.LENGTH_SHORT).show();
            render();
            return;
        }
        DesktopRuntimeBridge.refreshSettings();
        MagicDeskRuntimeService.refreshSettingsIfRunning();
        render();
    }

    private void startActivityOnCurrentDisplay(final Intent intent) {
        final ActivityOptions options = ActivityOptions.makeBasic();
        final android.view.Display display = getDisplay();
        options.setLaunchDisplayId(display == null
                ? android.view.Display.DEFAULT_DISPLAY
                : display.getDisplayId());
        startActivity(intent, options.toBundle());
    }

    private void render() {
        if (mView != null) {
            mView.render(MagicDeskSettings.load());
        }
    }
}

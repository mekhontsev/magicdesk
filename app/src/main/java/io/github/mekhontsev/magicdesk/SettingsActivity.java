package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Toast;

import java.io.IOException;

public final class SettingsActivity extends Activity
        implements SettingsView.Actions {
    private SettingsView mView;
    private boolean mSystemDesktopModeBusy;
    private final ShellAccess.StateListener mShellStateListener = state ->
            runOnUiThread(this::renderSystemDesktopMode);

    static Intent createIntent(final Context context) {
        return new Intent(context, SettingsActivity.class);
    }

    static AppLaunchTarget launchTarget(final Context context) {
        return BuiltInDesktopAppCatalog.settingsTarget();
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DesktopTaskDescription.apply(
                this,
                R.string.settings_title,
                R.mipmap.ic_launcher);
        BuiltInWindowRegistry.register(this);
        mView = new SettingsView(this, this);
        setContentView(mView.create());
        ShellAccess.addStateListener(mShellStateListener);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    protected void onDestroy() {
        ShellAccess.removeStateListener(mShellStateListener);
        BuiltInWindowRegistry.unregister(this);
        super.onDestroy();
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
    public void setDisableAdaptiveBrightnessOnExternalDesktop(
            final boolean enabled) {
        saveSetting(MagicDeskSettings
                .setDisableAdaptiveBrightnessOnExternalDesktop(enabled));
    }

    @Override
    public void setOpenTouchpadAutomatically(final boolean enabled) {
        saveSetting(
                MagicDeskSettings.setOpenTouchpadAutomatically(enabled));
    }

    @Override
    public void setCompatibilityOption(
            final DesktopCompatibilityPolicy.Option option, final boolean enabled) {
        saveSetting(MagicDeskSettings.setCompatibilityOption(option, enabled));
    }

    @Override
    public void setSystemDesktopMode(final boolean enabled) {
        renderSystemDesktopMode();
        if (mSystemDesktopModeBusy || !SystemDesktopModeSetting.canChange()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_system_desktop_mode)
                .setMessage(R.string.settings_system_desktop_mode_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) -> applySystemDesktopMode(enabled))
                .show();
    }

    private void applySystemDesktopMode(final boolean enabled) {
        if (mSystemDesktopModeBusy) {
            return;
        }
        mSystemDesktopModeBusy = true;
        renderSystemDesktopMode();
        final Context context = getApplicationContext();
        new Thread(() -> {
            String failure = null;
            boolean changed = false;
            try {
                changed = SystemDesktopModeSetting.setEnabled(context, enabled);
            } catch (IOException | RuntimeException error) {
                failure = error.getMessage();
                if (failure == null || failure.isEmpty()) {
                    failure = error.getClass().getSimpleName();
                }
                CompatibilityDiagnostics.record("SYSTEM-DESKTOP-MODE-001",
                        "Could not change Android desktop mode", failure, error);
            }
            final String resultError = failure;
            final boolean resultChanged = changed;
            runOnUiThread(() -> {
                mSystemDesktopModeBusy = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                renderSystemDesktopMode();
                if (resultError != null) {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.settings_save_failed)
                            .setMessage(resultError)
                            .setPositiveButton(android.R.string.ok, null)
                            .show();
                } else if (resultChanged) {
                    Toast.makeText(this, R.string.settings_system_desktop_mode_apply_notice,
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "MagicDeskSystemDesktopMode").start();
    }

    @Override
    public void setOpenFilesWithSingleClick(final boolean enabled) {
        saveSetting(MagicDeskSettings.setOpenFilesWithSingleClick(enabled));
    }

    @Override
    public void setMcpEnabled(final boolean enabled) {
        saveSetting(MagicDeskMcpPreferences.setEnabled(this, enabled));
    }

    @Override
    public void setMcpDeveloperTools(final boolean enabled) {
        saveSetting(MagicDeskMcpPreferences.setDeveloperTools(this, enabled));
    }

    @Override
    public void setMcpShellTools(final boolean enabled) {
        saveSetting(MagicDeskMcpPreferences.setShellTools(this, enabled));
    }

    @Override
    public void copyMcpConnection() {
        final MagicDeskMcpPreferences.Values settings =
                MagicDeskMcpPreferences.load(this);
        if (settings.token.isEmpty()) {
            Toast.makeText(this, R.string.settings_mcp_copy_failed,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        final String value = "Endpoint: " + settings.endpoint()
                + "\nAuthorization: Bearer " + settings.token
                + "\nADB: adb forward tcp:"
                + MagicDeskMcpPreferences.PORT + " tcp:"
                + MagicDeskMcpPreferences.PORT;
        final AndroidClipboardGateway.OperationResult copied =
                AndroidClipboardGateway.get(this).writeText(
                        getString(R.string.settings_mcp_connection),
                        value,
                        true);
        if (!copied.successful) {
            Toast.makeText(this, R.string.settings_mcp_copy_failed,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, R.string.settings_mcp_copied,
                Toast.LENGTH_SHORT).show();
    }

    @Override
    public void regenerateMcpToken() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_mcp_regenerate_token)
                .setMessage(R.string.settings_mcp_regenerate_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.action_reset, (dialog, which) -> {
                    final boolean saved =
                            MagicDeskMcpPreferences.regenerateToken(this);
                    saveSetting(saved);
                    if (saved) {
                        Toast.makeText(
                                this,
                                R.string.settings_mcp_token_regenerated,
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    @Override
    public void configureTermuxX11() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setTypeface(Typeface.MONOSPACE);
        input.setMaxLines(4);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(
                TermuxX11StartupCommand.MAX_LENGTH)});
        input.setText(MagicDeskSettings.load().termuxX11StartupCommand);
        input.setSelection(input.length());

        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_termux_x11_command)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.action_reset, (dialog, which) ->
                        saveSetting(MagicDeskSettings
                                .setTermuxX11StartupCommand(
                                        TermuxX11StartupCommand.DEFAULT)))
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        saveSetting(MagicDeskSettings
                                .setTermuxX11StartupCommand(
                                        input.getText().toString())))
                .show();
    }

    @Override
    public void openDeviceSetup() {
        startActivityOnCurrentDisplay(
                DeviceSetupActivity.createManualIntent(this));
    }

    @Override
    public void openApplicationSettings() {
        final android.view.Display display = getDisplay();
        final int displayId = display == null
                ? android.view.Display.DEFAULT_DISPLAY
                : display.getDisplayId();
        if (displayId == DesktopRuntimeBridge.getActiveDesktopDisplayId()
                && DesktopRuntimeBridge.openApplicationSettings(null)) {
            return;
        }
        startActivityOnCurrentDisplay(
                AppPresentationSettingsActivity.createIntent(this));
    }

    @Override
    public void openDiagnostics() {
        final android.view.Display display = getDisplay();
        final int displayId = display == null
                ? android.view.Display.DEFAULT_DISPLAY
                : display.getDisplayId();
        if (displayId == DesktopRuntimeBridge.getActiveDesktopDisplayId()
                && DesktopRuntimeBridge.openBuiltin("diagnostics")) {
            return;
        }
        startActivityOnCurrentDisplay(
                DiagnosticsActivity.createIntent(this));
    }

    @Override
    public void showAbout() {
        AboutDialog.show(this);
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
        MagicDeskRuntime.refreshSettings(this::render);
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
            mView.render(
                    MagicDeskSettings.load(),
                    MagicDeskMcpPreferences.load(this),
                    MagicDeskMcpRuntime.snapshot());
            renderSystemDesktopMode();
        }
    }

    private void renderSystemDesktopMode() {
        if (mView == null || isFinishing() || isDestroyed()) {
            return;
        }
        try {
            final boolean enabled = SystemDesktopModeSetting.read(this);
            final boolean canChange = SystemDesktopModeSetting.canChange();
            final int status = mSystemDesktopModeBusy
                    ? R.string.settings_system_desktop_mode_saving
                    : !ShellAccess.isReady()
                            ? R.string.settings_system_desktop_mode_shell
                            : !canChange
                                    ? R.string.settings_system_desktop_mode_close
                                    : R.string.settings_system_desktop_mode_apply_notice;
            mView.renderSystemDesktopMode(enabled, !mSystemDesktopModeBusy && canChange, status);
        } catch (IOException error) {
            mView.renderSystemDesktopMode(null, false,
                    R.string.settings_system_desktop_mode_unavailable);
        }
    }
}

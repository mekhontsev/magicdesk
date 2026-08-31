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

public final class SettingsActivity extends Activity
        implements SettingsView.Actions {
    private SettingsView mView;

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
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    protected void onDestroy() {
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
        }
    }
}

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
    public void resetCompatibilityDefaults() {
        if (mSystemDesktopModeBusy || !SystemDesktopModeSetting.canChange()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_compat_reset)
                .setMessage(getString(R.string.settings_compat_reset_confirm,
                        PlatformDrivers.current().name()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        applySystemSetting(() -> DesktopCompatibilitySettings.resetDefaults(
                                getApplicationContext())))
                .show();
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
        final Context context = getApplicationContext();
        applySystemSetting(() -> SystemDesktopModeSetting.setEnabled(context, enabled));
    }

    private interface SystemSettingChange {
        boolean apply() throws IOException;
    }

    private void applySystemSetting(final SystemSettingChange change) {
        if (mSystemDesktopModeBusy) {
            return;
        }
        mSystemDesktopModeBusy = true;
        renderSystemDesktopMode();
        new Thread(() -> {
            String failure = null;
            boolean changed = false;
            try {
                changed = change.apply();
            } catch (IOException | RuntimeException error) {
                failure = error.getMessage();
                if (failure == null || failure.isEmpty()) {
                    failure = error.getClass().getSimpleName();
                }
                CompatibilityDiagnostics.record("SYSTEM-DESKTOP-MODE-001",
                        "Could not apply desktop compatibility settings", failure, error);
            }
            final String resultError = failure;
            final boolean resultChanged = changed;
            runOnUiThread(() -> {
                mSystemDesktopModeBusy = false;
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                render();
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
    public void configureMcpAccess(final boolean network) {
        final var values = MagicDeskMcpPreferences.load(this);
        final var access = network ? values.networkAccess : values.localAccess;
        final var permissions = McpAccessPolicy.Permission.values();
        final CharSequence[] labels = new CharSequence[permissions.length];
        final boolean[] checked = new boolean[permissions.length];
        for (int i = 0; i < permissions.length; i++) {
            labels[i] = getString(permissions[i].label);
            checked[i] = access.has(permissions[i]);
        }
        final android.widget.TextView warning = new android.widget.TextView(this);
        warning.setText(R.string.mcp_permissions_warning);
        final int padding = Math.round(16 * getResources().getDisplayMetrics().density);
        warning.setPadding(padding, padding, padding, padding);
        new AlertDialog.Builder(this)
                .setTitle(network ? R.string.settings_mcp_network_access : R.string.settings_mcp_local_access)
                .setView(warning)
                .setMultiChoiceItems(labels, checked, (dialog, index, selected) -> checked[index] = selected)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    final java.util.Set<String> selected = new java.util.HashSet<>();
                    for (int i = 0; i < permissions.length; i++) {
                        if (checked[i]) selected.add(permissions[i].id);
                    }
                    saveSetting(MagicDeskMcpPreferences.setAccess(this, network, selected));
                }).show();
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
        regenerateMcpToken(false);
    }

    @Override public void setMcpNetworkEnabled(final boolean enabled) {
        if (!enabled) {
            saveSetting(MagicDeskMcpPreferences.setNetworkEnabled(this, false));
        } else {
            McpNetworkSettingsDialog.show(this, true, this::saveSetting, this::render);
        }
    }

    @Override public void configureMcpNetwork() {
        McpNetworkSettingsDialog.show(this, false, this::saveSetting, this::render);
    }

    @Override public void copyMcpNetworkConnection() {
        final var settings = MagicDeskMcpPreferences.load(this);
        final var runtime = MagicDeskMcpRuntime.snapshot();
        if (!runtime.networkRunning || settings.networkToken.isEmpty()) {
            Toast.makeText(this, R.string.settings_mcp_copy_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        final boolean copied = AndroidClipboardGateway.get(this).writeText(
                getString(R.string.settings_mcp_network_copy),
                "Endpoint: " + runtime.networkEndpoint
                        + "\nAuthorization: Bearer " + settings.networkToken, true).successful;
        Toast.makeText(this, copied ? R.string.settings_mcp_copied
                : R.string.settings_mcp_copy_failed, Toast.LENGTH_SHORT).show();
    }

    @Override public void regenerateMcpNetworkToken() {
        regenerateMcpToken(true);
    }

    private void regenerateMcpToken(final boolean network) {
        new AlertDialog.Builder(this)
                .setTitle(network ? R.string.settings_mcp_network_token
                        : R.string.settings_mcp_regenerate_token)
                .setMessage(R.string.settings_mcp_regenerate_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.action_reset, (dialog, which) -> {
                    final boolean saved =
                            network ? MagicDeskMcpPreferences.regenerateNetworkToken(this)
                                    : MagicDeskMcpPreferences.regenerateToken(this);
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
    public void configureShellBackend() {
        final ShellBackend[] backends = ShellBackend.values();
        final String[] labels = java.util.Arrays.stream(backends).map(value -> value.label).toArray(String[]::new);
        new AlertDialog.Builder(this).setTitle(R.string.settings_shell_backend)
                .setSingleChoiceItems(labels, ShellBackend.configured(this).ordinal(), (dialog, which) -> {
                    saveStartupSetting(backends[which].save(this));
                    dialog.dismiss();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    @Override
    public void setForceShell(boolean enabled) {
        saveStartupSetting(ShellPrivilegePolicy.save(this, enabled));
    }

    private void saveStartupSetting(boolean saved) {
        if (!saved) Toast.makeText(this, R.string.settings_save_failed, Toast.LENGTH_SHORT).show();
        render();
    }

    @Override
    public void configureIntegrationPackage(final IntegrationPackage integration) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSingleLine(true);
        input.setTypeface(Typeface.MONOSPACE);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(IntegrationPackage.MAX_LENGTH)});
        input.setText(integration.configured(this));
        input.setSelectAllOnFocus(true);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(SettingsView.integrationLabel(integration))
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.action_reset, null)
                .setPositiveButton(android.R.string.ok, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view ->
                    input.setText(integration.defaultPackage));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                try {
                    if (!integration.save(this, input.getText().toString())) {
                        input.setError(getString(R.string.settings_save_failed));
                        return;
                    }
                } catch (IllegalArgumentException error) {
                    input.setError(getString(R.string.settings_integration_package_invalid));
                    return;
                }
                // These preferences do not reconcile the running shell or terminal services.
                render();
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    @Override
    public void configureConsoleFontSize() {
        ConsoleFontSizeDialog.show(this, R.string.settings_console_font_size,
                ConsolePreferences.fontSizeSp(this), ConsolePreferences.DEFAULT_FONT_SIZE_SP, size -> {
                    ConsolePreferences.setFontSizeSp(this, size);
                    render();
                });
    }

    @Override
    public void openApplicationSettings() {
        final android.view.Display display = getDisplay();
        final int displayId = display == null
                ? android.view.Display.DEFAULT_DISPLAY
                : display.getDisplayId();
        if (displayId == DesktopRuntimeBridge.getActiveDesktopDisplayId()
                && DesktopRuntimeBridge.openApplicationSettings(displayId, null)) {
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
                && DesktopRuntimeBridge.openBuiltin(displayId, "diagnostics")) {
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
            mView.renderSystemDesktopMode(enabled, canChange, mSystemDesktopModeBusy, status);
        } catch (IOException error) {
            mView.renderSystemDesktopMode(null, false, mSystemDesktopModeBusy,
                    R.string.settings_system_desktop_mode_unavailable);
        }
    }
}

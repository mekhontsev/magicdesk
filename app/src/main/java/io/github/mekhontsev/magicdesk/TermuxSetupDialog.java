package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Explicit setup actions and the process-local command observation. */
final class TermuxSetupDialog {
    private final Activity activity;
    private final AlertDialog dialog;
    private final TextView result;
    private final TextView summary;
    private final Button check;
    private final Runnable statusListener = this::renderStatus;

    private TermuxSetupDialog(Activity activity, Runnable settings) {
        this.activity = activity;
        final var endpoint = TermuxIntegration.inspect(activity);
        final var content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(8), dp(24), dp(8));
        final var scroll = new ScrollView(activity);
        scroll.addView(content);
        dialog = new AlertDialog.Builder(activity).setTitle(R.string.console_shell_termux)
                .setView(scroll).setNegativeButton(R.string.action_close, null)
                .setNeutralButton(R.string.control_integration_settings, (d, which) -> settings.run()).create();
        summary = text(content, "");
        if (endpoint.canRequestPermission()) {
            action(content, R.string.control_grant_permission, R.drawable.ic_play, () -> {
                dialog.dismiss();
                TermuxIntegration.ensureRunCommandPermission(activity);
            });
            action(content, R.string.control_app_permissions, R.drawable.ic_settings, () -> {
                dialog.dismiss();
                IntegrationStatusDialogs.openAppSettings(activity, activity.getPackageName());
            });
        }
        if (endpoint.enabled && endpoint.installed && (endpoint.available() || endpoint.canRequestPermission())) {
            text(content, activity.getString(R.string.control_termux_setup_details));
            action(content, R.string.control_termux_copy_setup, R.drawable.ic_file_copy, this::copySetup);
        }
        final Intent launch = activity.getPackageManager().getLaunchIntentForPackage(endpoint.packageName);
        if (launch != null) action(content, R.string.control_open_termux, R.drawable.ic_file_console, () -> {
            try { activity.startActivity(launch); dialog.dismiss(); }
            catch (RuntimeException error) { Toast.makeText(activity, ShellAccess.usefulMessage(error), Toast.LENGTH_LONG).show(); }
        });
        else if (endpoint.installed) action(content, R.string.control_app_settings, R.drawable.ic_settings, () -> {
            dialog.dismiss();
            IntegrationStatusDialogs.openAppSettings(activity, endpoint.packageName);
        });
        check = action(content, R.string.control_termux_check, R.drawable.ic_file_refresh, this::checkConnection);
        check.setEnabled(endpoint.available());
        result = text(content, "");
        result.setVisibility(View.GONE);
        dialog.setOnShowListener(ignored -> TermuxConnectionStatus.get().addListener(statusListener));
        dialog.setOnDismissListener(ignored -> TermuxConnectionStatus.get().removeListener(statusListener));
    }

    static void show(Activity activity, Runnable settings) {
        new TermuxSetupDialog(activity, settings).dialog.show();
    }

    private void copySetup() {
        final var copied = AndroidClipboardGateway.get(activity).writeText(
                activity.getString(R.string.control_termux_copy_setup), TermuxSetupCommand.SCRIPT, false);
        if (!copied.successful) {
            result.setText(copied.error);
            result.setVisibility(View.VISIBLE);
            return;
        }
        Toast.makeText(activity, R.string.control_termux_setup_copied, Toast.LENGTH_SHORT).show();
    }

    private void checkConnection() {
        TermuxConnectionStatus.get().check(activity.getApplicationContext(), TermuxIntegration.inspect(activity), true);
    }

    private void renderStatus() {
        if (!dialog.isShowing() || activity.isFinishing() || activity.isDestroyed()) return;
        final var endpoint = TermuxIntegration.inspect(activity);
        final var snapshot = TermuxConnectionStatus.get().current(endpoint);
        final StringBuilder details = new StringBuilder(activity.getString(IntegrationStatusDialogs.termuxStatus(endpoint)));
        IntegrationStatusDialogs.appendPackage(activity, details, IntegrationPackage.TERMUX);
        if (!endpoint.error.isEmpty()) details.append("\n\n").append(endpoint.error);
        if (endpoint.available() && snapshot.state() == TermuxConnectionStatus.State.UNCHECKED) {
            details.append("\n\n").append(activity.getString(R.string.control_termux_available_details));
        }
        summary.setText(details);
        check.setEnabled(endpoint.available() && snapshot.state() != TermuxConnectionStatus.State.CHECKING);
        result.setVisibility(snapshot.state() == TermuxConnectionStatus.State.UNCHECKED ? View.GONE : View.VISIBLE);
        switch (snapshot.state()) {
            case UNCHECKED -> result.setText("");
            case CHECKING -> result.setText(R.string.control_termux_checking);
            case READY -> result.setText(R.string.control_termux_verified);
            case TIMED_OUT -> result.setText(R.string.control_termux_check_timeout);
            case FAILED -> result.setText(activity.getString(R.string.control_termux_check_failed,
                    snapshot.error().isEmpty() ? activity.getString(R.string.control_termux_unexpected_reply) : snapshot.error()));
        }
    }

    private TextView text(LinearLayout content, CharSequence value) {
        final var text = new TextView(activity);
        text.setText(value);
        text.setTextSize(14);
        text.setTextIsSelectable(true);
        text.setPadding(0, dp(8), 0, dp(8));
        content.addView(text, new LinearLayout.LayoutParams(-1, -2));
        return text;
    }

    private Button action(LinearLayout content, int label, int icon, Runnable run) {
        final var button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        new DesktopUiFactory(activity).setControlIcon(button, icon);
        button.setOnClickListener(view -> run.run());
        content.addView(button, new LinearLayout.LayoutParams(-1, -2));
        return button;
    }

    private int dp(int value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }
}

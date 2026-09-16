package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

/** Read-only integration summaries; authorization happens only through explicit actions. */
final class IntegrationStatusDialogs {
    private IntegrationStatusDialogs() { }

    static int termuxStatus(TermuxIntegration.Endpoint endpoint) {
        if (!endpoint.installed) return R.string.control_termux_not_installed;
        return endpoint.available() ? R.string.control_status_ready : R.string.control_termux_setup_required;
    }

    static void showAccess(Activity activity, boolean canRequest, Runnable request, Runnable settings) {
        final var access = ShellAccess.currentSnapshot();
        final boolean pending = ShellPrivilegePolicy.restartRequired(activity);
        final StringBuilder message = new StringBuilder(activity.getString(
                R.string.control_runtime_status, access.accessLabel()));
        message.append('\n').append(activity.getString(R.string.control_access_backend, access.backend.label));
        if (access.isReady()) message.append('\n').append(activity.getString(R.string.control_access_uid, access.uid));
        message.append('\n').append(activity.getString(ShellPrivilegePolicy.forceShell()
                ? R.string.control_access_limited : R.string.control_access_unlimited));
        if (!access.backend.usesRoot()) appendPackage(activity, message, IntegrationPackage.SHIZUKU);
        if (!access.error.isEmpty()) message.append("\n\n").append(access.error);
        if (pending) message.append("\n\n").append(activity.getString(R.string.access_restart_required));
        final var dialog = new AlertDialog.Builder(activity).setTitle(R.string.control_access_title)
                .setMessage(message).setNegativeButton(R.string.action_close, null)
                .setNeutralButton(R.string.control_integration_settings, (d, which) -> settings.run());
        if (canRequest && (pending || !access.isReady())) dialog.setPositiveButton(
                pending ? R.string.action_exit : R.string.control_access_setup, (d, which) -> request.run());
        dialog.show();
    }

    static void showTermux(Activity activity, Runnable settings) {
        final var endpoint = TermuxIntegration.inspect(activity);
        final StringBuilder message = new StringBuilder(activity.getString(termuxStatus(endpoint)));
        appendPackage(activity, message, IntegrationPackage.TERMUX);
        if (!endpoint.error.isEmpty()) message.append("\n\n").append(endpoint.error);
        if (endpoint.installed) message.append("\n\n").append(activity.getString(endpoint.available()
                ? R.string.control_termux_ready_details : R.string.control_termux_external_commands));
        final var dialog = new AlertDialog.Builder(activity).setTitle(R.string.console_shell_termux)
                .setMessage(message).setNegativeButton(R.string.action_close, null);
        if (endpoint.permissionRequired) {
            dialog.setPositiveButton(R.string.control_grant_permission,
                    (d, which) -> TermuxIntegration.ensureRunCommandPermission(activity));
            dialog.setNeutralButton(R.string.control_app_permissions,
                    (d, which) -> openAppSettings(activity, activity.getPackageName()));
        } else {
            dialog.setNeutralButton(R.string.control_integration_settings, (d, which) -> settings.run());
            final Intent launch = activity.getPackageManager().getLaunchIntentForPackage(endpoint.packageName);
            if (launch != null) dialog.setPositiveButton(R.string.control_open_termux,
                    (d, which) -> activity.startActivity(launch));
            else if (endpoint.installed) dialog.setPositiveButton(R.string.control_app_settings,
                    (d, which) -> openAppSettings(activity, endpoint.packageName));
        }
        dialog.show();
    }

    private static void appendPackage(Activity activity, StringBuilder message, IntegrationPackage integration) {
        message.append('\n').append(activity.getString(R.string.control_integration_package, integration.selected()));
        final String configured = integration.configured(activity);
        if (!configured.equals(integration.selected())) message.append('\n').append(activity.getString(
                R.string.settings_integration_restart_pending, configured));
    }

    private static void openAppSettings(Activity activity, String packageName) {
        activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)));
    }
}

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
        return termuxStatus(endpoint, TermuxConnectionStatus.get().current(endpoint).state());
    }

    static int termuxStatus(TermuxIntegration.Endpoint endpoint, TermuxConnectionStatus.State state) {
        if (!endpoint.enabled) return R.string.control_status_disabled;
        if (!endpoint.installed) return R.string.control_termux_not_installed;
        if (!endpoint.available()) return R.string.control_termux_setup_required;
        return switch (state) {
            case CHECKING -> R.string.control_termux_status_checking;
            case READY -> R.string.control_status_ready;
            case FAILED -> R.string.control_termux_status_failed;
            case UNCHECKED, TIMED_OUT -> R.string.control_termux_available;
        };
    }

    static int desktopStatus(RuntimeCapabilities capabilities) {
        return switch (capabilities.missing(RuntimeCapabilities.Service.DESKTOP)) {
            case "" -> R.string.control_status_ready;
            case "desktop_setup_checking" -> R.string.control_desktop_checking;
            case "desktop_setup" -> R.string.control_desktop_setup;
            case "device_restart" -> R.string.control_desktop_restart;
            case "desktop_disabled", "privileged_disabled" -> R.string.control_status_disabled;
            default -> R.string.control_desktop_unavailable;
        };
    }

    static void showDesktop(Activity activity, boolean canConfigure, Runnable access) {
        final var dialog = new AlertDialog.Builder(activity).setTitle(R.string.control_desktop_title)
                .setMessage("").setNegativeButton(R.string.action_close, null)
                .setNeutralButton(R.string.action_refresh, null)
                .setPositiveButton(R.string.control_desktop_setup, null).create();
        final Runnable render = () -> activity.runOnUiThread(() -> {
            if (activity.isFinishing() || activity.isDestroyed() || !dialog.isShowing()) return;
            final var capabilities = RuntimeCapabilities.current(activity);
            final var snapshot = DesktopSetupStatus.current();
            final String missing = capabilities.missing(RuntimeCapabilities.Service.DESKTOP);
            final StringBuilder message = new StringBuilder(activity.getString(desktopStatus(capabilities)));
            message.append("\n\n").append(activity.getString(R.string.control_desktop_android,
                    android.os.Build.VERSION.RELEASE, android.os.Build.VERSION.SDK_INT));
            message.append('\n').append(activity.getString(R.string.control_runtime_status,
                    ShellAccess.currentSnapshot().accessLabel()));
            if (snapshot.audit() != null && ShellAccess.isReady()) {
                message.append('\n').append(activity.getString(R.string.control_desktop_freeform,
                        activity.getString(snapshot.audit().freeformEnabled ? R.string.state_on : R.string.state_off)));
                message.append('\n').append(activity.getString(R.string.control_desktop_resizable,
                        activity.getString(snapshot.audit().resizableEnabled ? R.string.state_on : R.string.state_off)));
                message.append('\n').append(activity.getString(snapshot.audit().rebootRequired
                        ? R.string.setup_status_reboot_required : R.string.control_desktop_no_restart));
            }
            final int requirement = capabilities.unavailableMessage(RuntimeCapabilities.Service.DESKTOP);
            if (requirement != 0) message.append("\n\n").append(activity.getString(requirement));
            else message.append("\n\n").append(activity.getString(R.string.control_desktop_ready_details));
            if (ShellAccess.isReady() && !snapshot.error().isEmpty()) message.append("\n\n").append(snapshot.error());
            dialog.setMessage(message);
            final var setup = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            final boolean needsAccess = "privileged_service".equals(missing);
            setup.setText(needsAccess ? R.string.control_access_title : R.string.control_desktop_setup);
            setup.setEnabled(canConfigure && RuntimeCapabilities.allowsDesktop(android.os.Build.VERSION.SDK_INT));
            setup.setOnClickListener(view -> {
                dialog.dismiss();
                if (needsAccess) access.run();
                else activity.startActivity(DeviceSetupActivity.createManualIntent(activity));
            });
        });
        dialog.setOnDismissListener(ignored -> DesktopSetupStatus.removeListener(render));
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> DesktopSetupStatus.refresh(activity));
        DesktopSetupStatus.addListener(render);
        DesktopSetupStatus.refresh(activity);
    }

    static void showAccess(Activity activity, boolean canRequest, Runnable request, Runnable settings) {
        final var access = ShellAccess.currentSnapshot();
        final boolean pending = RuntimeLimits.restartRequired(activity);
        final StringBuilder message = new StringBuilder(activity.getString(
                R.string.control_runtime_status, access.accessLabel()));
        message.append('\n').append(activity.getString(R.string.control_access_backend, access.backend.label));
        if (access.isReady()) message.append('\n').append(activity.getString(R.string.control_access_uid, access.uid));
        message.append('\n').append(activity.getString(R.string.control_access_limit,
                activity.getString(RuntimeLimits.active().access().label)));
        if (!access.backend.usesRoot()) appendPackage(activity, message, IntegrationPackage.SHIZUKU);
        if (!access.error.isEmpty()) message.append("\n\n").append(access.error);
        if (pending) message.append("\n\n").append(activity.getString(R.string.access_restart_required));
        final var dialog = new AlertDialog.Builder(activity).setTitle(R.string.control_access_title)
                .setMessage(message).setNegativeButton(R.string.action_close, null)
                .setNeutralButton(R.string.control_integration_settings, (d, which) -> settings.run());
        if (canRequest && (pending || RuntimeLimits.active().privilegedAllowed() && !access.isReady())) dialog.setPositiveButton(
                pending ? R.string.action_exit : R.string.control_access_setup, (d, which) -> request.run());
        dialog.show();
    }

    static void showTermux(Activity activity, Runnable settings) {
        TermuxSetupDialog.show(activity, settings);
    }

    static void appendPackage(Activity activity, StringBuilder message, IntegrationPackage integration) {
        message.append('\n').append(activity.getString(R.string.control_integration_package, integration.selected()));
        final String configured = integration.configured(activity);
        if (!configured.equals(integration.selected())) message.append('\n').append(activity.getString(
                R.string.settings_integration_restart_pending, configured));
    }

    static void openAppSettings(Activity activity, String packageName) {
        activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)));
    }
}

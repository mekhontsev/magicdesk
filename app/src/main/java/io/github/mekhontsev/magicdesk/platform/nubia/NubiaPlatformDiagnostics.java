package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.CompatibilityDiagnostics;
import io.github.mekhontsev.magicdesk.ExternalDisplayController;
import io.github.mekhontsev.magicdesk.DesktopSelfTestCapabilityAudit;
import io.github.mekhontsev.magicdesk.DesktopSelfTestResult;
import io.github.mekhontsev.magicdesk.DisplayProfileController;
import io.github.mekhontsev.magicdesk.DisplayProfileStore;
import io.github.mekhontsev.magicdesk.PlatformDiagnostics;
import io.github.mekhontsev.magicdesk.ShellAccess;
import android.content.Context;
import android.provider.Settings;

import java.util.Map;

/** Capability and runtime diagnostics specific to RedMagic firmware. */
final class NubiaPlatformDiagnostics implements PlatformDiagnostics {
    @Override
    public void appendCapabilityProbe(
            final StringBuilder report,
            final Context context) {
        NubiaCapabilityProbe.appendTo(report, context);
    }

    @Override
    public void appendCompatibilityReport(
            final StringBuilder report,
            final Context context) {
        report.append("RedMagic charge separation: package=")
                .append(ChargeSeparationController.isSupported(context))
                .append(", enabled=")
                .append(Settings.Global.getInt(
                        context.getContentResolver(),
                        ChargeSeparationController.SETTING,
                        0) == 1)
                .append('\n');
        final RedmagicHardwareSnapshot hardware =
                RedmagicHardwareController.snapshot();
        report.append("RedMagic hardware: fan=")
                .append(hardware.fanAvailable)
                .append(" enabled=").append(hardware.fanEnabled)
                .append(", pump=").append(hardware.pumpAvailable)
                .append(" enabled=").append(hardware.pumpEnabled)
                .append(" speed=").append(hardware.pumpSpeed)
                .append(", cpuMilliC=").append(hardware.cpuMilliCelsius)
                .append(", gpuMilliC=").append(hardware.gpuMilliCelsius)
                .append(", skinMilliC=").append(hardware.skinMilliCelsius)
                .append(", batteryMilliC=")
                .append(hardware.batteryMilliCelsius)
                .append('\n');
        final int physicalDisplayId =
                ExternalDisplayController.findExternalDisplayId();
        final DisplayProfileStore.Profile displayProfile =
                DisplayProfileController.prepareExternalProfile(
                        context, physicalDisplayId);
        report.append("External display profile: ");
        if (displayProfile == null) {
            report.append("not connected");
        } else {
            report.append("key=").append(displayProfile.key)
                    .append(", fill=").append(displayProfile.fillDisplay)
                    .append(", output=")
                    .append(displayProfile.outputTiming == null
                            ? "system" : displayProfile.outputTiming)
                    .append(", resetPending=")
                    .append(displayProfile.resetOutputModePending);
        }
        appendAndroidDisplayModeState(report, physicalDisplayId);
        report.append('\n')
                .append("Phone screen guard: active=")
                .append(PhoneDisplayGuard.isActive())
                .append(", protectedUids=")
                .append(PhoneDisplayGuard.protectedUidSummary())
                .append('\n')
                .append("Nubia physical output settings: fit=")
                .append(Settings.Global.getString(
                        context.getContentResolver(),
                        "app_mirror_fit_status"))
                .append(", sizeType=")
                .append(Settings.Global.getString(
                        context.getContentResolver(),
                        "app_mirror_size_type"))
                .append(", support=")
                .append(Settings.Global.getString(
                        context.getContentResolver(),
                        "nb_app_mirror_support_fit"))
                .append(", current=")
                .append(Settings.Global.getString(
                        context.getContentResolver(),
                        "nb_app_mirror_now_fit"))
                .append('\n');
    }

    private static void appendAndroidDisplayModeState(
            final StringBuilder report,
            final int displayId) {
        if (displayId <= 0) {
            return;
        }
        report.append('\n')
                .append("Android display mode state: user=")
                .append(runDisplayCommand(
                        "get-user-preferred-display-mode " + displayId))
                .append(", global=")
                .append(runDisplayCommand("get-user-preferred-display-mode"))
                .append(", connectedAt=")
                .append(runDisplayCommand(
                        "get-active-display-mode-at-start " + displayId));
    }

    private static String runDisplayCommand(final String arguments) {
        try {
            final ShellAccess.CommandResult result =
                    ShellAccess.executeCommand(
                            "/system/bin/cmd display " + arguments);
            final String output = result.output == null
                    ? "" : result.output.trim().replace('\n', ' ');
            return result.exitCode == 0
                    ? output : "error(" + result.exitCode + "):" + output;
        } catch (java.io.IOException | RuntimeException error) {
            final String message = error.getMessage();
            return "error:" + (message == null
                    ? error.getClass().getSimpleName() : message);
        }
    }

    @Override
    public void auditSelfTest(
            final Context context,
            final DesktopSelfTestResult result,
            final Map<String, DesktopSelfTestCapabilityAudit.ProbeEntry>
                    capabilities) {
        DesktopSelfTestCapabilityAudit.optional(
                result, capabilities,
                "vendor.display_command", "present",
                "API-NUBIA-001", "RedMagic display command signature");
        // Shell-restricted EDID access is reported by compatibility
        // diagnostics; it is not required by the desktop window workflow.
        DesktopSelfTestCapabilityAudit.optional(
                result, capabilities,
                "vendor.phone_screen", "present",
                "API-NUBIA-002", "RedMagic phone-screen trigger");
        DesktopSelfTestCapabilityAudit.optional(
                result, capabilities,
                "vendor.redmagic_app_manager", "present",
                "API-NUBIA-003", "RedMagic property service");
        DesktopSelfTestCapabilityAudit.optional(
                result, capabilities,
                "vendor.power", "present",
                "API-NUBIA-004", "RedMagic power service");
        DesktopSelfTestCapabilityAudit.optional(
                result, capabilities,
                "vendor.mouse_position", "present",
                "API-NUBIA-007", "MagicDesk Nubia desktop pointer backend");
        DesktopSelfTestCapabilityAudit.optional(
                result, capabilities,
                "vendor.mirror_text_input", "present",
                "API-NUBIA-009", "RedMagic mirrored text input API");
        DesktopSelfTestCapabilityAudit.runtimeCapability(
                result, capabilities,
                "runtime.mirror_text_input", "working", "failed",
                "API-NUBIA-010", "RedMagic mirrored text input runtime");
        DesktopSelfTestCapabilityAudit.optionalComponent(
                context, result,
                "cn.nubia.touping",
                "cn.nubia.touping.HomeActivity",
                "API-NUBIA-006", "RedMagic SmartCast entry point");
    }

}

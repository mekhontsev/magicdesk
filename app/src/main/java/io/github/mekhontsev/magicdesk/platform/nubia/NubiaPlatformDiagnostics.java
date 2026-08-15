package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.CompatibilityDiagnostics;
import io.github.mekhontsev.magicdesk.ConsoleDisplayController;
import io.github.mekhontsev.magicdesk.DesktopSelfTestCapabilityAudit;
import io.github.mekhontsev.magicdesk.DesktopSelfTestResult;
import io.github.mekhontsev.magicdesk.DisplayProfileController;
import io.github.mekhontsev.magicdesk.DisplayProfileStore;
import io.github.mekhontsev.magicdesk.PlatformDevice;
import io.github.mekhontsev.magicdesk.PlatformDiagnostics;
import io.github.mekhontsev.magicdesk.PlatformSupportLevel;

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
        CompatibilityDiagnostics.appendCheck(
                report,
                "NUBIA-INPUT-001",
                CompatibilityDiagnostics.hasPackage(
                        context, "cn.nubia.keymapcenter"),
                "Nubia mirror input package",
                "cn.nubia.keymapcenter");
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
        report.append("Console display setting: ")
                .append(Settings.Global.getString(
                        context.getContentResolver(),
                        ConsoleModeState.DISPLAY_ID_SETTING))
                .append('\n');
        final int physicalDisplayId =
                ConsoleDisplayController.findExternalDisplayId();
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
                            ? "system" : displayProfile.outputTiming);
        }
        report.append('\n')
                .append("Phone screen guard: active=")
                .append(PhoneDisplayGuard.isActive())
                .append(", protectedUids=")
                .append(PhoneDisplayGuard.protectedUidSummary())
                .append('\n')
                .append("Nubia projection settings: fit=")
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

    @Override
    public void auditSelfTest(
            final Context context,
            final DesktopSelfTestResult result,
            final Map<String, DesktopSelfTestCapabilityAudit.ProbeEntry>
                    capabilities) {
        DesktopSelfTestCapabilityAudit.optional(
                result, capabilities,
                "permission.set_activity_watcher", "granted",
                "API-NUBIA-012", "Phone launcher task interception");
        DesktopSelfTestCapabilityAudit.optional(
                result, capabilities,
                "vendor.display_command", "present",
                "API-NUBIA-001", "RedMagic display command signature");
        DesktopSelfTestCapabilityAudit.optional(
                result, capabilities,
                "vendor.hdmi_modes.read", "granted",
                "API-NUBIA-011", "Vendor HDMI output-mode list");
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
                "API-NUBIA-007", "RedMagic absolute pointer positioning");
        DesktopSelfTestCapabilityAudit.optional(
                result, capabilities,
                "vendor.mirror_panel", "present",
                "API-NUBIA-008", "RedMagic mirror input panel registration");
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

    @Override
    public String supportDetail(
            final PlatformDevice device,
            final PlatformSupportLevel supportLevel) {
        switch (supportLevel) {
            case MAINTAINER_VERIFIED:
                return "maintainer-verified RedMagic 11 Pro / NX809J / "
                        + "20260204.221845";
            case COMMUNITY_TESTED:
                if (device != null
                        && "NX741J".equalsIgnoreCase(device.model)) {
                    return "community-tested nubia Z80 Ultra / NX741J / "
                            + "20251229.234747";
                }
                return "community-tested RedMagic 11 Pro / NX809J-UN / "
                        + "20260625.022314";
            case UNVERIFIED:
            default:
                return "unverified model or firmware; capability probing "
                        + "is required";
        }
    }
}

package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Interprets the non-destructive shell capability probe for the self-test. */
final class DesktopSelfTestCapabilityAudit {
    private DesktopSelfTestCapabilityAudit() {
    }

    static boolean run(
            final Context context, final DesktopSelfTestResult result) {
        final String output;
        try {
            output = ShellAccess.probeCapabilities();
        } catch (IOException error) {
            result.add(DesktopSelfTestResult.State.FAIL,
                    "API-PROBE-001", "Inspect privileged capabilities",
                    usefulMessage(error));
            return false;
        }
        result.add(DesktopSelfTestResult.State.PASS,
                "API-PROBE-001", "Inspect privileged capabilities",
                "probe completed");
        final Map<String, ProbeEntry> capabilities = parse(output);

        boolean runnable = true;
        runnable &= required(result, capabilities,
                "permission.manage_activity_tasks", "granted",
                "API-TASKS-001", "Manage Android tasks");
        runnable &= required(result, capabilities,
                "permission.write_secure_settings", "granted",
                "API-SETTINGS-001", "Configure secure/global settings");
        runnable &= required(result, capabilities,
                "tasks.read", "granted",
                "API-TASKS-002", "Read task state");
        runnable &= required(result, capabilities,
                "tasks.listener", "granted",
                "API-TASKS-003", "Observe task changes");

        optional(result, capabilities,
                "input.inject", "granted",
                "API-INPUT-001", "Inject display-targeted input");
        optional(result, capabilities,
                "raw_input.read", "granted",
                "API-INPUT-002", "Read physical input events");
        result.add(DesktopSelfTestResult.State.NOT_TESTED,
                "API-INPUT-003", "Exclusive physical input capture",
                "tested only when an external input bridge starts");
        optional(result, capabilities,
                "input.uinput", "granted",
                "API-INPUT-004", "Create virtual input devices");
        optional(result, capabilities,
                "input.layout_write", "granted",
                "API-INPUT-005", "Update physical keyboard layouts");
        optional(result, capabilities,
                "permission.internal_system_window", "granted",
                "API-WINDOW-001", "Internal desktop windows");
        optional(result, capabilities,
                "permission.device_power", "granted",
                "API-POWER-001", "Phone display power control");
        optional(result, capabilities,
                "permission.status_bar", "granted",
                "API-SYSTEMUI-001", "System UI control");
        optional(result, capabilities,
                "permission.set_orientation", "granted",
                "API-DISPLAY-001", "Display orientation control");
        optional(result, capabilities,
                "permission.capture_video_output", "granted",
                "API-RECORDING-001", "Display video capture");
        optional(result, capabilities,
                "permission.read_frame_buffer", "granted",
                "API-RECORDING-002", "Display framebuffer capture");
        optional(result, capabilities,
                "permission.change_component_enabled_state", "granted",
                "API-LAUNCHER-001", "Launcher component recovery");
        optional(result, capabilities,
                "vendor.display_command", "present",
                "API-NUBIA-001", "RedMagic display command signature");
        optional(result, capabilities,
                "vendor.hdmi_modes.read", "granted",
                "API-NUBIA-011", "Vendor HDMI output-mode list");
        optional(result, capabilities,
                "vendor.phone_screen", "present",
                "API-NUBIA-002", "RedMagic phone-screen trigger");
        optional(result, capabilities,
                "vendor.redmagic_app_manager", "present",
                "API-NUBIA-003", "RedMagic property service");
        optional(result, capabilities,
                "vendor.power", "present",
                "API-NUBIA-004", "RedMagic power service");
        optional(result, capabilities,
                "vendor.mouse_position", "present",
                "API-NUBIA-007", "RedMagic absolute pointer positioning");
        optional(result, capabilities,
                "vendor.mirror_panel", "present",
                "API-NUBIA-008", "RedMagic mirror input panel registration");
        optional(result, capabilities,
                "vendor.mirror_text_input", "present",
                "API-NUBIA-009", "RedMagic mirrored text input API");
        optional(result, capabilities,
                "wallpaper.system", "available",
                "API-WALLPAPER-001", "Static system wallpaper image");
        mirrorTextInputRuntime(result, capabilities);

        final boolean nativeDesktopAvailable =
                NativeDesktopController.isAvailable();
        result.add(nativeDesktopAvailable
                        ? DesktopSelfTestResult.State.PASS
                        : DesktopSelfTestResult.State.WARN,
                "API-WMSHELL-001", "WMShell desktop command",
                nativeDesktopAvailable
                        ? "desktopmode passthrough available"
                        : "unavailable; direct WindowContainerTransaction path required");
        optionalComponent(context, result,
                "cn.nubia.touping",
                "cn.nubia.touping.HomeActivity",
                "API-NUBIA-006", "RedMagic SmartCast entry point");

        result.add(DesktopSelfTestResult.State.NOT_TESTED,
                "DEVICE-DP-001", "Physical DisplayPort and EDID",
                "not exercised by the simulated self-test");
        result.add(DesktopSelfTestResult.State.NOT_TESTED,
                "DEVICE-WIRELESS-001", "Miracast transport",
                "not exercised by the simulated self-test");
        result.add(DesktopSelfTestResult.State.NOT_TESTED,
                "DEVICE-INPUT-001", "Physical keyboard and mouse",
                "not exercised by the simulated self-test");
        result.add(DesktopSelfTestResult.State.NOT_TESTED,
                "DEVICE-TOUCHPANEL-001", "Phone Touch Panel input routing",
                "not exercised by the simulated self-test");
        return runnable;
    }

    private static String usefulMessage(final Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        final String message = current.getMessage();
        return message == null || message.isEmpty()
                ? current.getClass().getSimpleName() : message;
    }

    static Map<String, ProbeEntry> parse(final String output) {
        if (output == null || output.isEmpty()) {
            return Collections.emptyMap();
        }
        final Map<String, ProbeEntry> values = new LinkedHashMap<>();
        for (final String rawLine : output.split("\\r?\\n")) {
            final int equals = rawLine.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            final String key = rawLine.substring(0, equals).trim();
            final String value = rawLine.substring(equals + 1).trim();
            final int detailSeparator = value.indexOf(" | ");
            final String state = detailSeparator < 0
                    ? value : value.substring(0, detailSeparator).trim();
            final String detail = detailSeparator < 0
                    ? "" : value.substring(detailSeparator + 3).trim();
            if (!key.isEmpty() && !state.isEmpty()) {
                values.put(key, new ProbeEntry(state, detail));
            }
        }
        return values;
    }

    private static boolean required(
            final DesktopSelfTestResult result,
            final Map<String, ProbeEntry> capabilities,
            final String key,
            final String expected,
            final String code,
            final String label) {
        final ProbeEntry entry = capabilities.get(key);
        final boolean available = entry != null && expected.equals(entry.state);
        result.add(available ? DesktopSelfTestResult.State.PASS
                        : DesktopSelfTestResult.State.FAIL,
                code, label, detail(entry));
        return available;
    }

    private static void optional(
            final DesktopSelfTestResult result,
            final Map<String, ProbeEntry> capabilities,
            final String key,
            final String expected,
            final String code,
            final String label) {
        final ProbeEntry entry = capabilities.get(key);
        final boolean available = entry != null && expected.equals(entry.state);
        result.add(available ? DesktopSelfTestResult.State.PASS
                        : DesktopSelfTestResult.State.WARN,
                code, label, detail(entry));
    }

    private static String detail(final ProbeEntry entry) {
        if (entry == null) {
            return "probe entry missing";
        }
        return entry.state
                + (entry.detail.isEmpty() ? "" : " (" + entry.detail + ")");
    }

    private static void mirrorTextInputRuntime(
            final DesktopSelfTestResult result,
            final Map<String, ProbeEntry> capabilities) {
        final ProbeEntry entry = capabilities.get("runtime.mirror_text_input");
        result.add(classifyMirrorTextInputRuntime(entry),
                "API-NUBIA-010", "RedMagic mirrored text input runtime",
                detail(entry));
    }

    static DesktopSelfTestResult.State classifyMirrorTextInputRuntime(
            final ProbeEntry entry) {
        if (entry != null && "working".equals(entry.state)) {
            return DesktopSelfTestResult.State.PASS;
        }
        if (entry != null && "failed".equals(entry.state)) {
            return DesktopSelfTestResult.State.WARN;
        }
        return DesktopSelfTestResult.State.NOT_TESTED;
    }

    private static void optionalComponent(
            final Context context,
            final DesktopSelfTestResult result,
            final String packageName,
            final String className,
            final String code,
            final String label) {
        boolean available = false;
        String detail = packageName + "/" + className;
        try {
            context.getPackageManager().getActivityInfo(
                    new ComponentName(packageName, className),
                    PackageManager.ComponentInfoFlags.of(
                            PackageManager.MATCH_DISABLED_COMPONENTS));
            available = true;
        } catch (PackageManager.NameNotFoundException error) {
            detail += " missing";
        }
        result.add(available ? DesktopSelfTestResult.State.PASS
                        : DesktopSelfTestResult.State.WARN,
                code, label, detail);
    }

    static final class ProbeEntry {
        final String state;
        final String detail;

        ProbeEntry(final String state, final String detail) {
            this.state = state;
            this.detail = detail;
        }
    }
}

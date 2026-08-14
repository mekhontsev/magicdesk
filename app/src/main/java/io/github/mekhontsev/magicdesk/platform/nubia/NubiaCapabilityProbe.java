package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.PlatformTextInputDriver;
import io.github.mekhontsev.magicdesk.ShizukuCapabilityProbe;

import android.content.Context;
import android.os.Bundle;
import android.provider.Settings;
import android.system.OsConstants;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

/** Shell capability probes for optional Nubia/REDMAGIC firmware interfaces. */
final class NubiaCapabilityProbe {
    private static final int MAX_THERMAL_DETAIL_CHARS = 4_000;
    private static final String[] HARDWARE_SETTINGS = {
            "fan_state_of_manual",
            "fan_state_of_mode",
            "game_fan_off_on",
            "liquid_cooling_main_switch",
            "liquid_cooling_flow_speed_mode",
            "liquid_cooling_off_on"
    };
    private static final String[] HARDWARE_NODES = {
            "/sys/kernel/fan/fan_enable",
            "/sys/kernel/fan/fan_speed_level",
            "/sys/kernel/fan/fan_speed_count",
            "/proc/driver/micropump/enable",
            "/proc/driver/micropump/freq",
            "/proc/driver/micropump/speed"
    };

    private NubiaCapabilityProbe() {
    }

    static void appendTo(
            final StringBuilder report,
            final Context context) {
        ShizukuCapabilityProbe.appendMethodPresence(
                report,
                "vendor.display_command",
                "android.hardware.display.IDisplayManager",
                "setCmdToDisplay",
                int.class,
                int.class,
                int.class,
                Bundle.class);
        ShizukuCapabilityProbe.appendOpenResult(
                report,
                "vendor.hdmi_modes.read",
                new File(NubiaHdmiModeController.EDID_MODES),
                OsConstants.O_RDONLY);
        ShizukuCapabilityProbe.appendMethodPresence(
                report,
                "vendor.phone_screen",
                "com.redmagic.os.RedMagicAppManager$Trigger",
                "openScreenOffTP",
                boolean.class);
        appendMousePositionApi(report);
        appendMirrorInputApis(report);
        ShizukuCapabilityProbe.appendService(
                report,
                "vendor.redmagic_app_manager",
                "redmagic.app.manager");
        ShizukuCapabilityProbe.appendService(
                report, "vendor.color_light", "ColorfulLightService");
        ShizukuCapabilityProbe.appendService(
                report, "vendor.power", "VendorPowerManagerService");
        appendHardwareSettings(report, context);
        appendHardwareNodes(report);
        appendThermalZones(report);
    }

    private static void appendHardwareSettings(
            final StringBuilder report,
            final Context context) {
        final StringBuilder values = new StringBuilder();
        for (final String key : HARDWARE_SETTINGS) {
            if (context == null) {
                appendSetting(
                        report, values, "system", key,
                        null, "no service context");
                appendSetting(
                        report, values, "global", key,
                        null, "no service context");
                continue;
            }
            try {
                appendSetting(
                        report,
                        values,
                        "system",
                        key,
                        Settings.System.getString(
                                context.getContentResolver(), key),
                        null);
            } catch (RuntimeException error) {
                appendSetting(report, values, "system", key, null,
                        ShizukuCapabilityProbe.usefulMessage(error));
            }
            try {
                appendSetting(
                        report,
                        values,
                        "global",
                        key,
                        Settings.Global.getString(
                                context.getContentResolver(), key),
                        null);
            } catch (RuntimeException error) {
                appendSetting(report, values, "global", key, null,
                        ShizukuCapabilityProbe.usefulMessage(error));
            }
        }
        appendHardwareNamespace(
                report,
                "fan",
                RedmagicSettingsNamespace.select(
                        values.toString(),
                        HARDWARE_SETTINGS[0],
                        HARDWARE_SETTINGS[1]));
        appendHardwareNamespace(
                report,
                "pump",
                RedmagicSettingsNamespace.select(
                        values.toString(),
                        HARDWARE_SETTINGS[3],
                        HARDWARE_SETTINGS[4]));
    }

    private static void appendSetting(
            final StringBuilder report,
            final StringBuilder values,
            final String namespace,
            final String key,
            final String value,
            final String error) {
        ShizukuCapabilityProbe.append(
                report,
                "hardware.setting." + namespace + "." + key,
                error == null
                        ? (value == null ? "absent" : "present")
                        : "error",
                error == null ? value : error);
        if (error == null) {
            values.append("setting.")
                    .append(namespace).append('.').append(key).append('=')
                    .append(value == null ? "null" : value)
                    .append('\n');
        }
    }

    private static void appendHardwareNamespace(
            final StringBuilder report,
            final String group,
            final RedmagicSettingsNamespace namespace) {
        ShizukuCapabilityProbe.append(
                report,
                "hardware.settings." + group,
                namespace == null ? "unresolved" : namespace.shellName,
                "");
    }

    private static void appendMousePositionApi(
            final StringBuilder report) {
        try {
            final Class<?> inputManager = Class.forName(
                    "android.hardware.input.IInputManager");
            inputManager.getMethod(
                    "getMousePosition", android.graphics.Point.class);
            inputManager.getMethod(
                    "setMousePosition", int.class, int.class);
            inputManager.getMethod("sendMouseCmd", int.class);
            ShizukuCapabilityProbe.append(
                    report, "vendor.mouse_position", "present", "");
        } catch (ReflectiveOperationException | RuntimeException error) {
            ShizukuCapabilityProbe.append(
                    report,
                    "vendor.mouse_position",
                    "missing",
                    ShizukuCapabilityProbe.usefulMessage(error));
        }
    }

    private static void appendMirrorInputApis(
            final StringBuilder report) {
        try {
            new NubiaInputRoutingDriver().verifyApi();
            ShizukuCapabilityProbe.append(
                    report,
                    "vendor.mirror_panel",
                    "present",
                    "IDisplayManager#noteMirrorInputPanelStatus");
        } catch (ReflectiveOperationException | RuntimeException error) {
            ShizukuCapabilityProbe.append(
                    report,
                    "vendor.mirror_panel",
                    "missing",
                    ShizukuCapabilityProbe.usefulMessage(error));
        }

        try {
            NubiaMirrorTextInputDriver.INSTANCE.verifyApi();
            ShizukuCapabilityProbe.append(
                    report,
                    "vendor.mirror_text_input",
                    "present",
                    "IDisplayManager and IDisplayMirrorWindow signatures");
        } catch (ReflectiveOperationException | RuntimeException error) {
            ShizukuCapabilityProbe.append(
                    report,
                    "vendor.mirror_text_input",
                    "missing",
                    ShizukuCapabilityProbe.usefulMessage(error));
        }

        final PlatformTextInputDriver.RuntimeState runtime =
                NubiaMirrorTextInputDriver.INSTANCE.runtimeState();
        ShizukuCapabilityProbe.append(
                report,
                "runtime.mirror_text_input",
                runtime.state,
                runtime.detail);
    }

    private static void appendHardwareNodes(final StringBuilder report) {
        int present = 0;
        boolean readable = false;
        boolean writable = false;
        for (final String path : HARDWARE_NODES) {
            final File file = new File(path);
            if (file.exists()) {
                present++;
            }
            readable |= file.canRead();
            writable |= file.canWrite();
        }
        ShizukuCapabilityProbe.append(
                report,
                "hardware.nodes.present",
                Integer.toString(present),
                "expected=" + HARDWARE_NODES.length);
        ShizukuCapabilityProbe.append(
                report,
                "hardware.nodes.read",
                readable ? "granted" : "denied",
                "");
        ShizukuCapabilityProbe.append(
                report,
                "hardware.nodes.write",
                writable ? "granted" : "denied",
                "");
    }

    private static void appendThermalZones(final StringBuilder report) {
        final File directory = new File("/sys/class/thermal");
        final File[] zones = directory.listFiles(
                (parent, name) -> name.startsWith("thermal_zone"));
        if (zones == null || zones.length == 0) {
            ShizukuCapabilityProbe.append(
                    report, "hardware.thermal_zones", "unavailable", "");
            return;
        }
        Arrays.sort(zones, Comparator.comparing(File::getName));
        final StringBuilder detail = new StringBuilder();
        int readable = 0;
        for (final File zone : zones) {
            final String type = readFirstLine(new File(zone, "type"));
            final String value = readFirstLine(new File(zone, "temp"));
            if (type == null || value == null) {
                continue;
            }
            final String entry = zone.getName()
                    + ':' + type + '=' + value;
            final int separatorLength = detail.length() == 0 ? 0 : 2;
            if (detail.length() + separatorLength + entry.length()
                    <= MAX_THERMAL_DETAIL_CHARS) {
                if (separatorLength > 0) {
                    detail.append(", ");
                }
                detail.append(entry);
            }
            readable++;
        }
        ShizukuCapabilityProbe.append(
                report,
                "hardware.thermal_zones",
                Integer.toString(readable),
                detail.toString());
    }

    private static String readFirstLine(final File file) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            return ShizukuCapabilityProbe.clean(reader.readLine());
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }
}

package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.BoundedProcessRunner;
import io.github.mekhontsev.magicdesk.PlatformTextInputDriver;
import io.github.mekhontsev.magicdesk.ShizukuCapabilityProbe;

import android.content.Context;
import android.os.Bundle;
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
    private static final long SETTINGS_READ_TIMEOUT_MILLIS = 2_000L;
    private static final int SETTINGS_READ_MAX_OUTPUT_BYTES = 16 * 1024;

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
        appendHardwareSettings(report);
        appendHardwareNodes(report);
        appendThermalZones(report);
    }

    private static void appendHardwareSettings(final StringBuilder report) {
        final RedmagicHardwareSettings.Snapshot settings;
        try {
            settings = RedmagicHardwareSettings.readAll(
                    NubiaCapabilityProbe::runCoolingSettingsRead);
        } catch (IOException | RuntimeException error) {
            RedmagicHardwareSettings.appendDiagnostics(
                    report,
                    null,
                    ShizukuCapabilityProbe.usefulMessage(error));
            return;
        }
        RedmagicHardwareSettings.appendDiagnostics(report, settings, null);
    }

    private static String runCoolingSettingsRead(final String command)
            throws IOException {
        Process process = null;
        try {
            process = new ProcessBuilder(
                    "/system/bin/sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            final BoundedProcessRunner.Result result =
                    BoundedProcessRunner.run(
                            process,
                            SETTINGS_READ_TIMEOUT_MILLIS,
                            SETTINGS_READ_MAX_OUTPUT_BYTES);
            if (result.exitCode != 0 || result.truncated) {
                throw new IOException(
                        "cooling settings read failed " + result.exitCode
                                + (result.truncated ? " (truncated)" : "")
                                + ": " + result.output.trim());
            }
            return result.output;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("cooling settings read interrupted", error);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
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
        for (final String path : NubiaHardwareNodes.PATHS) {
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
                "expected=" + NubiaHardwareNodes.PATHS.length);
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

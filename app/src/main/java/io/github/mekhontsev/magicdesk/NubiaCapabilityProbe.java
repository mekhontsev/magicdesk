package io.github.mekhontsev.magicdesk;

import android.os.Bundle;
import android.system.OsConstants;

import java.io.File;

/** Shell capability probes for optional ZTE/nubia firmware interfaces. */
final class NubiaCapabilityProbe {
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

    static void appendTo(final StringBuilder report) {
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
        appendHardwareNodes(report);
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
            DesktopInputRoutingSession.verifyMirrorPanelApi();
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
            DesktopMirrorTextInput.verifyApi();
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

        final DesktopMirrorTextInput.RuntimeState runtime =
                DesktopMirrorTextInput.runtimeState();
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
}

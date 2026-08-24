package io.github.mekhontsev.magicdesk.platform.nubia;

import java.io.File;

/** REDMAGIC cooling nodes shared by discovery and diagnostics. */
final class NubiaHardwareNodes {
    static final String[] PATHS = {
            "/sys/kernel/fan/fan_enable",
            "/sys/kernel/fan/fan_speed_level",
            "/sys/kernel/fan/fan_speed_count",
            "/proc/driver/micropump/enable",
            "/proc/driver/micropump/freq",
            "/proc/driver/micropump/speed"
    };

    private NubiaHardwareNodes() {
    }

    static boolean anyPresent() {
        for (final String path : PATHS) {
            if (new File(path).exists()) {
                return true;
            }
        }
        return false;
    }
}

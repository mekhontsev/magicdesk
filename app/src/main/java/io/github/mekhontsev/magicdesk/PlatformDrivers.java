package io.github.mekhontsev.magicdesk;

import io.github.mekhontsev.magicdesk.platform.android.GenericAndroidPlatformDriver;
import io.github.mekhontsev.magicdesk.platform.nubia.NubiaFirmwareDetector;
import io.github.mekhontsev.magicdesk.platform.nubia.NubiaPlatformDriver;

/** Selects one platform driver for the lifetime of the process. */
final class PlatformDrivers {
    private static final PlatformDriver NUBIA = new NubiaPlatformDriver();
    private static final PlatformDriver GENERIC =
            new GenericAndroidPlatformDriver();
    private static final PlatformDevice DEVICE = PlatformDevice.current();
    private static final boolean NUBIA_FIRMWARE_AVAILABLE =
            NubiaFirmwareDetector.isAvailable(DEVICE);
    private static final PlatformDriver CURRENT = resolve(
            DEVICE,
            BuildConfig.PLATFORM_OVERRIDE,
            NUBIA_FIRMWARE_AVAILABLE);

    private PlatformDrivers() {
    }

    static PlatformDriver current() {
        return CURRENT;
    }

    static PlatformDriver resolve(
            final PlatformDevice device,
            final String platformOverride,
            final boolean nubiaFirmwareAvailable) {
        if ("android".equals(platformOverride)) {
            return GENERIC;
        }
        if (nubiaFirmwareAvailable && NUBIA.supports(device)) {
            return NUBIA;
        }
        return GENERIC;
    }

    static String selectionDetail() {
        if (!BuildConfig.PLATFORM_OVERRIDE.isEmpty()) {
            return "debug override=" + BuildConfig.PLATFORM_OVERRIDE;
        }
        if (NUBIA.supports(DEVICE) && !NUBIA_FIRMWARE_AVAILABLE) {
            return "automatic; Nubia-family hardware with standard Android firmware";
        }
        return "automatic";
    }
}

package io.github.mekhontsev.magicdesk;

import io.github.mekhontsev.magicdesk.platform.android.GenericAndroidPlatformDriver;
import io.github.mekhontsev.magicdesk.platform.nubia.NubiaFirmwareDetector;
import io.github.mekhontsev.magicdesk.platform.nubia.NubiaPlatformDriver;

/** Selects one platform driver for the lifetime of the process. */
final class PlatformDrivers {
    private static final PlatformDriver GENERIC =
            new GenericAndroidPlatformDriver();
    private static final PlatformDevice DEVICE = PlatformDevice.current();
    private static final NubiaFirmwareDetector.Result NUBIA_CAPABILITIES =
            NubiaFirmwareDetector.detect(DEVICE);
    private static final PlatformDriver CURRENT = resolve(
            DEVICE,
            BuildConfig.PLATFORM_OVERRIDE,
            NUBIA_CAPABILITIES);

    private PlatformDrivers() {
    }

    static PlatformDriver current() {
        return CURRENT;
    }

    static PlatformDriver resolve(
            final PlatformDevice device,
            final String platformOverride,
            final boolean nubiaFirmwareAvailable) {
        return resolve(
                device,
                platformOverride,
                nubiaFirmwareAvailable
                        ? NubiaFirmwareDetector.complete(
                                "Nubia/REDMAGIC firmware fixture")
                        : NubiaFirmwareDetector.unavailable(
                                "Nubia firmware fixture unavailable"));
    }

    static PlatformDriver resolve(
            final PlatformDevice device,
            final String platformOverride,
            final NubiaFirmwareDetector.Result nubiaCapabilities) {
        if ("android".equals(platformOverride)) {
            return GENERIC;
        }
        final NubiaPlatformDriver nubia =
                new NubiaPlatformDriver(nubiaCapabilities);
        final PlatformMatch nubiaMatch = nubia.match(device);
        return ComposedPlatformDriver.compose(
                GENERIC, nubia, nubiaMatch);
    }

    static String selectionDetail() {
        if (!BuildConfig.PLATFORM_OVERRIDE.isEmpty()) {
            return "debug override=" + BuildConfig.PLATFORM_OVERRIDE;
        }
        return "automatic; " + CURRENT.selection().summary();
    }
}

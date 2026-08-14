package io.github.mekhontsev.magicdesk;

import io.github.mekhontsev.magicdesk.platform.android.GenericAndroidPlatformDriver;
import io.github.mekhontsev.magicdesk.platform.nubia.NubiaPlatformDriver;

/** Selects one platform driver for the lifetime of the process. */
final class PlatformDrivers {
    private static final PlatformDriver NUBIA = new NubiaPlatformDriver();
    private static final PlatformDriver GENERIC =
            new GenericAndroidPlatformDriver();
    private static final PlatformDriver CURRENT = resolve(
            PlatformDevice.current(), BuildConfig.PLATFORM_OVERRIDE);

    private PlatformDrivers() {
    }

    static PlatformDriver current() {
        return CURRENT;
    }

    static PlatformDriver resolve(final PlatformDevice device) {
        return resolve(device, "");
    }

    static PlatformDriver resolve(
            final PlatformDevice device,
            final String platformOverride) {
        if ("android".equals(platformOverride)) {
            return GENERIC;
        }
        if (NUBIA.supports(device)) {
            return NUBIA;
        }
        return GENERIC;
    }

    static String selectionDetail() {
        return BuildConfig.PLATFORM_OVERRIDE.isEmpty()
                ? "automatic"
                : "debug override=" + BuildConfig.PLATFORM_OVERRIDE;
    }
}

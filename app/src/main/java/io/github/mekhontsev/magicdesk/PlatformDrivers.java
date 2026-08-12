package io.github.mekhontsev.magicdesk;

/** Selects one platform driver for the lifetime of the process. */
final class PlatformDrivers {
    private static final PlatformDriver NUBIA = new NubiaPlatformDriver();
    private static final PlatformDriver GENERIC =
            new GenericAndroidPlatformDriver();
    private static final PlatformDriver CURRENT = resolve(
            PlatformDevice.current());

    private PlatformDrivers() {
    }

    static PlatformDriver current() {
        return CURRENT;
    }

    static PlatformDriver resolve(final PlatformDevice device) {
        if (NUBIA.supports(device)) {
            return NUBIA;
        }
        return GENERIC;
    }
}

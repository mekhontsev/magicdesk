package io.github.mekhontsev.magicdesk;

/** Density override carried by one task transition. */
final class DesktopTaskDensity {
    static final int UNCHANGED = -1;
    static final int INHERIT = 0;

    static final int MIN_DENSITY_DPI = 72;

    private DesktopTaskDensity() {
    }

    static int clamp(final int densityDpi) {
        return Math.max(MIN_DENSITY_DPI, densityDpi);
    }

    static boolean isValid(final int densityDpi) {
        return densityDpi == UNCHANGED
                || densityDpi == INHERIT
                || densityDpi >= MIN_DENSITY_DPI;
    }

    static void apply(
            final FrameworkWindowingApi windowing,
            final Object transaction,
            final Object token,
            final int densityDpi) throws ReflectiveOperationException {
        if (!isValid(densityDpi)) {
            throw new IllegalArgumentException(
                    "invalid task density override: " + densityDpi);
        }
        if (densityDpi == UNCHANGED) {
            return;
        }
        if (!windowing.supportsDensityOverride()) {
            if (densityDpi == INHERIT) {
                return;
            }
            throw new NoSuchMethodException(
                    "task density override is unavailable");
        }
        windowing.setDensityDpi(transaction, token, densityDpi);
    }
}

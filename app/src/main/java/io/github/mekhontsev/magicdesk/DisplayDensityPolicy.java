package io.github.mekhontsev.magicdesk;

final class DisplayDensityPolicy {
    static final int MIN_DPI = 96;
    static final int DPI_STEP = 4;

    private static final int TARGET_SHORT_SIDE_DP = 1080;
    private static final int BASE_DPI = 160;
    private static final int FALLBACK_EXTERNAL_DPI = 192;

    private DisplayDensityPolicy() {
    }

    static int recommendedExternalDpi(
            final int widthPixels,
            final int heightPixels,
            final int maximum) {
        if (widthPixels <= 0 || heightPixels <= 0) {
            return snapDpi(FALLBACK_EXTERNAL_DPI, maximum);
        }
        final int shortSidePixels = Math.min(widthPixels, heightPixels);
        final float proportional =
                shortSidePixels * BASE_DPI / (float) TARGET_SHORT_SIDE_DP;
        final int calculated =
                Math.round(proportional / DPI_STEP) * DPI_STEP;
        return snapDpi(calculated, maximum);
    }

    static int snapDpi(final int dpi, final int maximum) {
        final int safeMaximum = Math.max(MIN_DPI, maximum);
        final int clamped = Math.max(MIN_DPI, Math.min(safeMaximum, dpi));
        if (clamped == safeMaximum) {
            return safeMaximum;
        }
        final int snapped = MIN_DPI
                + Math.round((clamped - MIN_DPI) / (float) DPI_STEP)
                        * DPI_STEP;
        return Math.min(safeMaximum, snapped);
    }
}

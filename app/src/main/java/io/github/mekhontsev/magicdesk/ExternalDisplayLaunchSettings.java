package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.SharedPreferences;

final class ExternalDisplayLaunchSettings {
    static final int VENDOR_SIZE_UNCHANGED = -1;
    static final int VENDOR_SIZE_1080 = 0;
    static final int VENDOR_SIZE_1440 = 1;
    static final int VENDOR_SIZE_2160 = 2;

    private static final String PREFS = "magicdesk_external_display";
    private static final String PREF_FILL_DISPLAY = "fill_display";
    private static final String PREF_OUTPUT_MODE = "output_mode";

    enum OutputMode {
        NATIVE,
        SYSTEM,
        P1080,
        P1440,
        P2160
    }

    static final class Config {
        final boolean fillDisplay;
        final OutputMode outputMode;

        Config(final boolean fillDisplay, final OutputMode outputMode) {
            this.fillDisplay = fillDisplay;
            this.outputMode = outputMode == null ? OutputMode.NATIVE : outputMode;
        }
    }

    private ExternalDisplayLaunchSettings() {
    }

    static Config load(final Context context) {
        final SharedPreferences preferences = preferences(context);
        return new Config(
                preferences.getBoolean(PREF_FILL_DISPLAY, true),
                parseOutputMode(preferences.getString(
                        PREF_OUTPUT_MODE, OutputMode.NATIVE.name())));
    }

    static void setFillDisplay(final Context context, final boolean enabled) {
        preferences(context).edit()
                .putBoolean(PREF_FILL_DISPLAY, enabled)
                .apply();
    }

    static void setOutputMode(
            final Context context, final OutputMode outputMode) {
        preferences(context).edit()
                .putString(
                        PREF_OUTPUT_MODE,
                        (outputMode == null ? OutputMode.NATIVE : outputMode).name())
                .apply();
    }

    static int resolveVendorSizeType(
            final OutputMode outputMode,
            final int physicalWidth,
            final int physicalHeight) {
        final OutputMode safeMode = outputMode == null
                ? OutputMode.NATIVE : outputMode;
        switch (safeMode) {
            case SYSTEM:
                return VENDOR_SIZE_UNCHANGED;
            case P1080:
                return VENDOR_SIZE_1080;
            case P1440:
                return VENDOR_SIZE_1440;
            case P2160:
                return VENDOR_SIZE_2160;
            case NATIVE:
            default:
                final int shortSide = Math.min(physicalWidth, physicalHeight);
                if (shortSide == 1080) {
                    return VENDOR_SIZE_1080;
                }
                if (shortSide == 1440) {
                    return VENDOR_SIZE_1440;
                }
                if (shortSide == 2160) {
                    return VENDOR_SIZE_2160;
                }
                return VENDOR_SIZE_UNCHANGED;
        }
    }

    private static OutputMode parseOutputMode(final String stored) {
        try {
            return OutputMode.valueOf(stored);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return OutputMode.NATIVE;
        }
    }

    private static SharedPreferences preferences(final Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

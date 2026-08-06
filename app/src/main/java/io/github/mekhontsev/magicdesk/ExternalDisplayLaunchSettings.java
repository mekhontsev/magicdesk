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
    private static final String PREF_OUTPUT_TIMING = "output_timing";

    static final class Config {
        final boolean fillDisplay;
        final String outputTiming;

        Config(final boolean fillDisplay, final String outputTiming) {
            this.fillDisplay = fillDisplay;
            this.outputTiming = outputTiming;
        }
    }

    private ExternalDisplayLaunchSettings() {
    }

    static Config load(final Context context) {
        final SharedPreferences preferences = preferences(context);
        return new Config(
                preferences.getBoolean(PREF_FILL_DISPLAY, true),
                preferences.getString(PREF_OUTPUT_TIMING, null));
    }

    static void setFillDisplay(final Context context, final boolean enabled) {
        preferences(context).edit()
                .putBoolean(PREF_FILL_DISPLAY, enabled)
                .apply();
    }

    static void setOutputTiming(
            final Context context, final String outputTiming) {
        preferences(context).edit()
                .putString(PREF_OUTPUT_TIMING, outputTiming)
                .apply();
    }

    static int resolveVendorSizeType(
            final int physicalWidth,
            final int physicalHeight) {
        final int shortSide = Math.min(physicalWidth, physicalHeight);
        if (shortSide <= 0) {
            return VENDOR_SIZE_UNCHANGED;
        }
        if (shortSide >= 2160) {
            return VENDOR_SIZE_2160;
        }
        if (shortSide >= 1440) {
            return VENDOR_SIZE_1440;
        }
        return VENDOR_SIZE_1080;
    }

    private static SharedPreferences preferences(final Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

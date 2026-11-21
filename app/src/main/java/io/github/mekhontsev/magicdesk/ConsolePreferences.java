package io.github.mekhontsev.magicdesk;

import android.content.Context;

/** Local console presentation and the default executor for new sessions. */
final class ConsolePreferences {
    static final int DEFAULT_FONT_SIZE_SP = 14;
    static final int MIN_FONT_SIZE_SP = 8;
    static final int MAX_FONT_SIZE_SP = 40;

    static int fontSizeSp(final Context context) {
        return clampFontSize(context.getSharedPreferences("console", Context.MODE_PRIVATE)
                .getInt("font_size_sp", DEFAULT_FONT_SIZE_SP));
    }

    static void setFontSizeSp(final Context context, final int size) {
        context.getSharedPreferences("console", Context.MODE_PRIVATE).edit()
                .putInt("font_size_sp", clampFontSize(size)).apply();
    }

    static int clampFontSize(final int size) {
        return Math.max(MIN_FONT_SIZE_SP, Math.min(MAX_FONT_SIZE_SP, size));
    }

    static boolean volumeBlackScreen(Context context) {
        return context.getSharedPreferences("console", Context.MODE_PRIVATE)
                .getBoolean("volume_black_screen", false);
    }

    static void setVolumeBlackScreen(Context context, boolean enabled) {
        context.getSharedPreferences("console", Context.MODE_PRIVATE).edit()
                .putBoolean("volume_black_screen", enabled).apply();
    }

    static DesktopExecBackend selected(Context context) {
        return DesktopExecBackend.parse(context.getSharedPreferences("console", Context.MODE_PRIVATE)
                .getString("executor", "app"));
    }
    static void select(Context context, DesktopExecBackend backend) {
        context.getSharedPreferences("console", Context.MODE_PRIVATE).edit()
                .putString("executor", backend.wireName).apply();
    }
    private ConsolePreferences() { }
}

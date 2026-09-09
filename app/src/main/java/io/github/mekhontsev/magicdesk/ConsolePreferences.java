package io.github.mekhontsev.magicdesk;

import android.content.Context;

/** Defaults for new Console windows, independent of their PTY backend or Desktop. */
final class ConsolePreferences {
    static final int DEFAULT_FONT_SIZE_SP = 14;
    static final int MIN_FONT_SIZE_SP = 8;
    static final int MAX_FONT_SIZE_SP = 40;

    private ConsolePreferences() { }

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
}

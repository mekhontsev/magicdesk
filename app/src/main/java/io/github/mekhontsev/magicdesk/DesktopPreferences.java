package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class DesktopPreferences {
    static final int DEFAULT_DESKTOP_DPI = 192;

    private static final String PREFS = "magicdesk";
    private static final String PREF_DESKTOP_DPI = "desktop_dpi";
    private static final String PREF_PINNED_PACKAGES = "pinned_packages";
    private static final List<String> FAVORITE_PACKAGES =
            Collections.unmodifiableList(Arrays.asList(
                    "com.termux",
                    "com.android.chrome",
                    "org.telegram.messenger",
                    "com.google.android.gm",
                    "com.openai.chatgpt"));

    private DesktopPreferences() {
    }

    static List<String> favoritePackages() {
        return FAVORITE_PACKAGES;
    }

    static Set<String> legacyPinnedPackages(final Context context) {
        final SharedPreferences preferences = preferences(context);
        if (!preferences.contains(PREF_PINNED_PACKAGES)) {
            return new LinkedHashSet<>(FAVORITE_PACKAGES);
        }
        final Set<String> stored = preferences.getStringSet(
                PREF_PINNED_PACKAGES, Collections.<String>emptySet());
        return stored == null
                ? new LinkedHashSet<String>() : new LinkedHashSet<>(stored);
    }

    static void saveLegacyPinnedPackages(
            final Context context, final Set<String> packages) {
        preferences(context).edit()
                .putStringSet(PREF_PINNED_PACKAGES, packages)
                .apply();
    }

    static int legacyDesktopDpi(final Context context) {
        return preferences(context).getInt(
                PREF_DESKTOP_DPI, DEFAULT_DESKTOP_DPI);
    }

    static void saveLegacyDesktopDpi(final Context context, final int dpi) {
        preferences(context).edit()
                .putInt(PREF_DESKTOP_DPI, dpi)
                .apply();
    }

    private static SharedPreferences preferences(final Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

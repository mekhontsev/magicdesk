package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class DesktopPreferences {
    static final int SYSTEM_DESKTOP_DPI = 0;
    static final int DEFAULT_DESKTOP_DPI = 192;

    private static final String PREFS = "magicdesk";
    private static final String PREF_PINNED_PACKAGES = "pinned_packages";
    private static final String PREF_DESKTOP_SHORTCUTS = "desktop_shortcuts";
    private static final String PREF_RECENT_PACKAGES = "recent_packages";
    private static final int MAX_RECENT_PACKAGES = 24;
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

    static Set<String> taskbarPackages(final Context context) {
        final SharedPreferences preferences = preferences(context);
        if (!preferences.contains(PREF_PINNED_PACKAGES)) {
            return new LinkedHashSet<>(FAVORITE_PACKAGES);
        }
        final Set<String> stored = preferences.getStringSet(
                PREF_PINNED_PACKAGES, Collections.<String>emptySet());
        return stored == null
                ? new LinkedHashSet<String>() : new LinkedHashSet<>(stored);
    }

    static void saveTaskbarPackages(
            final Context context, final Set<String> packages) {
        preferences(context).edit()
                .putStringSet(PREF_PINNED_PACKAGES, packages)
                .apply();
    }

    static List<String> desktopShortcutPackages(final Context context) {
        final SharedPreferences preferences = preferences(context);
        if (!preferences.contains(PREF_DESKTOP_SHORTCUTS)) {
            return new ArrayList<>(FAVORITE_PACKAGES);
        }
        return decodePackages(preferences.getString(
                PREF_DESKTOP_SHORTCUTS, ""));
    }

    static void saveDesktopShortcutPackages(
            final Context context,
            final Collection<String> packages) {
        preferences(context).edit()
                .putString(PREF_DESKTOP_SHORTCUTS, encodePackages(packages))
                .apply();
    }

    static List<String> recentPackages(final Context context) {
        return decodeRecentPackages(preferences(context).getString(
                PREF_RECENT_PACKAGES, ""));
    }

    static synchronized boolean recordRecentPackage(
            final Context context,
            final String packageName) {
        if (context == null
                || !PackageNameValidator.isSafe(packageName)
                || context.getPackageName().equals(packageName)
                || context.getPackageManager()
                        .getLaunchIntentForPackage(packageName) == null) {
            return false;
        }
        final List<String> previous = recentPackages(context);
        final List<String> updated = updateRecentPackages(
                previous, packageName, MAX_RECENT_PACKAGES);
        if (updated.equals(previous)) {
            return false;
        }
        preferences(context).edit()
                .putString(PREF_RECENT_PACKAGES, encodePackages(updated))
                .apply();
        return true;
    }

    static List<String> updateRecentPackages(
            final List<String> previous,
            final String packageName,
            final int limit) {
        final List<String> updated = new ArrayList<>();
        if (packageName != null && packageName.length() > 0 && limit > 0) {
            updated.add(packageName);
        }
        if (previous != null) {
            for (final String candidate : previous) {
                if (updated.size() >= limit) {
                    break;
                }
                if (candidate != null
                        && candidate.length() > 0
                        && !updated.contains(candidate)) {
                    updated.add(candidate);
                }
            }
        }
        return updated;
    }

    private static String encodePackages(final Collection<String> packages) {
        final StringBuilder encoded = new StringBuilder();
        for (final String packageName : packages) {
            if (encoded.length() > 0) {
                encoded.append('\n');
            }
            encoded.append(packageName);
        }
        return encoded.toString();
    }

    private static List<String> decodeRecentPackages(final String encoded) {
        final List<String> packages = decodePackages(encoded);
        if (packages.size() > MAX_RECENT_PACKAGES) {
            return new ArrayList<>(packages.subList(0, MAX_RECENT_PACKAGES));
        }
        return packages;
    }

    private static List<String> decodePackages(final String encoded) {
        final List<String> packages = new ArrayList<>();
        if (encoded != null && encoded.length() > 0) {
            for (final String packageName : encoded.split("\\n")) {
                if (PackageNameValidator.isSafe(packageName)
                        && !packages.contains(packageName)) {
                    packages.add(packageName);
                }
            }
        }
        return packages;
    }

    private static SharedPreferences preferences(final Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

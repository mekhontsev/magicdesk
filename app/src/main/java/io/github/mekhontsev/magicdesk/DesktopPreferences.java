package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

final class DesktopPreferences {
    static final int SYSTEM_DESKTOP_DPI = 0;
    static final int DEFAULT_DESKTOP_DPI = 192;

    private static final String PREFS = "magicdesk";
    private static final String PREF_RECENT_PACKAGES = "recent_packages";
    private static final String PREF_ON_SCREEN_KEYBOARD_LOCATION =
            "on_screen_keyboard_location";
    private static final int MAX_RECENT_PACKAGES = 24;

    private DesktopPreferences() {
    }

    static List<String> taskbarPackages() {
        return DesktopStateStore.read(
                state -> new ArrayList<>(state.taskbarPackages),
                new ArrayList<>());
    }

    static void saveTaskbarPackages(
            final Collection<String> packages) {
        final List<String> stored = new ArrayList<>();
        if (packages != null) {
            for (final String packageName : packages) {
                if (PackageNameValidator.isSafe(packageName)
                        && !stored.contains(packageName)) {
                    stored.add(packageName);
                }
            }
        }
        DesktopStateStore.update(state -> {
            state.taskbarPackages.clear();
            state.taskbarPackages.addAll(stored);
        });
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

    static OnScreenKeyboardLocation onScreenKeyboardLocation(
            final Context context) {
        return OnScreenKeyboardLocation.fromStoredValue(
                preferences(context).getString(
                        PREF_ON_SCREEN_KEYBOARD_LOCATION,
                        OnScreenKeyboardLocation.PHONE.storedValue));
    }

    static void saveOnScreenKeyboardLocation(
            final Context context,
            final OnScreenKeyboardLocation location) {
        preferences(context).edit()
                .putString(
                        PREF_ON_SCREEN_KEYBOARD_LOCATION,
                        (location == null
                                ? OnScreenKeyboardLocation.PHONE : location)
                                .storedValue)
                .apply();
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

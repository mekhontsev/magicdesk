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

    static List<String> recentAppKeys(final Context context) {
        return recentAppKeys(context, StartMenuScope.DESKTOP);
    }

    static List<String> recentAppKeys(final Context context, final StartMenuScope scope) {
        return decodeRecentAppKeys(preferences(context).getString(
                scope.historyKey, ""));
    }

    static synchronized boolean recordRecentApp(
            final Context context,
            final String appKey) {
        return recordRecentApp(context, appKey, StartMenuScope.DESKTOP);
    }

    static synchronized boolean recordRecentApp(
            final Context context, final String appKey, final StartMenuScope scope) {
        if (context == null
                || !isLaunchableAppKey(context, appKey)) {
            return false;
        }
        final List<String> previous = recentAppKeys(context, scope);
        final List<String> updated = updateRecentAppKeys(
                previous, appKey, MAX_RECENT_PACKAGES);
        if (updated.equals(previous)) {
            return false;
        }
        preferences(context).edit()
                .putString(scope.historyKey, encodePackages(updated))
                .apply();
        return true;
    }

    static List<String> updateRecentAppKeys(
            final List<String> previous,
            final String appKey,
            final int limit) {
        final List<String> updated = new ArrayList<>();
        if (appKey != null && appKey.length() > 0 && limit > 0) {
            updated.add(appKey);
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

    private static List<String> decodeRecentAppKeys(final String encoded) {
        final List<String> appKeys = new ArrayList<>();
        if (encoded != null && encoded.length() > 0) {
            for (final String appKey : encoded.split("\\n")) {
                if ((PackageNameValidator.isSafe(appKey)
                        || BuiltInDesktopAppCatalog.isAppIdentityKey(appKey))
                        && !appKeys.contains(appKey)) {
                    appKeys.add(appKey);
                }
            }
        }
        if (appKeys.size() > MAX_RECENT_PACKAGES) {
            return new ArrayList<>(appKeys.subList(0, MAX_RECENT_PACKAGES));
        }
        return appKeys;
    }

    private static boolean isLaunchableAppKey(
            final Context context,
            final String appKey) {
        if (BuiltInDesktopAppCatalog.isAppIdentityKey(appKey)) {
            return true;
        }
        return PackageNameValidator.isSafe(appKey)
                && !context.getPackageName().equals(appKey)
                && context.getPackageManager()
                        .getLaunchIntentForPackage(appKey) != null;
    }

    private static SharedPreferences preferences(final Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}

package io.github.mekhontsev.magicdesk;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

final class DesktopPreferences {
    static final int SYSTEM_DESKTOP_DPI = 0;
    static final int DEFAULT_DESKTOP_DPI = 192;
    private static final int MAX_RECENT_APPS = 24;
    private static final String RECENT_APPS = "recent_apps";

    private DesktopPreferences() {
    }

    static List<AppReference> taskbarApps() {
        return DesktopStateStore.read(state -> new ArrayList<>(state.taskbarApps),
                new ArrayList<>());
    }

    static void saveTaskbarApps(final Collection<AppReference> apps) {
        final List<AppReference> stored = new ArrayList<>();
        if (apps != null) {
            for (final AppReference app : apps) {
                if (app != null && !stored.contains(app)
                        && BuiltInDesktopAppCatalog.isPinnable(app.launchTarget())) {
                    stored.add(app);
                }
            }
        }
        DesktopStateStore.update(state -> {
            state.taskbarApps.clear();
            state.taskbarApps.addAll(stored);
        });
    }

    static List<AppReference> recentApps(final Context context) {
        return decodeRecentApps(context.getSharedPreferences("magicdesk", Context.MODE_PRIVATE)
                .getString(RECENT_APPS, "[]"));
    }

    static synchronized boolean recordRecentApp(
            final Context context, final AppReference app) {
        if (app == null) {
            return false;
        }
        final List<AppReference> previous = recentApps(context);
        final List<AppReference> updated = updateRecentApps(previous, app, MAX_RECENT_APPS);
        if (updated.equals(previous)) {
            return false;
        }
        // History changes with task focus; keep disk I/O off the UI thread.
        context.getSharedPreferences("magicdesk", Context.MODE_PRIVATE).edit()
                .putString(RECENT_APPS, encodeRecentApps(updated)).apply();
        return true;
    }

    static String encodeRecentApps(final List<AppReference> apps) {
        final JSONArray encoded = new JSONArray();
        for (final AppReference app : apps) {
            encoded.put(app.persistentKey());
        }
        return encoded.toString();
    }

    static List<AppReference> decodeRecentApps(final String encoded) {
        final List<AppReference> apps = new ArrayList<>();
        try {
            final JSONArray values = new JSONArray(encoded);
            for (int index = 0; index < values.length() && apps.size() < MAX_RECENT_APPS; index++) {
                try {
                    final AppReference app = AppReference.fromPersistentKey(values.getString(index));
                    if (!apps.contains(app)) {
                        apps.add(app);
                    }
                } catch (IllegalArgumentException | JSONException ignored) {
                    // Unresolved entries must never become current-profile applications.
                }
            }
        } catch (JSONException ignored) {
            return apps;
        }
        return apps;
    }

    static List<AppReference> updateRecentApps(
            final List<AppReference> previous, final AppReference app, final int limit) {
        final List<AppReference> updated = new ArrayList<>();
        if (app != null && limit > 0) {
            updated.add(app);
        }
        if (previous != null) {
            for (final AppReference candidate : previous) {
                if (updated.size() >= limit) {
                    break;
                }
                if (candidate != null && !updated.contains(candidate)) {
                    updated.add(candidate);
                }
            }
        }
        return updated;
    }
}

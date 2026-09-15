package io.github.mekhontsev.magicdesk;

import android.content.SharedPreferences;

/** Private host identity; Android remains the authority for widget bindings. */
final class DesktopWidgetHostIds {
    private static final int FIRST_HOST_ID = 0x4d440000;
    private final SharedPreferences preferences;

    DesktopWidgetHostIds(SharedPreferences preferences) {
        this.preferences = preferences;
    }

    static String workspaceKey(long profileSerial, String displayUniqueId) {
        if (profileSerial < 0 || displayUniqueId == null || displayUniqueId.isBlank()) {
            throw new IllegalArgumentException("widget workspace identity is unavailable");
        }
        return profileSerial + ":" + displayUniqueId;
    }

    int getOrAllocate(String workspace) {
        synchronized (preferences) {
            final String key = "host:" + workspace;
            final int stored = preferences.getInt(key, 0);
            if (stored >= FIRST_HOST_ID) return stored;
            if (stored != 0) throw new IllegalStateException("invalid widget host identity");
            final int last = preferences.getInt("lastHostId", FIRST_HOST_ID - 1);
            if (last < FIRST_HOST_ID - 1 || last == Integer.MAX_VALUE) {
                throw new IllegalStateException("widget host IDs exhausted or invalid");
            }
            final int id = last + 1;
            if (!preferences.edit().putInt(key, id).putInt("lastHostId", id).commit()) {
                // commit() publishes to memory even on disk failure. No Android host
                // has used this ID yet; remove the tentative mapping before retry.
                preferences.edit().remove(key).putInt("lastHostId", last).apply();
                throw new IllegalStateException("could not save widget host identity");
            }
            return id;
        }
    }
}

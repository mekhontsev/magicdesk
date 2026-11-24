package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.SharedPreferences;

/** Selects the touchscreen route when a Wayland host borrows an output. */
final class WaylandTouchSettings {
    private static final String DIRECT = "direct";

    private WaylandTouchSettings() { }

    static boolean enabled(Context context) { return preferences(context).getBoolean(DIRECT, true); }

    static void save(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(DIRECT, enabled).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("wayland_touch", Context.MODE_PRIVATE);
    }
}

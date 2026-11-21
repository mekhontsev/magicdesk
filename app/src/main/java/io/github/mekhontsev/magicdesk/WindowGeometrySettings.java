package io.github.mekhontsev.magicdesk;

import android.content.Context;

/** Startup preference shared by app, command service and its helper processes. */
final class WindowGeometrySettings {
    private WindowGeometrySettings() { }
    static boolean enabled(Context context) {
        return context.getSharedPreferences("window_geometry", Context.MODE_PRIVATE)
                .getBoolean("enabled", true);
    }
    static boolean save(Context context, boolean enabled) {
        return context.getSharedPreferences("window_geometry", Context.MODE_PRIVATE)
                .edit().putBoolean("enabled", enabled).commit();
    }
}

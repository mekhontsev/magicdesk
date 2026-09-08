package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.SharedPreferences;

/** Remembers creation defaults, never a live display or session. */
final class VirtualDisplayPreferences {
    private VirtualDisplayPreferences() { }

    private static SharedPreferences store(final Context context) {
        return context.getSharedPreferences("virtual_display", Context.MODE_PRIVATE);
    }

    static VirtualDisplaySpec load(final Context context) {
        final SharedPreferences prefs = store(context);
        return new VirtualDisplaySpec(
                prefs.getInt("width", VirtualDisplaySpec.DEFAULT_WIDTH),
                prefs.getInt("height", VirtualDisplaySpec.DEFAULT_HEIGHT),
                prefs.getInt("density", VirtualDisplaySpec.DEFAULT_DPI));
    }

    static void save(final Context context, final VirtualDisplaySpec spec) {
        store(context).edit().putInt("width", spec.width).putInt("height", spec.height)
                .putInt("density", spec.densityDpi).apply();
    }
}

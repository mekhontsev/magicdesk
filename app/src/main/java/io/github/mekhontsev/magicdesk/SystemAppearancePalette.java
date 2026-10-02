package io.github.mekhontsev.magicdesk;

import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.res.Configuration;
import java.util.EnumMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Public Android colors are presentation inputs, never persisted theme values. */
final class SystemAppearancePalette implements ComponentCallbacks {
    private static SystemAppearancePalette sInstance;
    private final Context context;
    private final Runnable changed;
    private final WeakHashMap<ShellAppearance, ShellAppearance> cache = new WeakHashMap<>();
    private Map<UiColor, Integer> light, dark;

    private SystemAppearancePalette(Context context, Runnable changed) {
        this.context = context.getApplicationContext(); this.changed = changed;
        this.context.registerComponentCallbacks(this);
    }
    static synchronized void initialize(Context context, Runnable changed) {
        if (sInstance == null) sInstance = new SystemAppearancePalette(context, changed);
    }
    static synchronized ShellAppearance resolve(ShellAppearance definition) {
        if (sInstance == null || definition.palette().source() == ShellAppearance.ColorSource.FIXED) return definition;
        return sInstance.cache.computeIfAbsent(definition, sInstance::resolveTheme);
    }
    private ShellAppearance resolveTheme(ShellAppearance definition) {
        boolean night = switch (definition.palette().mode()) {
            case LIGHT -> false; case DARK -> true;
            case SYSTEM -> (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
        };
        if (night && dark == null) dark = read(true);
        if (!night && light == null) light = read(false);
        return definition.withPalette(definition.palette().resolve(night ? dark : light));
    }
    private Map<UiColor, Integer> read(boolean night) {
        var result = new EnumMap<UiColor, Integer>(UiColor.class);
        result.putAll(ShellAppearance.preset(night ? "dark" : "light").palette().colors());
        put(result, UiColor.BACKGROUND, night, android.R.color.system_surface_dark, android.R.color.system_surface_light);
        put(result, UiColor.PANEL, night, android.R.color.system_surface_container_dark, android.R.color.system_surface_container_light);
        put(result, UiColor.SURFACE, night, android.R.color.system_surface_container_dark, android.R.color.system_surface_container_light);
        put(result, UiColor.SURFACE_LOW, night, android.R.color.system_surface_container_low_dark, android.R.color.system_surface_container_low_light);
        put(result, UiColor.SURFACE_HIGH, night, android.R.color.system_surface_container_high_dark, android.R.color.system_surface_container_high_light);
        put(result, UiColor.HOVER, night, android.R.color.system_surface_container_highest_dark, android.R.color.system_surface_container_highest_light);
        put(result, UiColor.TEXT, night, android.R.color.system_on_surface_dark, android.R.color.system_on_surface_light);
        put(result, UiColor.MUTED, night, android.R.color.system_on_surface_variant_dark, android.R.color.system_on_surface_variant_light);
        put(result, UiColor.ACCENT, night, android.R.color.system_primary_dark, android.R.color.system_primary_light);
        put(result, UiColor.ON_ACCENT, night, android.R.color.system_on_primary_dark, android.R.color.system_on_primary_light);
        put(result, UiColor.ACCENT_CONTAINER, night, android.R.color.system_primary_container_dark, android.R.color.system_primary_container_light);
        put(result, UiColor.ON_ACCENT_CONTAINER, night, android.R.color.system_on_primary_container_dark, android.R.color.system_on_primary_container_light);
        put(result, UiColor.OUTLINE, night, android.R.color.system_outline_dark, android.R.color.system_outline_light);
        put(result, UiColor.DANGER, night, android.R.color.system_error_dark, android.R.color.system_error_light);
        return Map.copyOf(result);
    }
    private void put(EnumMap<UiColor, Integer> result, UiColor role, boolean night, int darkId, int lightId) {
        result.put(role, context.getColor(night ? darkId : lightId));
    }
    @Override public void onConfigurationChanged(Configuration configuration) {
        synchronized (SystemAppearancePalette.class) { cache.clear(); light = null; dark = null; }
        changed.run();
    }
    @Override public void onLowMemory() {
        synchronized (SystemAppearancePalette.class) { cache.clear(); }
    }
}

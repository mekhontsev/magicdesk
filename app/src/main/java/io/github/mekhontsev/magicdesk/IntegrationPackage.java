package io.github.mekhontsev.magicdesk;

import android.content.Context;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Package selection is separate from the compatible application's wire protocol. */
enum IntegrationPackage {
    SHIZUKU("shizukuManagerPackage", "moe.shizuku.privileged.api"),
    TERMUX("termuxPackage", "com.termux");

    static final int MAX_LENGTH = 255;
    private static final Pattern NAME = Pattern.compile(
            "[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");
    final String key;
    final String defaultPackage;

    IntegrationPackage(final String key, final String defaultPackage) {
        this.key = key;
        this.defaultPackage = defaultPackage;
    }

    String selected() {
        return active().get(this);
    }

    String configured(final Context context) {
        return context.getSharedPreferences("integration_packages", Context.MODE_PRIVATE)
                .getString(key, defaultPackage);
    }

    boolean save(final Context context, final String value) {
        return context.getSharedPreferences("integration_packages", Context.MODE_PRIVATE)
                .edit().putString(key, normalize(value)).commit();
    }

    static Map<IntegrationPackage, String> active() {
        return Active.PACKAGES;
    }

    private static final class Active {
        // Initialized once at application startup. Saving Settings affects only the next process.
        static final Map<IntegrationPackage, String> PACKAGES = load();

        private static Map<IntegrationPackage, String> load() {
            final Map<IntegrationPackage, String> configured = new EnumMap<>(IntegrationPackage.class);
            for (final IntegrationPackage integration : values()) {
                configured.put(integration, integration.configured(MagicDeskApplication.applicationContext()));
            }
            return snapshot(configured);
        }
    }

    static Map<IntegrationPackage, String> snapshot(final Map<IntegrationPackage, String> configured) {
        final Map<IntegrationPackage, String> result = new EnumMap<>(IntegrationPackage.class);
        for (final IntegrationPackage integration : values()) {
            result.put(integration, normalize(configured.getOrDefault(integration, integration.defaultPackage)));
        }
        return Map.copyOf(result);
    }

    static String normalize(final String value) {
        final String name = value == null ? "" : value.trim();
        if (name.length() > MAX_LENGTH || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid application package name");
        }
        return name;
    }
}

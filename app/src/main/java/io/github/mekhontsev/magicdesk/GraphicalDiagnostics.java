package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import io.github.mekhontsev.magicdesk.hosted.HostedRenderer;

/** Passive declarations and bounded observations from ordinary session startup. */
final class GraphicalDiagnostics {
    enum Protocol { X11, WAYLAND }
    private static final String PREFS = "graphics_diagnostics";

    private GraphicalDiagnostics() { }

    static void record(Context context, Protocol protocol, HostedRenderer requested, String description) {
        if (description == null || description.isBlank()) return;
        String key = protocol.name();
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(key + ".description", clean(description))
                    .putString(key + ".requested", requested.id)
                    .putString(key + ".app", BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
                    .putString(key + ".firmware", Build.FINGERPRINT)
                    .putLong(key + ".time", System.currentTimeMillis()).apply();
        } catch (RuntimeException failure) {
            Log.w("MagicDeskGraphics", "Could not retain renderer observation", failure);
        }
    }

    static void appendReport(StringBuilder report, Context context) {
        report.append("## Linux graphics (passive)\n")
                .append("Declarations are not a driver test; observations come from normal compositor startup, not guest Mesa.\n");
        try {
            FeatureInfo[] features = context.getPackageManager().getSystemAvailableFeatures();
            report.append("Android-declared Vulkan: version=")
                    .append(feature(features, PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, true))
                    .append(", level=").append(feature(features, PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL, false))
                    .append(", compute=").append(feature(features, PackageManager.FEATURE_VULKAN_HARDWARE_COMPUTE, false))
                    .append('\n');
        } catch (RuntimeException failure) {
            report.append("Android-declared Vulkan: unavailable (")
                    .append(failure.getClass().getSimpleName()).append(")\n");
        }
        try {
            var saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getAll();
            for (Protocol protocol : Protocol.values()) {
                String key = protocol.name();
                report.append(key).append(" last startup: ");
                if (!(saved.get(key + ".description") instanceof String description)) {
                    report.append("NOT_OBSERVED\n");
                    continue;
                }
                report.append(clean(description)).append(" | requested=").append(saved.get(key + ".requested"))
                        .append(" | recorded=").append(java.time.Instant.ofEpochMilli((Long) saved.get(key + ".time")))
                        .append(" | app=").append(saved.get(key + ".app"))
                        .append(" | currentFirmware=").append(Build.FINGERPRINT.equals(saved.get(key + ".firmware")))
                        .append('\n');
            }
        } catch (RuntimeException failure) {
            report.append("Renderer observations: unavailable (").append(failure.getClass().getSimpleName()).append(")\n");
        }
        report.append('\n');
    }

    static String feature(FeatureInfo[] features, String name, boolean version) {
        if (features == null) return "unknown";
        Integer found = null;
        for (FeatureInfo feature : features) {
            if (feature != null && name.equals(feature.name)
                    && (found == null || Integer.compareUnsigned(feature.version, found) > 0)) found = feature.version;
        }
        if (found == null) return "not declared";
        return version ? (found >>> 22) + "." + ((found >>> 12) & 1023) + "." + (found & 4095)
                : Integer.toUnsignedString(found);
    }

    static String clean(String text) {
        return BoundedText.prefix(text.replace('\n', ' ').replace('\r', ' ').replace('\u0000', ' ').trim(), 1024);
    }
}

package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.json.JSONException;
import org.json.JSONObject;

/** Installed package metadata, independent of transport readiness and server API version. */
record IntegrationPackageSnapshot(String key, String selectedPackage, String configuredPackages,
        String metadataState, String label, String versionName, Long versionCode, String error) {
    IntegrationPackageSnapshot {
        label = CompatibilityDiagnostics.cleanSingleLine(label, 256);
        versionName = CompatibilityDiagnostics.cleanSingleLine(versionName, 256);
        error = CompatibilityDiagnostics.cleanSingleLine(error, 500);
    }

    static IntegrationPackageSnapshot capture(final Context context, final IntegrationPackage integration) {
        return capture(context, integration.key, integration.selected(), integration.configured(context));
    }

    static IntegrationPackageSnapshot capture(final Context context, final String key,
            final String selectedPackage, final String configuredPackages) {
        try {
            final PackageManager packages = context.getPackageManager();
            final PackageInfo info = packages.getPackageInfo(selectedPackage,
                    PackageManager.PackageInfoFlags.of(0));
            String label = "";
            String error = "";
            try {
                if (info.applicationInfo != null) {
                    final CharSequence value = packages.getApplicationLabel(info.applicationInfo);
                    label = value == null ? "" : value.toString();
                }
            } catch (RuntimeException failure) {
                // An unavailable label must not hide the installed version.
                error = "label unavailable: " + failure.getClass().getSimpleName();
            }
            return new IntegrationPackageSnapshot(key, selectedPackage, configuredPackages,
                    "available", label, info.versionName, info.getLongVersionCode(), error);
        } catch (PackageManager.NameNotFoundException failure) {
            return new IntegrationPackageSnapshot(key, selectedPackage, configuredPackages,
                    "not-installed-or-not-visible", "", "", null, "");
        } catch (RuntimeException failure) {
            return new IntegrationPackageSnapshot(key, selectedPackage, configuredPackages,
                    "unavailable", "", "", null, failure.getClass().getSimpleName());
        }
    }

    void appendReport(final StringBuilder report) {
        report.append("Integration ").append(key)
                .append(": active=").append(selectedPackage)
                .append(", configured=").append(configuredPackages)
                .append(", installedMetadata=").append(metadataState);
        if (versionCode != null) {
            report.append(", label=").append(label.isEmpty() ? "unavailable" : label)
                    .append(", versionName=").append(versionName.isEmpty() ? "unavailable" : versionName)
                    .append(", versionCode=").append(versionCode);
        }
        if (!error.isEmpty()) report.append(", error=").append(error);
        report.append('\n');
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("selectedPackage", selectedPackage)
                .put("configuredPackages", configuredPackages)
                .put("installedMetadata", metadataState)
                .put("label", label)
                .put("versionName", versionName)
                .put("versionCode", versionCode == null ? JSONObject.NULL : versionCode)
                .put("error", error);
    }
}

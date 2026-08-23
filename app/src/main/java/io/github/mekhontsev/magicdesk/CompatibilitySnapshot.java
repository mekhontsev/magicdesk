package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Structured core of a compatibility report. */
final class CompatibilitySnapshot {
    static final int SCHEMA_VERSION = 2;

    final PlatformDevice device;
    final PlatformDriver platform;
    final FirmwareProfileCatalog.Entry firmwareProfile;
    final PlatformCapabilitySnapshot capabilities;
    final JSONObject onboarding;
    final long selfTestLastModifiedMillis;

    private CompatibilitySnapshot(
            final PlatformDevice device,
            final PlatformDriver platform,
            final FirmwareProfileCatalog.Entry firmwareProfile,
            final PlatformCapabilitySnapshot capabilities,
            final JSONObject onboarding,
            final long selfTestLastModifiedMillis) {
        this.device = device;
        this.platform = platform;
        this.firmwareProfile = firmwareProfile;
        this.capabilities = capabilities;
        this.onboarding = onboarding;
        this.selfTestLastModifiedMillis = selfTestLastModifiedMillis;
    }

    static CompatibilitySnapshot capture(
            final Context context,
            final DeviceSetupManager.Audit audit) {
        return new CompatibilitySnapshot(
                PlatformDevice.current(),
                audit.platform,
                audit.firmwareProfile,
                PlatformCapabilitySnapshot.capture(audit.platform),
                onboardingJson(context),
                DesktopSelfTestResult.lastModifiedMillis(context));
    }

    void appendSelection(final StringBuilder report) {
        final PlatformSelection selection = platform.selection();
        report.append("## Platform composition\n")
                .append("Baseline: ").append(selection.baselineId()).append('\n')
                .append("Extension: ")
                .append(selection.extensionId().isEmpty()
                        ? "none" : selection.extensionId())
                .append('\n');
        if (!selection.extensionEvidence().isEmpty()) {
            report.append("Selection evidence: ")
                    .append(selection.extensionEvidence()).append('\n');
        }
        for (final PlatformComponent component
                : PlatformComponent.values()) {
            final PlatformSelection.Provider provider =
                    selection.provider(component);
            if (provider != null) {
                report.append("- ").append(component.wireName)
                        .append('=').append(provider.id)
                        .append(" | ").append(provider.evidence)
                        .append('\n');
            }
        }
        report.append("Capabilities:\n");
        for (final PlatformCapabilitySnapshot.Entry entry
                : capabilities.entries()) {
            report.append("- ").append(entry.id.wireName)
                    .append('=').append(entry.state.wireName)
                    .append(" | provider=").append(entry.providerId);
            if (!entry.detail.isEmpty()) {
                report.append(" | ").append(entry.detail);
            }
            report.append('\n');
        }
        report.append('\n');
    }

    String machineReadableJson() {
        try {
            final JSONObject root = new JSONObject()
                    .put("schemaVersion", SCHEMA_VERSION)
                    .put("device", deviceJson())
                    .put("platform", platformJson())
                    .put("capabilities",
                            capabilities.toJson().getJSONArray("capabilities"))
                    .put("windowTransitions",
                            DesktopWindowTransitionDiagnostics.toJson())
                    .put("onboarding", onboarding)
                    .put("selfTestLastModifiedMillis",
                            selfTestLastModifiedMillis);
            if (firmwareProfile != null) {
                root.put("firmwareProfile", profileJson());
            }
            return root.toString();
        } catch (JSONException error) {
            return errorJson(error);
        }
    }

    private static JSONObject onboardingJson(final Context context) {
        try {
            return CompatibilityOnboardingStore.toJson(context);
        } catch (JSONException error) {
            return new JSONObject();
        }
    }

    private static String errorJson(final JSONException error) {
        try {
            return new JSONObject()
                    .put("schemaVersion", SCHEMA_VERSION)
                    .put("error", error.getMessage())
                    .toString();
        } catch (JSONException ignored) {
            return "{\"schemaVersion\":2,\"error\":\"serialization failed\"}";
        }
    }

    private JSONObject deviceJson() throws JSONException {
        return new JSONObject()
                .put("manufacturer", device.manufacturer)
                .put("brand", device.brand)
                .put("model", device.model)
                .put("device", device.device)
                .put("product", device.product)
                .put("fingerprint", device.fingerprint)
                .put("sdk", device.sdkInt)
                .put("release", Build.VERSION.RELEASE)
                .put("securityPatch", Build.VERSION.SECURITY_PATCH);
    }

    private JSONObject platformJson() throws JSONException {
        final PlatformSelection selection = platform.selection();
        final JSONObject providers = new JSONObject();
        for (final PlatformComponent component
                : PlatformComponent.values()) {
            final PlatformSelection.Provider provider =
                    selection.provider(component);
            if (provider != null) {
                providers.put(component.wireName, new JSONObject()
                        .put("id", provider.id)
                        .put("evidence", provider.evidence));
            }
        }
        return new JSONObject()
                .put("id", platform.id())
                .put("name", platform.name())
                .put("baseline", selection.baselineId())
                .put("extension", selection.extensionId())
                .put("selectionEvidence", selection.extensionEvidence())
                .put("providers", providers);
    }

    private JSONObject profileJson() throws JSONException {
        return new JSONObject()
                .put("name", firmwareProfile.name)
                .put("support", firmwareProfile.supportLevel.name())
                .put("confirmedScope",
                        new JSONArray(firmwareProfile.confirmedScope))
                .put("limitations",
                        new JSONArray(firmwareProfile.limitations));
    }
}

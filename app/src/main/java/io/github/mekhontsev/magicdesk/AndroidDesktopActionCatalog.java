package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/** Stable semantic Android actions shared by UI and automation adapters. */
final class AndroidDesktopActionCatalog {
    private static final List<Entry> ENTRIES = List.of(
            new Entry(
                    "open-document",
                    "Open document",
                    "Open Android's document picker and retain returned URI grants.",
                    true,
                    new String[] {},
                    new String[] {"mimeType", "multiple"}),
            new Entry(
                    "create-document",
                    "Create document",
                    "Open Android's document creation surface and return its URI.",
                    true,
                    new String[] {},
                    new String[] {"mimeType", "suggestedName"}),
            new Entry(
                    "app-details",
                    "Application details",
                    "Open Android application details for a package.",
                    false,
                    new String[] {"package"},
                    new String[] {}),
            new Entry(
                    "notification-access",
                    "Notification access",
                    "Open Android notification-listener access settings.",
                    false,
                    new String[] {},
                    new String[] {"listenerComponent"}),
            new Entry(
                    "wireless-settings",
                    "Wireless settings",
                    "Open Android wireless settings.",
                    false,
                    new String[] {},
                    new String[] {}),
            new Entry(
                    "sound-settings",
                    "Sound settings",
                    "Open Android sound settings.",
                    false,
                    new String[] {},
                    new String[] {}));

    private AndroidDesktopActionCatalog() {
    }

    static JSONArray describe() {
        final JSONArray actions = new JSONArray();
        for (final Entry entry : ENTRIES) {
            actions.put(entry.toJson());
        }
        return actions;
    }

    static String[] ids() {
        final String[] ids = new String[ENTRIES.size()];
        for (int index = 0; index < ENTRIES.size(); index++) {
            ids[index] = ENTRIES.get(index).id;
        }
        return ids;
    }

    static AndroidDesktopAction create(
            final String id,
            final JSONObject args,
            final String source) throws JSONException {
        final JSONObject parameters = args == null ? new JSONObject() : args;
        final Intent intent;
        final boolean expectResult;
        final String name;
        switch (id == null ? "" : id.trim()) {
            case "open-document":
                intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(optionalString(
                                parameters, "mimeType", "*/*"))
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE,
                                parameters.optBoolean("multiple", false))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                expectResult = true;
                name = "Open document";
                break;
            case "create-document":
                intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(optionalString(
                                parameters,
                                "mimeType",
                                "application/octet-stream"))
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                final String suggestedName = optionalString(
                        parameters, "suggestedName", "");
                if (!suggestedName.isEmpty()) {
                    intent.putExtra(Intent.EXTRA_TITLE, suggestedName);
                }
                expectResult = true;
                name = "Create document";
                break;
            case "app-details":
                final String packageName = optionalString(
                        parameters, "package", "");
                if (!PackageNameValidator.isSafe(packageName)) {
                    throw new IllegalArgumentException(
                            "package must be an Android package name");
                }
                intent = new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", packageName, null));
                expectResult = false;
                name = "Application details";
                break;
            case "notification-access":
                final String listenerValue = optionalString(
                        parameters, "listenerComponent", "");
                final ComponentName listener = listenerValue.isEmpty()
                        ? null : ComponentName.unflattenFromString(
                                listenerValue);
                if (!listenerValue.isEmpty() && listener == null) {
                    throw new IllegalArgumentException(
                            "listenerComponent must be a flattened component");
                }
                intent = listener == null
                        ? new Intent(
                                Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        : new Intent(
                                Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                                .putExtra(
                                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                        listener.flattenToString());
                expectResult = false;
                name = "Notification access";
                break;
            case "wireless-settings":
                intent = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
                expectResult = false;
                name = "Wireless settings";
                break;
            case "sound-settings":
                intent = new Intent(Settings.ACTION_SOUND_SETTINGS);
                expectResult = false;
                name = "Sound settings";
                break;
            default:
                throw new IllegalArgumentException(
                        "unknown desktop action: " + id);
        }
        final DesktopTaskInstancePolicy defaultInstance = expectResult
                ? DesktopTaskInstancePolicy.CREATE_NEW
                : DesktopTaskInstancePolicy.REUSE_EXISTING;
        final AndroidIntegrationRequest request =
                AndroidIntegrationRequest.activity(
                        intent,
                        name,
                        AndroidIntegrationRequest.parsePresentation(
                                parameters, defaultInstance),
                        false,
                        "",
                        expectResult);
        return AndroidDesktopAction.request(id, source, request);
    }

    private static String optionalString(
            final JSONObject object,
            final String name,
            final String fallback) {
        final String value = object.optString(name, fallback);
        return value == null || value.trim().isEmpty()
                ? fallback : value.trim();
    }

    private static final class Entry {
        final String id;
        final String label;
        final String description;
        final boolean returnsActivityResult;
        final String[] requiredParameters;
        final String[] optionalParameters;

        Entry(
                final String id,
                final String label,
                final String description,
                final boolean returnsActivityResult,
                final String[] requiredParameters,
                final String[] optionalParameters) {
            this.id = id;
            this.label = label;
            this.description = description;
            this.returnsActivityResult = returnsActivityResult;
            this.requiredParameters = requiredParameters;
            this.optionalParameters = optionalParameters;
        }

        JSONObject toJson() {
            final JSONObject value = new JSONObject();
            try {
                value.put("id", id)
                        .put("label", label)
                        .put("description", description)
                        .put("returnsActivityResult", returnsActivityResult)
                        .put("requiredParameters",
                                new JSONArray(requiredParameters))
                        .put("optionalParameters",
                                new JSONArray(optionalParameters))
                        .put("presentationParameters", new JSONArray()
                                .put("displayId")
                                .put("mode")
                                .put("instance")
                                .put("preferredTaskId")
                                .put("bounds"));
            } catch (JSONException ignored) {
            }
            return value;
        }
    }
}

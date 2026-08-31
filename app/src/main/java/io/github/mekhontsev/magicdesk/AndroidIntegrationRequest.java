package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URISyntaxException;
import java.util.Iterator;

/** Immutable, transport-independent description of one Android integration call. */
final class AndroidIntegrationRequest {
    enum Kind {
        ACTIVITY("activity"),
        BROADCAST("broadcast"),
        SERVICE("service");

        final String wireName;

        Kind(final String wireName) {
            this.wireName = wireName;
        }

        static Kind parse(final String value) {
            for (final Kind kind : values()) {
                if (kind.wireName.equals(value)) {
                    return kind;
                }
            }
            throw new IllegalArgumentException(
                    "kind must be activity, broadcast, or service");
        }
    }

    private static final int MAX_EXTRAS = 64;
    private static final int MAX_CATEGORIES = 16;
    private static final int MAX_EXTRA_DEPTH = 8;
    private static final int MAX_ARRAY_VALUES = 256;
    private static final int MAX_REQUEST_CHARS = 262_144;

    final Kind kind;
    final Intent intent;
    final String name;
    final DesktopLaunchMode launchMode;
    final boolean chooser;
    final String chooserTitle;
    final boolean expectResult;
    final boolean foregroundService;

    private AndroidIntegrationRequest(
            final Kind kind,
            final Intent intent,
            final String name,
            final DesktopLaunchMode launchMode,
            final boolean chooser,
            final String chooserTitle,
            final boolean expectResult,
            final boolean foregroundService) {
        if (kind == null || intent == null) {
            throw new IllegalArgumentException("Android integration request is empty");
        }
        this.kind = kind;
        this.intent = new Intent(intent);
        this.name = clean(name, "Android action");
        this.launchMode = launchMode == null
                ? DesktopLaunchMode.AUTO : launchMode;
        this.chooser = chooser;
        this.chooserTitle = clean(chooserTitle, "");
        this.expectResult = expectResult;
        this.foregroundService = foregroundService;
    }

    static AndroidIntegrationRequest parse(
            final JSONObject value,
            final Kind defaultKind) throws JSONException {
        final JSONObject source = value == null ? new JSONObject() : value;
        if (source.toString().length() > MAX_REQUEST_CHARS) {
            throw new IllegalArgumentException(
                    "Android integration request is too large");
        }
        final Kind kind = Kind.parse(optionalString(
                source, "kind", defaultKind.wireName));
        final Intent intent = parseBaseIntent(optionalString(
                source, "intentUri", ""));

        final String action = optionalString(source, "action", "");
        if (!action.isEmpty()) {
            intent.setAction(action);
        }
        final String dataUri = optionalString(source, "dataUri", "");
        final String mimeType = optionalString(source, "mimeType", "");
        if (!dataUri.isEmpty() && !mimeType.isEmpty()) {
            intent.setDataAndType(Uri.parse(dataUri), mimeType);
        } else if (!dataUri.isEmpty()) {
            intent.setData(Uri.parse(dataUri));
        } else if (!mimeType.isEmpty()) {
            intent.setType(mimeType);
        }

        final String packageName = optionalString(source, "package", "");
        final String componentValue = optionalString(source, "component", "");
        if (!componentValue.isEmpty()) {
            final ComponentName component = ComponentName.unflattenFromString(
                    componentValue);
            if (component == null) {
                throw new IllegalArgumentException(
                        "component must be a flattened Android component");
            }
            if (!packageName.isEmpty()
                    && !packageName.equals(component.getPackageName())) {
                throw new IllegalArgumentException(
                        "component must belong to package");
            }
            intent.setComponent(component);
        } else if (!packageName.isEmpty()) {
            intent.setPackage(packageName);
        }

        final JSONArray categories = source.optJSONArray("categories");
        if (categories != null) {
            if (categories.length() > MAX_CATEGORIES) {
                throw new IllegalArgumentException("too many Intent categories");
            }
            for (int index = 0; index < categories.length(); index++) {
                intent.addCategory(requiredArrayString(
                        categories, index, "categories"));
            }
        }
        if (source.has("flags")) {
            intent.setFlags(requiredInt(source, "flags"));
        }
        final JSONArray flagNames = source.optJSONArray("flagNames");
        if (flagNames != null) {
            for (int index = 0; index < flagNames.length(); index++) {
                intent.addFlags(flag(requiredArrayString(
                        flagNames, index, "flagNames")));
            }
        }
        final JSONObject extras = source.optJSONObject("extras");
        if (extras != null) {
            putExtras(intent, extras);
        }

        final String name = optionalString(source, "name",
                packageName.isEmpty() ? "Android action" : packageName);
        final DesktopLaunchMode mode = parseLaunchMode(
                optionalString(source, "mode", "auto"));
        final boolean chooser = source.optBoolean("chooser", false);
        final boolean expectResult = source.optBoolean("expectResult", false);
        if (kind != Kind.ACTIVITY && (chooser || expectResult)) {
            throw new IllegalArgumentException(
                    "chooser and expectResult are only valid for activities");
        }
        return new AndroidIntegrationRequest(
                kind,
                intent,
                name,
                mode,
                chooser,
                optionalString(source, "chooserTitle", ""),
                expectResult,
                source.optBoolean("foreground", false));
    }

    static AndroidIntegrationRequest activity(
            final Intent intent,
            final String name,
            final DesktopLaunchMode mode,
            final boolean chooser,
            final String chooserTitle,
            final boolean expectResult) {
        return new AndroidIntegrationRequest(
                Kind.ACTIVITY,
                intent,
                name,
                mode,
                chooser,
                chooserTitle,
                expectResult,
                false);
    }

    private static Intent parseBaseIntent(final String intentUri) {
        if (intentUri.isEmpty()) {
            return new Intent();
        }
        try {
            return Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME);
        } catch (URISyntaxException | RuntimeException error) {
            throw new IllegalArgumentException("invalid Android Intent URI", error);
        }
    }

    private static void putExtras(
            final Intent intent,
            final JSONObject extras) throws JSONException {
        if (extras.length() > MAX_EXTRAS) {
            throw new IllegalArgumentException("too many Intent extras");
        }
        final Iterator<String> names = extras.keys();
        while (names.hasNext()) {
            final String name = names.next();
            putExtra(intent, name, extras.get(name));
        }
    }

    private static void putExtra(
            final Intent intent,
            final String name,
            final Object value) throws JSONException {
        if (value == null || value == JSONObject.NULL) {
            intent.putExtra(name, (String) null);
        } else if (value instanceof Boolean) {
            intent.putExtra(name, ((Boolean) value).booleanValue());
        } else if (value instanceof Integer) {
            intent.putExtra(name, ((Integer) value).intValue());
        } else if (value instanceof Long) {
            intent.putExtra(name, ((Long) value).longValue());
        } else if (value instanceof Number) {
            intent.putExtra(name, ((Number) value).doubleValue());
        } else if (value instanceof String) {
            intent.putExtra(name, (String) value);
        } else if (value instanceof JSONObject) {
            intent.putExtra(name, toBundle((JSONObject) value, 1));
        } else if (value instanceof JSONArray) {
            putArrayExtra(intent, name, (JSONArray) value);
        } else {
            throw new IllegalArgumentException(
                    "unsupported Intent extra type for " + name);
        }
    }

    private static Bundle toBundle(
            final JSONObject value,
            final int depth)
            throws JSONException {
        if (depth > MAX_EXTRA_DEPTH || value.length() > MAX_EXTRAS) {
            throw new IllegalArgumentException(
                    "Intent extras are too deeply nested or too large");
        }
        final Bundle bundle = new Bundle();
        final Iterator<String> names = value.keys();
        while (names.hasNext()) {
            final String name = names.next();
            final Object item = value.get(name);
            if (item == null || item == JSONObject.NULL) {
                bundle.putString(name, null);
            } else if (item instanceof Boolean) {
                bundle.putBoolean(name, ((Boolean) item).booleanValue());
            } else if (item instanceof Integer) {
                bundle.putInt(name, ((Integer) item).intValue());
            } else if (item instanceof Long) {
                bundle.putLong(name, ((Long) item).longValue());
            } else if (item instanceof Number) {
                bundle.putDouble(name, ((Number) item).doubleValue());
            } else if (item instanceof String) {
                bundle.putString(name, (String) item);
            } else if (item instanceof JSONObject) {
                bundle.putBundle(name, toBundle(
                        (JSONObject) item, depth + 1));
            } else {
                throw new IllegalArgumentException(
                        "nested Intent extras must be scalar objects");
            }
        }
        return bundle;
    }

    private static void putArrayExtra(
            final Intent intent,
            final String name,
            final JSONArray values) throws JSONException {
        if (values.length() > MAX_ARRAY_VALUES) {
            throw new IllegalArgumentException(
                    "Intent extra array is too large: " + name);
        }
        if (values.length() == 0) {
            intent.putExtra(name, new String[0]);
            return;
        }
        final Object first = values.get(0);
        if (first instanceof Boolean) {
            final boolean[] result = new boolean[values.length()];
            for (int index = 0; index < result.length; index++) {
                result[index] = values.getBoolean(index);
            }
            intent.putExtra(name, result);
        } else if (first instanceof Integer) {
            final int[] result = new int[values.length()];
            for (int index = 0; index < result.length; index++) {
                result[index] = values.getInt(index);
            }
            intent.putExtra(name, result);
        } else if (first instanceof Long) {
            final long[] result = new long[values.length()];
            for (int index = 0; index < result.length; index++) {
                result[index] = values.getLong(index);
            }
            intent.putExtra(name, result);
        } else if (first instanceof Number) {
            final double[] result = new double[values.length()];
            for (int index = 0; index < result.length; index++) {
                result[index] = values.getDouble(index);
            }
            intent.putExtra(name, result);
        } else {
            final String[] result = new String[values.length()];
            for (int index = 0; index < result.length; index++) {
                result[index] = values.getString(index);
            }
            intent.putExtra(name, result);
        }
    }

    private static DesktopLaunchMode parseLaunchMode(final String value) {
        for (final DesktopLaunchMode mode : DesktopLaunchMode.values()) {
            if (mode.wireName.equalsIgnoreCase(value)) {
                return mode;
            }
        }
        throw new IllegalArgumentException(
                "mode must be auto, windowed, or fullscreen");
    }

    private static int flag(final String value) {
        switch (value) {
            case "grant_read_uri":
                return Intent.FLAG_GRANT_READ_URI_PERMISSION;
            case "grant_write_uri":
                return Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            case "grant_persistable_uri":
                return Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
            case "grant_prefix_uri":
                return Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
            case "new_task":
                return Intent.FLAG_ACTIVITY_NEW_TASK;
            case "clear_top":
                return Intent.FLAG_ACTIVITY_CLEAR_TOP;
            case "single_top":
                return Intent.FLAG_ACTIVITY_SINGLE_TOP;
            case "new_document":
                return Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
            case "multiple_task":
                return Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
            case "reorder_to_front":
                return Intent.FLAG_ACTIVITY_REORDER_TO_FRONT;
            case "clear_task":
                return Intent.FLAG_ACTIVITY_CLEAR_TASK;
            case "exclude_from_recents":
                return Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
            case "no_history":
                return Intent.FLAG_ACTIVITY_NO_HISTORY;
            default:
                throw new IllegalArgumentException("unknown Intent flag: " + value);
        }
    }

    private static String optionalString(
            final JSONObject object,
            final String name,
            final String fallback) {
        final String value = object.optString(name, fallback);
        return value == null ? fallback : value.trim();
    }

    private static String requiredArrayString(
            final JSONArray values,
            final int index,
            final String name) throws JSONException {
        final Object value = values.get(index);
        if (!(value instanceof String)
                || ((String) value).trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must contain strings");
        }
        return ((String) value).trim();
    }

    private static int requiredInt(
            final JSONObject object,
            final String name) throws JSONException {
        final Object value = object.get(name);
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        final long number = ((Number) value).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " is out of range");
        }
        return (int) number;
    }

    private static String clean(final String value, final String fallback) {
        return value == null || value.trim().isEmpty()
                ? fallback : value.trim();
    }
}

package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class WorkspaceProfileStore {
    private static final String TAG = "MagicDeskProfiles";
    private static final String PREFS = "magicdesk_workspace_profiles";
    private static final int VERSION = 2;

    private WorkspaceProfileStore() {
    }

    static Profile load(final Context context, final String monitorKey,
            final int defaultDpi,
            final Collection<String> defaultTaskbarPackages,
            final Collection<String> defaultDesktopPackages) {
        final String stored = preferences(context).getString(storageKey(monitorKey), null);
        if (stored != null) {
            try {
                final Profile profile = fromJson(new JSONObject(stored));
                if (monitorKey.equals(profile.monitorKey)) {
                    return profile;
                }
            } catch (JSONException | RuntimeException e) {
                Log.w(TAG, "Cannot read profile for " + monitorKey, e);
            }
        }

        final Profile profile = new Profile(monitorKey);
        profile.dpi = defaultDpi;
        profile.dpiExplicit = false;
        addDistinct(profile.taskbarPackages, defaultTaskbarPackages);
        addDistinct(profile.desktopPackages, defaultDesktopPackages);
        save(context, profile);
        return profile;
    }

    static void save(final Context context, final Profile profile) {
        if (profile == null || profile.monitorKey == null
                || profile.monitorKey.length() == 0) {
            return;
        }
        try {
            preferences(context).edit()
                    .putString(storageKey(profile.monitorKey), toJson(profile).toString())
                    .apply();
        } catch (JSONException | RuntimeException e) {
            Log.w(TAG, "Cannot save profile for " + profile.monitorKey, e);
        }
    }

    static boolean exists(final Context context, final String monitorKey) {
        return preferences(context).contains(storageKey(monitorKey));
    }

    static String resolveMonitorAlias(final Context context, final String displayKey) {
        if (displayKey == null || displayKey.length() == 0) {
            return displayKey;
        }
        return preferences(context).getString(aliasKey(displayKey), displayKey);
    }

    static void saveMonitorAlias(final Context context, final String displayKey,
            final String monitorKey) {
        if (displayKey == null || displayKey.length() == 0
                || monitorKey == null || monitorKey.length() == 0) {
            return;
        }
        preferences(context).edit()
                .putString(aliasKey(displayKey), monitorKey)
                .apply();
    }

    private static SharedPreferences preferences(final Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String storageKey(final String monitorKey) {
        return "profile_" + encodedKey(monitorKey);
    }

    private static String aliasKey(final String displayKey) {
        return "alias_" + encodedKey(displayKey);
    }

    private static String encodedKey(final String value) {
        return Base64.encodeToString(
                value.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static JSONObject toJson(final Profile profile) throws JSONException {
        final JSONObject json = new JSONObject();
        json.put("version", VERSION);
        json.put("monitor", profile.monitorKey);
        json.put("dpi", profile.dpi);
        json.put("dpiExplicit", profile.dpiExplicit);
        json.put("taskbar", toJsonArray(profile.taskbarPackages));
        json.put("desktop", toJsonArray(profile.desktopPackages));
        if (profile.folderUri != null && profile.folderUri.length() > 0) {
            json.put("folderUri", profile.folderUri);
        }
        if (profile.workspacePackage != null && profile.workspacePackage.length() > 0) {
            json.put("workspacePackage", profile.workspacePackage);
        }
        if (profile.workspaceBounds != null && !profile.workspaceBounds.isEmpty()) {
            final JSONArray bounds = new JSONArray();
            bounds.put(profile.workspaceBounds.left);
            bounds.put(profile.workspaceBounds.top);
            bounds.put(profile.workspaceBounds.right);
            bounds.put(profile.workspaceBounds.bottom);
            json.put("workspaceBounds", bounds);
        }
        return json;
    }

    private static Profile fromJson(final JSONObject json) throws JSONException {
        final Profile profile = new Profile(json.getString("monitor"));
        profile.dpi = json.optInt("dpi", 192);
        profile.dpiExplicit = json.optBoolean("dpiExplicit", false);
        addDistinct(profile.taskbarPackages, fromJsonArray(json.optJSONArray("taskbar")));
        addDistinct(profile.desktopPackages, fromJsonArray(json.optJSONArray("desktop")));
        profile.folderUri = emptyToNull(json.optString("folderUri", null));
        profile.workspacePackage = emptyToNull(
                json.optString("workspacePackage", null));
        final JSONArray bounds = json.optJSONArray("workspaceBounds");
        if (bounds != null && bounds.length() == 4) {
            final Rect parsed = new Rect(bounds.optInt(0), bounds.optInt(1),
                    bounds.optInt(2), bounds.optInt(3));
            if (!parsed.isEmpty()) {
                profile.workspaceBounds = parsed;
            }
        }
        return profile;
    }

    private static JSONArray toJsonArray(final Collection<String> values) {
        final JSONArray array = new JSONArray();
        if (values != null) {
            for (final String value : values) {
                if (value != null && value.length() > 0) {
                    array.put(value);
                }
            }
        }
        return array;
    }

    private static List<String> fromJsonArray(final JSONArray array) {
        final List<String> values = new ArrayList<>();
        if (array == null) {
            return values;
        }
        for (int index = 0; index < array.length(); index++) {
            final String value = emptyToNull(array.optString(index, null));
            if (value != null && !values.contains(value)) {
                values.add(value);
            }
        }
        return values;
    }

    private static void addDistinct(final Collection<String> destination,
            final Collection<String> source) {
        if (source == null) {
            return;
        }
        for (final String value : source) {
            if (value != null && value.length() > 0 && !destination.contains(value)) {
                destination.add(value);
            }
        }
    }

    private static String emptyToNull(final String value) {
        return value == null || value.length() == 0 ? null : value;
    }

    static final class Profile {
        final String monitorKey;
        int dpi;
        boolean dpiExplicit;
        final Set<String> taskbarPackages = new LinkedHashSet<>();
        final List<String> desktopPackages = new ArrayList<>();
        String folderUri;
        String workspacePackage;
        Rect workspaceBounds = new Rect();

        Profile(final String monitorKey) {
            this.monitorKey = monitorKey;
        }
    }
}

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
import java.util.LinkedHashMap;
import java.util.Map;

final class DisplayProfileStore {
    private static final String TAG = "MagicDeskProfiles";
    private static final String PREFS = "magicdesk_display_profiles_v2";

    private DisplayProfileStore() {
    }

    static Profile load(final Context context, final String monitorKey,
            final int defaultDpi) {
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

    static void removePlacementEverywhere(
            final Context context, final String itemId) {
        updatePlacementEverywhere(context, itemId, null);
    }

    static void renamePlacementEverywhere(
            final Context context,
            final String previousItemId,
            final String newItemId) {
        if (newItemId == null || newItemId.length() == 0) {
            return;
        }
        updatePlacementEverywhere(context, previousItemId, newItemId);
    }

    private static void updatePlacementEverywhere(
            final Context context,
            final String previousItemId,
            final String newItemId) {
        if (previousItemId == null || previousItemId.length() == 0) {
            return;
        }
        final SharedPreferences preferences = preferences(context);
        final SharedPreferences.Editor editor = preferences.edit();
        boolean changed = false;
        for (final Map.Entry<String, ?> stored
                : preferences.getAll().entrySet()) {
            if (!stored.getKey().startsWith("profile_")
                    || !(stored.getValue() instanceof String)) {
                continue;
            }
            try {
                final Profile profile = fromJson(
                        new JSONObject((String) stored.getValue()));
                final DesktopPlacement placement =
                        profile.placements.remove(previousItemId);
                if (placement == null) {
                    continue;
                }
                if (newItemId != null) {
                    profile.placements.put(newItemId, placement);
                }
                editor.putString(
                        stored.getKey(), toJson(profile).toString());
                changed = true;
            } catch (JSONException | RuntimeException error) {
                Log.w(TAG, "Cannot update stored desktop placement", error);
            }
        }
        if (changed) {
            editor.apply();
        }
    }

    static boolean exists(final Context context, final String monitorKey) {
        return preferences(context).contains(storageKey(monitorKey));
    }

    static Integer readStoredDpi(
            final Context context, final String monitorKey) {
        final String stored =
                preferences(context).getString(storageKey(monitorKey), null);
        if (stored == null) {
            return null;
        }
        try {
            final Profile profile = fromJson(new JSONObject(stored));
            return monitorKey.equals(profile.monitorKey)
                    ? Integer.valueOf(profile.dpi) : null;
        } catch (JSONException | RuntimeException e) {
            Log.w(TAG, "Cannot read DPI for " + monitorKey, e);
            return null;
        }
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
        json.put("monitor", profile.monitorKey);
        json.put("dpi", profile.dpi);
        json.put("dpiExplicit", profile.dpiExplicit);
        if (profile.workspaceBounds != null && !profile.workspaceBounds.isEmpty()) {
            final JSONArray bounds = new JSONArray();
            bounds.put(profile.workspaceBounds.left);
            bounds.put(profile.workspaceBounds.top);
            bounds.put(profile.workspaceBounds.right);
            bounds.put(profile.workspaceBounds.bottom);
            json.put("workspaceBounds", bounds);
            if (profile.workspaceBoundsTarget != null) {
                json.put("workspaceBoundsTarget", profile.workspaceBoundsTarget);
            }
        }
        final JSONObject placements = new JSONObject();
        for (final Map.Entry<String, DesktopPlacement> entry
                : profile.placements.entrySet()) {
            final DesktopPlacement placement = entry.getValue();
            if (entry.getKey() == null || placement == null) {
                continue;
            }
            final JSONArray values = new JSONArray();
            values.put(placement.column);
            values.put(placement.row);
            values.put(placement.columnSpan);
            values.put(placement.rowSpan);
            placements.put(entry.getKey(), values);
        }
        json.put("placements", placements);
        return json;
    }

    private static Profile fromJson(final JSONObject json) throws JSONException {
        final Profile profile = new Profile(json.getString("monitor"));
        profile.dpi = json.optInt("dpi", 192);
        profile.dpiExplicit = json.optBoolean("dpiExplicit", false);
        final JSONArray bounds = json.optJSONArray("workspaceBounds");
        if (bounds != null && bounds.length() == 4) {
            final Rect parsed = new Rect(bounds.optInt(0), bounds.optInt(1),
                    bounds.optInt(2), bounds.optInt(3));
            if (!parsed.isEmpty()) {
                profile.workspaceBounds = parsed;
                final String boundsTarget = json.optString(
                        "workspaceBoundsTarget", "");
                profile.workspaceBoundsTarget = boundsTarget.length() == 0
                        ? null : boundsTarget;
            }
        }
        final JSONObject placements = json.optJSONObject("placements");
        if (placements != null) {
            final java.util.Iterator<String> keys = placements.keys();
            while (keys.hasNext()) {
                final String itemId = keys.next();
                final JSONArray values = placements.optJSONArray(itemId);
                final int column = values == null ? -1 : values.optInt(0, -1);
                final int row = values == null ? -1 : values.optInt(1, -1);
                if (itemId.length() == 0
                        || values == null
                        || values.length() != 4
                        || column < 0
                        || row < 0) {
                    continue;
                }
                profile.placements.put(itemId, new DesktopPlacement(
                        column,
                        row,
                        values.optInt(2, 1),
                        values.optInt(3, 1)));
            }
        }
        return profile;
    }

    static final class Profile {
        final String monitorKey;
        int dpi;
        boolean dpiExplicit;
        Rect workspaceBounds = new Rect();
        String workspaceBoundsTarget;
        final Map<String, DesktopPlacement> placements =
                new LinkedHashMap<>();

        Profile(final String monitorKey) {
            this.monitorKey = monitorKey;
        }
    }
}

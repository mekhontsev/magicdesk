package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class DesktopContentStore {
    private static final String TAG = "MagicDeskContent";
    private static final String PREFS = "magicdesk_desktop_content_v2";
    private static final String KEY_CONTENT = "content";

    private final Context mContext;
    private State mState;

    DesktopContentStore(final Context context) {
        mContext = context.getApplicationContext();
    }

    State get() {
        if (mState == null) {
            mState = load();
        }
        return mState;
    }

    void save() {
        try {
            preferences().edit()
                    .putString(KEY_CONTENT, toJson(get()).toString())
                    .apply();
        } catch (JSONException | RuntimeException error) {
            Log.w(TAG, "Cannot save desktop content", error);
        }
    }

    private State load() {
        final String encoded = preferences().getString(KEY_CONTENT, null);
        if (encoded == null || encoded.length() == 0) {
            return new State();
        }
        try {
            return fromJson(new JSONObject(encoded));
        } catch (JSONException | RuntimeException error) {
            Log.w(TAG, "Cannot read desktop content", error);
            return new State();
        }
    }

    private SharedPreferences preferences() {
        return mContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONObject toJson(final State state) throws JSONException {
        final JSONObject json = new JSONObject();
        final JSONArray shortcuts = new JSONArray();
        for (final AppLaunchTarget target : state.shortcuts) {
            shortcuts.put(targetToJson(target));
        }
        json.put("shortcuts", shortcuts);
        if (state.workspaceTarget != null) {
            json.put("workspaceTarget", targetToJson(state.workspaceTarget));
        }
        return json;
    }

    private static State fromJson(final JSONObject json) throws JSONException {
        final State state = new State();
        final JSONArray shortcuts = json.optJSONArray("shortcuts");
        if (shortcuts != null) {
            for (int index = 0; index < shortcuts.length(); index++) {
                final JSONObject value = shortcuts.optJSONObject(index);
                final AppLaunchTarget target = targetFromJson(value);
                if (target != null && !state.shortcuts.contains(target)) {
                    state.shortcuts.add(target);
                }
            }
        }
        state.workspaceTarget = targetFromJson(
                json.optJSONObject("workspaceTarget"));
        return state;
    }

    private static JSONObject targetToJson(final AppLaunchTarget target)
            throws JSONException {
        final JSONObject json = new JSONObject();
        json.put("package", target.packageName);
        if (target.activityClassName.length() > 0) {
            json.put("activity", target.activityClassName);
        }
        if (target.action.length() > 0) {
            json.put("action", target.action);
        }
        return json;
    }

    private static AppLaunchTarget targetFromJson(final JSONObject json) {
        if (json == null) {
            return null;
        }
        final String packageName = json.optString("package", "");
        final String activity = json.optString("activity", "");
        final String action = json.optString("action", "");
        try {
            return activity.length() == 0
                    ? AppLaunchTarget.packageDefault(packageName)
                    : AppLaunchTarget.explicit(packageName, activity, action);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    static final class State {
        final List<AppLaunchTarget> shortcuts = new ArrayList<>();
        AppLaunchTarget workspaceTarget;

        List<AppLaunchTarget> shortcutsView() {
            return Collections.unmodifiableList(shortcuts);
        }
    }
}

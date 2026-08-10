package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class DesktopStateStore {
    static final int FORMAT = 1;

    private static final String TAG = "MagicDeskState";
    private static final Object LOCK = new Object();
    private static final Storage SHELL_STORAGE = new Storage() {
        @Override
        public String read() throws IOException {
            return ShellAccess.readDesktopState();
        }

        @Override
        public void write(final String encoded) throws IOException {
            ShellAccess.writeDesktopState(encoded);
        }
    };

    private static State sState;
    private static String sEncoded;
    private static Storage sStorage = SHELL_STORAGE;

    private DesktopStateStore() {
    }

    interface Reader<T> {
        T read(State state);
    }

    interface Mutation {
        void apply(State state);
    }

    interface Storage {
        String read() throws IOException;

        void write(String encoded) throws IOException;
    }

    static <T> T read(final Reader<T> reader, final T unavailableValue) {
        synchronized (LOCK) {
            try {
                ensureLoadedLocked();
                return reader.read(snapshotLocked());
            } catch (IOException error) {
                report("Could not load desktop state", error);
                return unavailableValue;
            }
        }
    }

    static void load() throws IOException {
        synchronized (LOCK) {
            ensureLoadedLocked();
        }
    }

    static boolean update(final Mutation mutation) {
        synchronized (LOCK) {
            try {
                ensureLoadedLocked();
            } catch (IOException error) {
                report("Could not save desktop state", error);
                return false;
            }
            final String previous;
            try {
                previous = toJson(sState).toString();
            } catch (JSONException | RuntimeException error) {
                report("Could not encode desktop state", error);
                return false;
            }
            try {
                mutation.apply(sState);
            } catch (RuntimeException error) {
                restoreLocked(previous);
                report("Could not update desktop state", error);
                return false;
            }
            final String encoded;
            try {
                encoded = toJson(sState).toString();
            } catch (JSONException | RuntimeException error) {
                restoreLocked(previous);
                report("Could not encode desktop state", error);
                return false;
            }
            if (encoded.equals(previous)) {
                return true;
            }
            try {
                sStorage.write(encoded);
                sEncoded = encoded;
                return true;
            } catch (IOException | RuntimeException error) {
                restoreLocked(previous);
                report("Could not save desktop state", error);
                return false;
            }
        }
    }

    static ExternalSnapshot readExternal() {
        try {
            return new ExternalSnapshot(sStorage.read());
        } catch (IOException | RuntimeException error) {
            report("Could not reload desktop state", error);
            return null;
        }
    }

    static boolean applyExternal(final ExternalSnapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        synchronized (LOCK) {
            if (Objects.equals(snapshot.encoded, sEncoded)) {
                return false;
            }
            try {
                sState = decode(snapshot.encoded);
                sEncoded = snapshot.encoded;
                return true;
            } catch (JSONException | RuntimeException error) {
                report("Desktop state is invalid", error);
                return false;
            }
        }
    }

    static State decode(final String encoded) throws JSONException {
        if (encoded == null || encoded.trim().isEmpty()) {
            return new State();
        }
        final JSONObject root = new JSONObject(encoded);
        if (root.getInt("format") != FORMAT) {
            throw new JSONException("unsupported desktop state format");
        }
        final State state = new State();
        readTargets(root.optJSONArray("shortcuts"), state.content.shortcuts);
        state.content.workspaceTarget = targetFromJson(
                root.optJSONObject("workspaceTarget"));
        readPackages(root.optJSONArray("taskbar"), state.taskbarPackages);

        final JSONObject profiles = root.optJSONObject("displayProfiles");
        if (profiles != null) {
            final java.util.Iterator<String> keys = profiles.keys();
            while (keys.hasNext()) {
                final String key = keys.next();
                final JSONObject value = profiles.optJSONObject(key);
                final DisplayProfileStore.Profile profile =
                        profileFromJson(value);
                if (profile != null && key.equals(profile.monitorKey)) {
                    state.displayProfiles.put(key, profile);
                }
            }
        }

        final JSONObject aliases = root.optJSONObject("displayAliases");
        if (aliases != null) {
            final java.util.Iterator<String> keys = aliases.keys();
            while (keys.hasNext()) {
                final String displayKey = keys.next();
                final String monitorKey = aliases.optString(displayKey, "");
                if (!displayKey.isEmpty() && !monitorKey.isEmpty()) {
                    state.displayAliases.put(displayKey, monitorKey);
                }
            }
        }
        return state;
    }

    static String encode(final State state) throws JSONException {
        return toJson(state).toString();
    }

    private static void ensureLoadedLocked() throws IOException {
        if (sState != null) {
            return;
        }
        final String encoded = sStorage.read();
        try {
            final State state = decode(encoded);
            sEncoded = encoded;
            sState = state;
        } catch (JSONException | RuntimeException error) {
            report("Desktop state is invalid; using defaults", error);
            sState = new State();
            sEncoded = null;
        }
    }

    private static void restoreLocked(final String encoded) {
        try {
            sState = decode(encoded);
            sEncoded = encoded;
        } catch (JSONException | RuntimeException error) {
            report("Could not roll back desktop state", error);
            sState = null;
            sEncoded = null;
        }
    }

    private static State snapshotLocked() {
        final State snapshot = new State();
        snapshot.content.shortcuts.addAll(sState.content.shortcuts);
        snapshot.content.workspaceTarget = sState.content.workspaceTarget;
        snapshot.taskbarPackages.addAll(sState.taskbarPackages);
        for (final Map.Entry<String, DisplayProfileStore.Profile> entry
                : sState.displayProfiles.entrySet()) {
            snapshot.displayProfiles.put(
                    entry.getKey(), DisplayProfileStore.copy(entry.getValue()));
        }
        snapshot.displayAliases.putAll(sState.displayAliases);
        return snapshot;
    }

    static void useStorageForTests(final Storage storage) {
        synchronized (LOCK) {
            sStorage = storage == null ? SHELL_STORAGE : storage;
            sState = null;
            sEncoded = null;
        }
    }

    private static JSONObject toJson(final State state) throws JSONException {
        final JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("shortcuts", targetsToJson(state.content.shortcuts));
        if (state.content.workspaceTarget != null) {
            root.put(
                    "workspaceTarget",
                    targetToJson(state.content.workspaceTarget));
        }
        root.put("taskbar", stringsToJson(state.taskbarPackages));

        final JSONObject profiles = new JSONObject();
        for (final Map.Entry<String, DisplayProfileStore.Profile> entry
                : state.displayProfiles.entrySet()) {
            final DisplayProfileStore.Profile profile = entry.getValue();
            if (profile != null && entry.getKey().equals(profile.monitorKey)) {
                profiles.put(entry.getKey(), profileToJson(profile));
            }
        }
        root.put("displayProfiles", profiles);

        final JSONObject aliases = new JSONObject();
        for (final Map.Entry<String, String> entry
                : state.displayAliases.entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isEmpty()
                    && entry.getValue() != null
                    && !entry.getValue().isEmpty()) {
                aliases.put(entry.getKey(), entry.getValue());
            }
        }
        root.put("displayAliases", aliases);
        return root;
    }

    private static JSONArray targetsToJson(
            final List<AppLaunchTarget> targets) throws JSONException {
        final JSONArray values = new JSONArray();
        for (final AppLaunchTarget target : targets) {
            if (target != null) {
                values.put(targetToJson(target));
            }
        }
        return values;
    }

    private static JSONArray stringsToJson(final List<String> values) {
        final JSONArray array = new JSONArray();
        for (final String value : values) {
            if (value != null && !value.isEmpty()) {
                array.put(value);
            }
        }
        return array;
    }

    private static JSONObject targetToJson(final AppLaunchTarget target)
            throws JSONException {
        final JSONObject json = new JSONObject();
        json.put("package", target.packageName);
        if (!target.activityClassName.isEmpty()) {
            json.put("activity", target.activityClassName);
        }
        if (!target.action.isEmpty()) {
            json.put("action", target.action);
        }
        return json;
    }

    private static void readTargets(
            final JSONArray values,
            final List<AppLaunchTarget> destination) {
        if (values == null) {
            return;
        }
        for (int index = 0; index < values.length(); index++) {
            final AppLaunchTarget target = targetFromJson(
                    values.optJSONObject(index));
            if (target != null && !destination.contains(target)) {
                destination.add(target);
            }
        }
    }

    private static AppLaunchTarget targetFromJson(final JSONObject json) {
        if (json == null) {
            return null;
        }
        final String packageName = json.optString("package", "");
        final String activity = json.optString("activity", "");
        final String action = json.optString("action", "");
        if (activity.length() > 512 || action.length() > 512) {
            return null;
        }
        try {
            return activity.isEmpty()
                    ? AppLaunchTarget.packageDefault(packageName)
                    : AppLaunchTarget.explicit(packageName, activity, action);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static void readPackages(
            final JSONArray values,
            final List<String> destination) {
        if (values == null) {
            return;
        }
        for (int index = 0; index < values.length(); index++) {
            final String packageName = values.optString(index, "");
            if (PackageNameValidator.isSafe(packageName)
                    && !destination.contains(packageName)) {
                destination.add(packageName);
            }
        }
    }

    private static JSONObject profileToJson(
            final DisplayProfileStore.Profile profile) throws JSONException {
        final JSONObject json = new JSONObject();
        json.put("monitor", profile.monitorKey);
        json.put("dpi", profile.dpi);
        json.put("dpiExplicit", profile.dpiExplicit);
        if (profile.workspaceBounds != null
                && profile.workspaceBounds.right > profile.workspaceBounds.left
                && profile.workspaceBounds.bottom
                        > profile.workspaceBounds.top) {
            final JSONArray bounds = new JSONArray();
            bounds.put(profile.workspaceBounds.left);
            bounds.put(profile.workspaceBounds.top);
            bounds.put(profile.workspaceBounds.right);
            bounds.put(profile.workspaceBounds.bottom);
            json.put("workspaceBounds", bounds);
            if (profile.workspaceBoundsTarget != null) {
                json.put(
                        "workspaceBoundsTarget",
                        profile.workspaceBoundsTarget);
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

    private static DisplayProfileStore.Profile profileFromJson(
            final JSONObject json) {
        if (json == null) {
            return null;
        }
        final String monitorKey = json.optString("monitor", "");
        if (monitorKey.isEmpty()) {
            return null;
        }
        final DisplayProfileStore.Profile profile =
                new DisplayProfileStore.Profile(monitorKey);
        profile.dpi = json.optInt("dpi", 192);
        profile.dpiExplicit = json.optBoolean("dpiExplicit", false);
        final JSONArray bounds = json.optJSONArray("workspaceBounds");
        if (bounds != null && bounds.length() == 4) {
            final int left = bounds.optInt(0);
            final int top = bounds.optInt(1);
            final int right = bounds.optInt(2);
            final int bottom = bounds.optInt(3);
            if (right > left && bottom > top) {
                final Rect parsed = new Rect();
                parsed.left = left;
                parsed.top = top;
                parsed.right = right;
                parsed.bottom = bottom;
                profile.workspaceBounds = parsed;
                final String target = json.optString(
                        "workspaceBoundsTarget", "");
                profile.workspaceBoundsTarget = target.isEmpty()
                        ? null : target;
            }
        }
        final JSONObject placements = json.optJSONObject("placements");
        if (placements != null) {
            final java.util.Iterator<String> keys = placements.keys();
            while (keys.hasNext()) {
                final String itemId = keys.next();
                final JSONArray values = placements.optJSONArray(itemId);
                if (itemId.isEmpty() || values == null
                        || values.length() != 4
                        || values.optInt(0, -1) < 0
                        || values.optInt(1, -1) < 0) {
                    continue;
                }
                profile.placements.put(itemId, new DesktopPlacement(
                        values.optInt(0),
                        values.optInt(1),
                        values.optInt(2, 1),
                        values.optInt(3, 1)));
            }
        }
        return profile;
    }

    private static void report(final String message, final Throwable error) {
        try {
            Log.w(TAG, message, error);
        } catch (RuntimeException ignored) {
            // Diagnostics must not alter storage transaction semantics.
        }
        try {
            CompatibilityDiagnostics.record(
                    "DESKTOP-STATE-001",
                    message,
                    error.getMessage() == null
                            ? error.getClass().getSimpleName()
                            : error.getMessage(),
                    error);
        } catch (RuntimeException ignored) {
            // The state has already been rolled back where necessary.
        }
    }

    static final class State {
        final DesktopContentStore.State content =
                new DesktopContentStore.State();
        final List<String> taskbarPackages = new ArrayList<>();
        final Map<String, DisplayProfileStore.Profile> displayProfiles =
                new LinkedHashMap<>();
        final Map<String, String> displayAliases = new LinkedHashMap<>();
    }

    static final class ExternalSnapshot {
        final String encoded;

        ExternalSnapshot(final String encoded) {
            this.encoded = encoded;
        }
    }
}

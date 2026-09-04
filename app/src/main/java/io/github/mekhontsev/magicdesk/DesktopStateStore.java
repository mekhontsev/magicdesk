package io.github.mekhontsev.magicdesk;

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
        readPackages(root.optJSONArray("taskbar"), state.taskbarPackages);
        readDesktopPlacements(
                root.optJSONObject("desktopPlacements"),
                state.desktopPlacements);
        state.desktopPlacements.keySet().removeIf(
                key -> key.startsWith("app:"));
        readAppWindows(
                root.optJSONObject("appWindows"), state.appWindows);
        readAppPresentations(
                root.optJSONObject("appPresentations"),
                state.appPresentations);
        state.settings = MagicDeskSettings.Values.fromJson(
                root.optJSONObject("settings"));

        final JSONObject profiles = root.optJSONObject("displayProfiles");
        if (profiles != null) {
            final java.util.Iterator<String> keys = profiles.keys();
            while (keys.hasNext()) {
                final String key = keys.next();
                final JSONObject value = profiles.optJSONObject(key);
                final DisplayProfileStore.Profile profile =
                        profileFromJson(value);
                if (profile != null && key.equals(profile.key)) {
                    state.displayProfiles.put(key, profile);
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
        snapshot.taskbarPackages.addAll(sState.taskbarPackages);
        snapshot.desktopPlacements.putAll(sState.desktopPlacements);
        snapshot.appWindows.putAll(sState.appWindows);
        snapshot.appPresentations.putAll(sState.appPresentations);
        snapshot.settings = sState.settings.copy();
        for (final Map.Entry<String, DisplayProfileStore.Profile> entry
                : sState.displayProfiles.entrySet()) {
            snapshot.displayProfiles.put(
                    entry.getKey(), DisplayProfileStore.copy(entry.getValue()));
        }
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
        root.put("taskbar", stringsToJson(state.taskbarPackages));
        root.put(
                "desktopPlacements",
                desktopPlacementsToJson(state.desktopPlacements));
        root.put("appWindows", appWindowsToJson(state.appWindows));
        root.put(
                "appPresentations",
                appPresentationsToJson(state.appPresentations));
        root.put("settings", state.settings.toJson());

        final JSONObject profiles = new JSONObject();
        for (final Map.Entry<String, DisplayProfileStore.Profile> entry
                : state.displayProfiles.entrySet()) {
            final DisplayProfileStore.Profile profile = entry.getValue();
            if (profile != null && entry.getKey().equals(profile.key)) {
                profiles.put(entry.getKey(), profileToJson(profile));
            }
        }
        root.put("displayProfiles", profiles);

        return root;
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
        json.put("key", profile.key);
        json.put("dpi", profile.dpi);
        json.put("dpiExplicit", profile.dpiExplicit);
        json.put("fillDisplay", profile.fillDisplay);
        if (profile.outputTiming != null && !profile.outputTiming.isEmpty()) {
            json.put("outputTiming", profile.outputTiming);
        }
        if (profile.resetOutputModePending) {
            json.put("resetOutputModePending", true);
        }
        return json;
    }

    private static DisplayProfileStore.Profile profileFromJson(
            final JSONObject json) {
        if (json == null) {
            return null;
        }
        final String profileKey = json.optString("key", "");
        if (profileKey.isEmpty()) {
            return null;
        }
        final DisplayProfileStore.Profile profile =
                new DisplayProfileStore.Profile(profileKey);
        profile.dpi = json.optInt("dpi", 192);
        profile.dpiExplicit = json.optBoolean("dpiExplicit", false);
        profile.fillDisplay = json.optBoolean("fillDisplay", true);
        final String outputTiming = json.optString("outputTiming", "");
        profile.outputTiming = outputTiming.isEmpty() ? null : outputTiming;
        profile.resetOutputModePending = json.optBoolean(
                "resetOutputModePending", false);
        return profile;
    }

    private static JSONObject desktopPlacementsToJson(
            final Map<String, GlobalDesktopPlacement> placements)
            throws JSONException {
        final JSONObject json = new JSONObject();
        for (final Map.Entry<String, GlobalDesktopPlacement> entry
                : placements.entrySet()) {
            final String itemId = entry.getKey();
            final GlobalDesktopPlacement placement = entry.getValue();
            if (itemId == null || itemId.isEmpty() || placement == null) {
                continue;
            }
            final JSONArray values = new JSONArray();
            values.put(placement.x);
            values.put(placement.y);
            values.put(placement.columnSpan);
            values.put(placement.rowSpan);
            json.put(itemId, values);
        }
        return json;
    }

    private static void readDesktopPlacements(
            final JSONObject json,
            final Map<String, GlobalDesktopPlacement> destination) {
        if (json == null) {
            return;
        }
        final java.util.Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            final String itemId = keys.next();
            final JSONArray values = json.optJSONArray(itemId);
            if (itemId.isEmpty() || itemId.length() > 2048
                    || values == null || values.length() != 4
                    || values.optInt(0, -1) < 0
                    || values.optInt(1, -1) < 0
                    || values.optInt(0) > GlobalDesktopPlacement.SCALE
                    || values.optInt(1) > GlobalDesktopPlacement.SCALE
                    || values.optInt(2, 0) <= 0
                    || values.optInt(3, 0) <= 0) {
                continue;
            }
            destination.put(itemId, new GlobalDesktopPlacement(
                    values.optInt(0),
                    values.optInt(1),
                    values.optInt(2),
                    values.optInt(3)));
        }
    }

    private static JSONObject appWindowsToJson(
            final Map<String, AppWindowState> appWindows)
            throws JSONException {
        final JSONObject json = new JSONObject();
        for (final Map.Entry<String, AppWindowState> entry
                : appWindows.entrySet()) {
            final String stateKey = entry.getKey();
            final AppWindowState state = entry.getValue();
            if (!AppWindowStateStore.isSafeStateKey(stateKey)
                    || state == null
                    || (state.mode == null
                            && state.windowBounds == null)) {
                continue;
            }
            final JSONObject value = new JSONObject();
            if (state.mode != null) {
                value.put("mode", state.mode == AppWindowState.Mode.WINDOWED
                        ? "windowed" : "fullscreen");
            }
            if (state.windowBounds != null) {
                final JSONArray bounds = new JSONArray();
                bounds.put(state.windowBounds.x);
                bounds.put(state.windowBounds.y);
                bounds.put(state.windowBounds.width);
                bounds.put(state.windowBounds.height);
                value.put("bounds", bounds);
            }
            json.put(stateKey, value);
        }
        return json;
    }

    private static void readAppWindows(
            final JSONObject json,
            final Map<String, AppWindowState> destination) {
        if (json == null) {
            return;
        }
        final java.util.Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            final String stateKey = keys.next();
            final JSONObject value = json.optJSONObject(stateKey);
            if (!AppWindowStateStore.isSafeStateKey(stateKey)
                    || value == null) {
                continue;
            }
            final String encodedMode = value.optString("mode", "");
            final AppWindowState.Mode mode;
            if ("windowed".equals(encodedMode)) {
                mode = AppWindowState.Mode.WINDOWED;
            } else if ("fullscreen".equals(encodedMode)) {
                mode = AppWindowState.Mode.FULLSCREEN;
            } else {
                mode = null;
            }
            RelativeWindowBounds bounds = null;
            final JSONArray encodedBounds = value.optJSONArray("bounds");
            if (encodedBounds != null && encodedBounds.length() == 4
                    && isRelativeValue(encodedBounds.optInt(0, -1), false)
                    && isRelativeValue(encodedBounds.optInt(1, -1), false)
                    && isRelativeValue(encodedBounds.optInt(2, -1), true)
                    && isRelativeValue(encodedBounds.optInt(3, -1), true)) {
                bounds = new RelativeWindowBounds(
                        encodedBounds.optInt(0),
                        encodedBounds.optInt(1),
                        encodedBounds.optInt(2),
                        encodedBounds.optInt(3));
            }
            if (mode == null && bounds == null) {
                continue;
            }
            destination.put(stateKey, new AppWindowState(mode, bounds));
        }
    }

    private static JSONObject appPresentationsToJson(
            final Map<String, AppPresentationProfile> profiles)
            throws JSONException {
        final JSONObject json = new JSONObject();
        for (final Map.Entry<String, AppPresentationProfile> entry
                : profiles.entrySet()) {
            final AppPresentationProfile profile = entry.getValue();
            if (AppPresentationProfile.supportsPackage(entry.getKey())
                    && profile != null) {
                json.put(entry.getKey(), profile.scalePercent);
            }
        }
        return json;
    }

    private static void readAppPresentations(
            final JSONObject json,
            final Map<String, AppPresentationProfile> destination) {
        if (json == null) {
            return;
        }
        final java.util.Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            final String packageName = keys.next();
            final int scalePercent = json.optInt(packageName, -1);
            if (!AppPresentationProfile.supportsPackage(packageName)
                    || !AppPresentationProfile.isValidScale(scalePercent)) {
                continue;
            }
            destination.put(
                    packageName,
                    new AppPresentationProfile(scalePercent));
        }
    }

    private static boolean isRelativeValue(
            final int value,
            final boolean positive) {
        return value >= (positive ? 1 : 0)
                && value <= RelativeWindowBounds.SCALE;
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
        final List<String> taskbarPackages = new ArrayList<>();
        final Map<String, GlobalDesktopPlacement> desktopPlacements =
                new LinkedHashMap<>();
        final Map<String, AppWindowState> appWindows =
                new LinkedHashMap<>();
        final Map<String, AppPresentationProfile> appPresentations =
                new LinkedHashMap<>();
        final Map<String, DisplayProfileStore.Profile> displayProfiles =
                new LinkedHashMap<>();
        MagicDeskSettings.Values settings = MagicDeskSettings.Values.defaults();
    }

    static final class ExternalSnapshot {
        final String encoded;

        ExternalSnapshot(final String encoded) {
            this.encoded = encoded;
        }
    }
}

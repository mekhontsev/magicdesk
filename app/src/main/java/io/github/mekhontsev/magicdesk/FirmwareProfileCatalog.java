package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Declarative exact-firmware support catalog bundled with the APK. */
public final class FirmwareProfileCatalog {
    private static final String TAG = "MagicDeskProfiles";
    private static final String ASSET =
            "compatibility/firmware-profiles.json";
    private static volatile FirmwareProfileCatalog sCurrent;

    public static final class Entry {
        public final String platformId;
        public final String name;
        public final List<String> models;
        public final List<String> devices;
        public final String fingerprint;
        public final PlatformSupportLevel supportLevel;
        public final List<String> confirmedScope;
        public final List<String> limitations;

        Entry(
                final String platformId,
                final String name,
                final List<String> models,
                final List<String> devices,
                final String fingerprint,
                final PlatformSupportLevel supportLevel,
                final List<String> confirmedScope,
                final List<String> limitations) {
            this.platformId = platformId;
            this.name = name;
            this.models = Collections.unmodifiableList(
                    new ArrayList<>(models));
            this.devices = Collections.unmodifiableList(
                    new ArrayList<>(devices));
            this.fingerprint = fingerprint;
            this.supportLevel = supportLevel;
            this.confirmedScope = Collections.unmodifiableList(
                    new ArrayList<>(confirmedScope));
            this.limitations = Collections.unmodifiableList(
                    new ArrayList<>(limitations));
        }

        boolean matches(
                final String selectedPlatformId,
                final PlatformDevice device) {
            return device != null
                    && platformId.equals(selectedPlatformId)
                    && fingerprint.equals(device.fingerprint)
                    && (containsIgnoreCase(models, device.model)
                            || containsIgnoreCase(devices, device.device));
        }

        public String supportDetail() {
            final StringBuilder detail = new StringBuilder()
                    .append(supportLevel.name().toLowerCase(Locale.ROOT)
                            .replace('_', '-'))
                    .append(' ').append(name);
            if (!confirmedScope.isEmpty()) {
                detail.append("; scope=")
                        .append(String.join(",", confirmedScope));
            }
            if (!limitations.isEmpty()) {
                detail.append("; limitations=")
                        .append(String.join(" | ", limitations));
            }
            return detail.toString();
        }
    }

    private final int mSchemaVersion;
    private final List<Entry> mEntries;

    private FirmwareProfileCatalog(
            final int schemaVersion,
            final List<Entry> entries) {
        mSchemaVersion = schemaVersion;
        mEntries = Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public static FirmwareProfileCatalog load(final Context context) {
        FirmwareProfileCatalog current = sCurrent;
        if (current != null) {
            return current;
        }
        synchronized (FirmwareProfileCatalog.class) {
            current = sCurrent;
            if (current == null) {
                try (InputStream stream = context.getAssets().open(ASSET)) {
                    current = parse(read(stream));
                } catch (IOException | JSONException error) {
                    Log.e(TAG, "could not load firmware profile catalog", error);
                    current = new FirmwareProfileCatalog(
                            0, Collections.emptyList());
                }
                sCurrent = current;
            }
        }
        return current;
    }

    static FirmwareProfileCatalog parse(final String source)
            throws JSONException {
        final JSONObject root = new JSONObject(source);
        final int schemaVersion = root.getInt("schemaVersion");
        if (schemaVersion != 1) {
            throw new JSONException(
                    "unsupported firmware profile schema " + schemaVersion);
        }
        final JSONArray profiles = root.getJSONArray("profiles");
        final List<Entry> entries = new ArrayList<>();
        for (int index = 0; index < profiles.length(); index++) {
            final JSONObject profile = profiles.getJSONObject(index);
            entries.add(new Entry(
                    required(profile, "platform"),
                    required(profile, "name"),
                    strings(profile.getJSONArray("models")),
                    strings(profile.getJSONArray("devices")),
                    required(profile, "fingerprint"),
                    PlatformSupportLevel.valueOf(
                            required(profile, "support")),
                    strings(profile.optJSONArray("confirmedScope")),
                    strings(profile.optJSONArray("limitations"))));
        }
        return new FirmwareProfileCatalog(schemaVersion, entries);
    }

    public int schemaVersion() {
        return mSchemaVersion;
    }

    public List<Entry> entries() {
        return mEntries;
    }

    public Entry find(
            final String platformId,
            final PlatformDevice device) {
        for (final Entry entry : mEntries) {
            if (entry.matches(platformId, device)) {
                return entry;
            }
        }
        return null;
    }

    private static String read(final InputStream stream) throws IOException {
        final StringBuilder source = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                source.append(line).append('\n');
            }
        }
        return source.toString();
    }

    private static String required(
            final JSONObject object,
            final String key) throws JSONException {
        final String value = object.getString(key);
        if (value.isEmpty()) {
            throw new JSONException("empty firmware profile field " + key);
        }
        return value;
    }

    private static List<String> strings(final JSONArray values)
            throws JSONException {
        if (values == null) {
            return Collections.emptyList();
        }
        final List<String> result = new ArrayList<>();
        for (int index = 0; index < values.length(); index++) {
            result.add(values.getString(index));
        }
        return result;
    }

    private static boolean containsIgnoreCase(
            final List<String> values,
            final String expected) {
        for (final String value : values) {
            if (value.equalsIgnoreCase(expected)) {
                return true;
            }
        }
        return false;
    }
}

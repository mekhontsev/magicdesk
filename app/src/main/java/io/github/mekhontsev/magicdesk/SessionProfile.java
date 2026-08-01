package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.Locale;

final class SessionProfile {
    static final String EXTRA_DISPLAY_TARGET =
            "io.github.mekhontsev.magicdesk.extra.DISPLAY_TARGET";

    private static final String PREFS = "magicdesk_session";
    private static final String KEY_DISPLAY_TARGET = "display_target";

    enum DisplayTarget {
        AUTO,
        PRIMARY,
        CURRENT,
        EXTERNAL
    }

    final DisplayTarget displayTarget;

    interface PreferenceStore {
        String getString(String key);

        void putString(String key, String value);
    }

    SessionProfile(final DisplayTarget displayTarget) {
        this.displayTarget = displayTarget == null
                ? DisplayTarget.AUTO : displayTarget;
    }

    static SessionProfile load(final Context context) {
        return load(new SharedPreferenceStore(preferences(context)));
    }

    static SessionProfile load(final PreferenceStore preferences) {
        return new SessionProfile(parseEnum(
                        DisplayTarget.class,
                        preferences.getString(KEY_DISPLAY_TARGET),
                        DisplayTarget.AUTO));
    }

    static SessionProfile fromLaunchIntent(
            final Context context, final Intent intent) {
        final SessionProfile saved = load(context);
        if (intent == null) {
            return saved;
        }
        return withLaunchOverrides(
                saved,
                intent.getStringExtra(EXTRA_DISPLAY_TARGET));
    }

    static SessionProfile withLaunchOverrides(
            final SessionProfile saved,
            final String displayTarget) {
        final SessionProfile fallback = saved == null
                ? new SessionProfile(DisplayTarget.AUTO)
                : saved;
        return new SessionProfile(parseEnum(
                        DisplayTarget.class,
                        displayTarget,
                        fallback.displayTarget));
    }

    void save(final Context context) {
        save(new SharedPreferenceStore(preferences(context)));
    }

    void save(final PreferenceStore preferences) {
        preferences.putString(KEY_DISPLAY_TARGET, wireName(displayTarget));
    }

    void writeToIntent(final Intent intent) {
        if (intent == null) {
            return;
        }
        intent.putExtra(EXTRA_DISPLAY_TARGET, wireName(displayTarget));
    }

    SessionProfile withDisplayTarget(final DisplayTarget target) {
        return new SessionProfile(target);
    }

    String displayWireName() {
        return wireName(displayTarget);
    }

    private static SharedPreferences preferences(final Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static final class SharedPreferenceStore
            implements PreferenceStore {
        private final SharedPreferences mPreferences;

        SharedPreferenceStore(final SharedPreferences preferences) {
            mPreferences = preferences;
        }

        @Override
        public String getString(final String key) {
            return mPreferences.getString(key, null);
        }

        @Override
        public void putString(final String key, final String value) {
            mPreferences.edit()
                    .putString(key, value)
                    .apply();
        }
    }

    private static String wireName(final Enum<?> value) {
        return value.name().toLowerCase(Locale.US);
    }

    private static <T extends Enum<T>> T parseEnum(
            final Class<T> type,
            final String value,
            final T fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.US));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}

package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import java.util.Locale;

final class SessionProfile {
    static final String EXTRA_PRIVILEGE_MODE =
            "io.github.mekhontsev.magicdesk.extra.PRIVILEGE_MODE";
    static final String EXTRA_DISPLAY_TARGET =
            "io.github.mekhontsev.magicdesk.extra.DISPLAY_TARGET";

    private static final String PREFS = "magicdesk_session";
    private static final String KEY_PRIVILEGE_MODE = "privilege_mode";
    private static final String KEY_DISPLAY_TARGET = "display_target";

    enum PrivilegeMode {
        AUTO,
        BASIC,
        SHIZUKU,
        ROOT
    }

    enum DisplayTarget {
        AUTO,
        PRIMARY,
        CURRENT,
        EXTERNAL
    }

    final PrivilegeMode privilegeMode;
    final DisplayTarget displayTarget;

    SessionProfile(
            final PrivilegeMode privilegeMode,
            final DisplayTarget displayTarget) {
        this.privilegeMode = privilegeMode == null
                ? PrivilegeMode.AUTO : privilegeMode;
        this.displayTarget = displayTarget == null
                ? DisplayTarget.AUTO : displayTarget;
    }

    static SessionProfile load(final Context context) {
        final SharedPreferences preferences = preferences(context);
        return new SessionProfile(
                parseEnum(
                        PrivilegeMode.class,
                        preferences.getString(KEY_PRIVILEGE_MODE, null),
                        PrivilegeMode.AUTO),
                parseEnum(
                        DisplayTarget.class,
                        preferences.getString(KEY_DISPLAY_TARGET, null),
                        DisplayTarget.AUTO));
    }

    static SessionProfile fromLaunchIntent(
            final Context context, final Intent intent) {
        final SessionProfile saved = load(context);
        if (intent == null) {
            return saved;
        }
        return new SessionProfile(
                parseEnum(
                        PrivilegeMode.class,
                        intent.getStringExtra(EXTRA_PRIVILEGE_MODE),
                        saved.privilegeMode),
                parseEnum(
                        DisplayTarget.class,
                        intent.getStringExtra(EXTRA_DISPLAY_TARGET),
                        saved.displayTarget));
    }

    void save(final Context context) {
        preferences(context).edit()
                .putString(KEY_PRIVILEGE_MODE, wireName(privilegeMode))
                .putString(KEY_DISPLAY_TARGET, wireName(displayTarget))
                .apply();
    }

    void writeToIntent(final Intent intent) {
        if (intent == null) {
            return;
        }
        intent.putExtra(EXTRA_PRIVILEGE_MODE, wireName(privilegeMode));
        intent.putExtra(EXTRA_DISPLAY_TARGET, wireName(displayTarget));
    }

    SessionProfile withPrivilegeMode(final PrivilegeMode mode) {
        return new SessionProfile(mode, displayTarget);
    }

    SessionProfile withDisplayTarget(final DisplayTarget target) {
        return new SessionProfile(privilegeMode, target);
    }

    String privilegeWireName() {
        return wireName(privilegeMode);
    }

    String displayWireName() {
        return wireName(displayTarget);
    }

    private static SharedPreferences preferences(final Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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

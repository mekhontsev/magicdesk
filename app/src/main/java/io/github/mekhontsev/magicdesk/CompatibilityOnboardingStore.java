package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/** Manual compatibility observations tied to one exact firmware fingerprint. */
final class CompatibilityOnboardingStore {
    private static final String PREFS = "magicdesk_compatibility_onboarding";
    private static final String KEY_FINGERPRINT = "fingerprint";
    private static final String KEY_TARGET = "target";

    enum Target {
        PHONE,
        SIMULATED,
        WIRED,
        WIRELESS
    }

    enum State {
        NOT_TESTED,
        PASS,
        FAIL,
        UNAVAILABLE
    }

    enum Check {
        DESKTOP_START("desktop-start", R.string.onboarding_check_desktop_start),
        WINDOW_MOVE_RESIZE(
                "window-move-resize", R.string.onboarding_check_window_move_resize),
        FULLSCREEN_RESTORE(
                "fullscreen-restore", R.string.onboarding_check_fullscreen_restore),
        PHYSICAL_INPUT("physical-input", R.string.onboarding_check_physical_input),
        CAPTURE("capture", R.string.onboarding_check_capture),
        CLOSE_RESTORE("close-restore", R.string.onboarding_check_close_restore),
        HOME_ROLE("home-role", R.string.onboarding_check_home_role),
        OUTPUT_CONFIGURATION(
                "output-configuration", R.string.onboarding_check_output_configuration);

        final String wireName;
        final int labelResId;

        Check(final String wireName, final int labelResId) {
            this.wireName = wireName;
            this.labelResId = labelResId;
        }
    }

    static final class Record {
        final String fingerprint;
        final Target target;
        final EnumMap<Check, State> states;

        Record(
                final String fingerprint,
                final Target target,
                final Map<Check, State> states) {
            this.fingerprint = fingerprint;
            this.target = target;
            this.states = new EnumMap<>(Check.class);
            for (final Check check : Check.values()) {
                this.states.put(
                        check,
                        states == null || states.get(check) == null
                                ? State.NOT_TESTED : states.get(check));
            }
        }
    }

    private CompatibilityOnboardingStore() {
    }

    static Record load(final Context context) {
        final SharedPreferences preferences = preferences(context);
        final String fingerprint = PlatformDevice.current().fingerprint;
        if (!fingerprint.equals(
                preferences.getString(KEY_FINGERPRINT, ""))) {
            return empty(fingerprint);
        }
        final Target target = parse(
                Target.class,
                preferences.getString(KEY_TARGET, Target.PHONE.name()),
                Target.PHONE);
        final EnumMap<Check, State> states = new EnumMap<>(Check.class);
        for (final Check check : Check.values()) {
            states.put(check, parse(
                    State.class,
                    preferences.getString(
                            stateKey(target, check), State.NOT_TESTED.name()),
                    State.NOT_TESTED));
        }
        return new Record(fingerprint, target, states);
    }

    static Record load(final Context context, final Target target) {
        final SharedPreferences preferences = preferences(context);
        final String fingerprint = PlatformDevice.current().fingerprint;
        if (!fingerprint.equals(
                preferences.getString(KEY_FINGERPRINT, ""))) {
            return empty(fingerprint, target);
        }
        final EnumMap<Check, State> states = new EnumMap<>(Check.class);
        for (final Check check : Check.values()) {
            states.put(check, parse(
                    State.class,
                    preferences.getString(
                            stateKey(target, check), State.NOT_TESTED.name()),
                    State.NOT_TESTED));
        }
        return new Record(fingerprint, target, states);
    }

    static void save(final Context context, final Record record) {
        final SharedPreferences preferences = preferences(context);
        final SharedPreferences.Editor editor = preferences.edit();
        if (!record.fingerprint.equals(
                preferences.getString(KEY_FINGERPRINT, ""))) {
            editor.clear();
        }
        editor
                .putString(KEY_FINGERPRINT, record.fingerprint)
                .putString(KEY_TARGET, record.target.name());
        for (final Check check : Check.values()) {
            editor.putString(
                    stateKey(record.target, check),
                    record.states.get(check).name());
        }
        editor.apply();
    }

    static void appendReport(
            final StringBuilder report,
            final Context context) {
        report.append("## Compatibility onboarding\n");
        for (final Target target : Target.values()) {
            final Record record = load(context, target);
            report.append("Target: ")
                    .append(target.name().toLowerCase(Locale.ROOT))
                    .append('\n');
            for (final Check check : Check.values()) {
                report.append(record.states.get(check).name())
                        .append(" [").append(check.wireName).append("]\n");
            }
        }
        report.append('\n');
    }

    static JSONObject toJson(final Context context) throws JSONException {
        final Record selected = load(context);
        final JSONArray targets = new JSONArray();
        for (final Target target : Target.values()) {
            final Record record = load(context, target);
            final JSONArray checks = new JSONArray();
            for (final Check check : Check.values()) {
                checks.put(new JSONObject()
                        .put("id", check.wireName)
                        .put("state", record.states.get(check).name()
                                .toLowerCase(Locale.ROOT)));
            }
            targets.put(new JSONObject()
                    .put("id", target.name().toLowerCase(Locale.ROOT))
                    .put("checks", checks));
        }
        return new JSONObject()
                .put("selectedTarget", selected.target.name()
                        .toLowerCase(Locale.ROOT))
                .put("targets", targets);
    }

    private static Record empty(final String fingerprint) {
        return empty(fingerprint, Target.PHONE);
    }

    private static Record empty(
            final String fingerprint,
            final Target target) {
        return new Record(fingerprint, target, new EnumMap<>(Check.class));
    }

    private static SharedPreferences preferences(final Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String stateKey(final Target target, final Check check) {
        return "state." + target.name() + '.' + check.name();
    }

    private static <T extends Enum<T>> T parse(
            final Class<T> type,
            final String value,
            final T fallback) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return fallback;
        }
    }
}

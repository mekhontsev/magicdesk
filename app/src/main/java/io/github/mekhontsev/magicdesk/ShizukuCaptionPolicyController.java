package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Temporarily disables Nubia's wired privacy filter while Console Mode is active. */
final class ShizukuCaptionPolicyController {
    private static final String TAG = "MagicDeskCaptionPolicy";
    private static final String PROJECTION_PROVIDER =
            "content://cn.nubia.touping.TouPingProvider";
    private static final String PROVIDER_METHOD = "MagicDesk";
    private static final String PROVIDER_ARGUMENT = "CALL_5";
    private static final String PROVIDER_REQUEST_KEY =
            "BURYINGPOINT_WIRE_CALL5";
    private static final String PROVIDER_PRIVACY_RESULT_KEY = "CALL_5_KEY3";
    private static final String PREFERENCES = "shizuku_caption_policy";
    private static final String KEY_OVERRIDE_ACTIVE = "override_active";
    private static final String KEY_RESTORE_VALUE = "restore_value";
    private static final String SURFACE_FLINGER_OPTION_COMMAND =
            "io.github.mekhontsev.magicdesk.SurfaceFlingerOptionCommand";
    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread =
                        new Thread(runnable, "MagicDeskCaptionPolicy");
                thread.setDaemon(true);
                return thread;
            });

    private ShizukuCaptionPolicyController() {
    }

    static void setCaptionsVisible(final Context context, final boolean visible) {
        final Context applicationContext = context.getApplicationContext();
        EXECUTOR.execute(() -> apply(applicationContext, visible));
    }

    private static void apply(final Context context, final boolean visible) {
        if (!RuntimeAccess.allowsShizukuCommands()
                || RuntimeAccess.allowsRootCommands()) {
            return;
        }
        final SharedPreferences preferences =
                context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        if (visible) {
            enable(context, preferences);
        } else {
            restore(context, preferences);
        }
    }

    private static void enable(
            final Context context,
            final SharedPreferences preferences) {
        final Integer privacyValue = readWiredPrivacyValue(context);
        if (privacyValue == null) {
            recordFailure(
                    "SHIZUKU-CAPTION-001",
                    "Could not read Nubia wired privacy mode",
                    "Native window captions remain hidden");
            return;
        }
        preferences.edit()
                .putBoolean(KEY_OVERRIDE_ACTIVE, true)
                .putInt(KEY_RESTORE_VALUE, privacyValue.intValue())
                .commit();
        if (setSurfaceFlingerOption(0)) {
            Log.i(TAG, "captions enabled; restore value=" + privacyValue);
            return;
        }
        setSurfaceFlingerOption(privacyValue.intValue());
        preferences.edit().putBoolean(KEY_OVERRIDE_ACTIVE, false).apply();
        recordFailure(
                "SHIZUKU-CAPTION-002",
                "Could not enable native window captions",
                "The Shizuku shell cannot update Nubia SurfaceFlinger option 1102");
    }

    private static void restore(
            final Context context,
            final SharedPreferences preferences) {
        if (!preferences.getBoolean(KEY_OVERRIDE_ACTIVE, false)) {
            return;
        }
        final Integer currentPrivacyValue = readWiredPrivacyValue(context);
        final int restoreValue = currentPrivacyValue != null
                ? currentPrivacyValue.intValue()
                : preferences.getInt(KEY_RESTORE_VALUE, 1);
        if (setSurfaceFlingerOption(restoreValue)) {
            preferences.edit().putBoolean(KEY_OVERRIDE_ACTIVE, false).apply();
            Log.i(TAG, "wired privacy restored value=" + restoreValue);
        } else {
            recordFailure(
                    "SHIZUKU-CAPTION-003",
                    "Could not restore Nubia wired privacy mode",
                    "SurfaceFlinger option 1102 should be restored after Shizuku reconnects");
        }
    }

    private static Integer readWiredPrivacyValue(final Context context) {
        final Bundle request = new Bundle();
        request.putBoolean(PROVIDER_REQUEST_KEY, true);
        try {
            final Bundle result = context.getContentResolver().call(
                    Uri.parse(PROJECTION_PROVIDER),
                    PROVIDER_METHOD,
                    PROVIDER_ARGUMENT,
                    request);
            final String rawValue = result == null
                    ? null : result.getString(PROVIDER_PRIVACY_RESULT_KEY);
            final Integer value = parsePrivacyValue(rawValue);
            Log.i(TAG, "Nubia wired privacy value=" + rawValue);
            return value;
        } catch (RuntimeException error) {
            Log.w(TAG, "cannot read Nubia wired privacy mode", error);
            return null;
        }
    }

    static Integer parsePrivacyValue(final String rawValue) {
        if (rawValue == null) {
            return null;
        }
        final String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
        if ("1".equals(normalized)
                || "true".equals(normalized)
                || "on".equals(normalized)
                || "enabled".equals(normalized)) {
            return Integer.valueOf(1);
        }
        if ("0".equals(normalized)
                || "false".equals(normalized)
                || "off".equals(normalized)
                || "disabled".equals(normalized)) {
            return Integer.valueOf(0);
        }
        return null;
    }

    private static boolean setSurfaceFlingerOption(final int value) {
        final String command =
                "APK=$(/system/bin/pm path io.github.mekhontsev.magicdesk "
                        + "| /system/bin/cut -d: -f2- "
                        + "| /system/bin/head -n 1); "
                        + "CLASSPATH=\"$APK\" /system/bin/app_process / "
                        + SURFACE_FLINGER_OPTION_COMMAND
                        + " set " + value;
        try {
            final String output = PrivilegedCommandRunner.run(command).trim();
            final boolean success = output.contains(
                    "external-task-captions="
                            + (value == 0 ? "enabled" : "restored"))
                    && output.contains("value=" + value);
            if (!success) {
                Log.w(TAG, "unexpected SurfaceFlinger response=" + output);
            }
            return success;
        } catch (IOException error) {
            Log.w(TAG, "SurfaceFlinger option failed value=" + value, error);
            return false;
        }
    }

    private static void recordFailure(
            final String code,
            final String summary,
            final String detail) {
        CompatibilityDiagnostics.record(code, summary, detail);
        Log.w(TAG, summary + ": " + detail);
    }
}

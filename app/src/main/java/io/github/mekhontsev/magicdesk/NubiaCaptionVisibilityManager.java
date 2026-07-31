package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.util.Log;

import java.io.IOException;

/** Owns Nubia's temporary external-caption visibility override. */
final class NubiaCaptionVisibilityManager {
    private static final String TAG = "MagicDeskCaptions";
    private static final String PREFS = "magicdesk_caption_visibility";
    private static final String KEY_OWNED = "owned";
    private static final String WIRED_PRIVACY_SETTING = "cast_privacy_model";
    private static final String APP_PROCESS = "/system/bin/app_process";
    private static final String COMMAND_CLASS =
            "io.github.mekhontsev.magicdesk.SurfaceFlingerOptionCommand";
    private static final long COMMAND_TIMEOUT_MILLIS = 5_000L;
    private static final int MAX_OUTPUT_BYTES = 32 * 1024;

    private NubiaCaptionVisibilityManager() {
    }

    static synchronized boolean setEnabled(final boolean enabled) {
        final Context context = MagicDeskApplication.applicationContext();
        if (context == null) {
            Log.w(TAG, "application context is unavailable");
            return false;
        }
        final SharedPreferences preferences =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final boolean alreadyOwned =
                preferences.getBoolean(KEY_OWNED, false);

        if (!enabled && !alreadyOwned) {
            return true;
        }
        if (enabled
                && !alreadyOwned
                && !preferences.edit().putBoolean(KEY_OWNED, true).commit()) {
            Log.w(TAG, "could not record caption visibility ownership");
            return false;
        }

        final int value = enabled ? 0 : readWiredPrivacyMode(context);
        try {
            final String output = setSurfaceFlingerOption(context, value);
            if (!enabled && !preferences.edit().remove(KEY_OWNED).commit()) {
                Log.w(TAG, "could not clear caption visibility ownership");
                return false;
            }
            Log.i(TAG, output.replace('\n', ' ').trim());
            return true;
        } catch (IOException error) {
            Log.w(TAG, "could not update external caption visibility", error);
            if (enabled && !alreadyOwned) {
                preferences.edit().remove(KEY_OWNED).apply();
            }
            CompatibilityDiagnostics.record(
                    "NUBIA-CAPTION-001",
                    "Could not update external caption visibility",
                    error.getMessage(),
                    error);
            return false;
        }
    }

    static boolean parseWiredPrivacyMode(final String value) {
        return value == null || !"false".equalsIgnoreCase(value.trim());
    }

    private static int readWiredPrivacyMode(final Context context) {
        final String value = Settings.Global.getString(
                context.getContentResolver(),
                WIRED_PRIVACY_SETTING);
        return parseWiredPrivacyMode(value) ? 1 : 0;
    }

    private static String setSurfaceFlingerOption(
            final Context context,
            final int value) throws IOException {
        final ProcessBuilder builder = new ProcessBuilder(
                APP_PROCESS,
                "/",
                COMMAND_CLASS,
                "set",
                Integer.toString(value));
        builder.environment().put(
                "CLASSPATH",
                context.getApplicationInfo().sourceDir);
        builder.redirectErrorStream(true);
        final Process process = builder.start();
        try {
            final BoundedProcessRunner.Result result =
                    BoundedProcessRunner.run(
                            process,
                            COMMAND_TIMEOUT_MILLIS,
                            MAX_OUTPUT_BYTES);
            if (result.exitCode != 0) {
                throw new IOException(
                        "SurfaceFlinger command exited " + result.exitCode
                                + outputSuffix(result.output));
            }
            if (result.truncated) {
                throw new IOException(
                        "SurfaceFlinger command output was truncated");
            }
            final String expected =
                    "sf-option=1102 value=" + value;
            if (!result.output.contains(expected)) {
                throw new IOException(
                        "unexpected SurfaceFlinger command result"
                                + outputSuffix(result.output));
            }
            return result.output;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("SurfaceFlinger command interrupted", error);
        } finally {
            process.destroy();
        }
    }

    private static String outputSuffix(final String output) {
        final String oneLine = output.trim()
                .replace('\n', ' ')
                .replace('\r', ' ');
        return oneLine.isEmpty() ? "" : ": " + oneLine;
    }
}

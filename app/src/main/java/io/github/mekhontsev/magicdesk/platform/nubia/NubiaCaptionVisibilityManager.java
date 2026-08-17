package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.BoundedProcessRunner;
import io.github.mekhontsev.magicdesk.CompatibilityDiagnostics;
import io.github.mekhontsev.magicdesk.MagicDeskApplication;
import io.github.mekhontsev.magicdesk.SurfaceFlingerOptionCommand;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.io.IOException;
import java.util.Locale;

/** Owns Nubia's temporary external-caption visibility override. */
public final class NubiaCaptionVisibilityManager {
    public enum Transport {
        NONE(-1, null, null, null),
        WIRELESS(
                1100,
                "CALL_4",
                "BURYINGPOINT_WIRELESS_CALL4",
                "CALL_4_KEY12"),
        WIRED(
                1102,
                "CALL_5",
                "BURYINGPOINT_WIRE_CALL5",
                "CALL_5_KEY3");

        final int surfaceFlingerOption;
        final String providerArgument;
        final String providerRequestKey;
        final String providerResultKey;

        Transport(
                final int surfaceFlingerOption,
                final String providerArgument,
                final String providerRequestKey,
                final String providerResultKey) {
            this.surfaceFlingerOption = surfaceFlingerOption;
            this.providerArgument = providerArgument;
            this.providerRequestKey = providerRequestKey;
            this.providerResultKey = providerResultKey;
        }
    }

    private static final String TAG = "MagicDeskCaptions";
    private static final String PREFS = "magicdesk_caption_visibility";
    private static final String KEY_OWNED_TRANSPORT = "owned_transport";
    private static final String KEY_RESTORE_VALUE = "restore_value";
    private static final String PROJECTION_PROVIDER =
            "content://cn.nubia.touping.TouPingProvider";
    private static final String PROVIDER_METHOD = "MagicDesk";
    private static final String APP_PROCESS = "/system/bin/app_process";
    private static final String COMMAND_CLASS =
            "io.github.mekhontsev.magicdesk.SurfaceFlingerOptionCommand";
    private static final long COMMAND_TIMEOUT_MILLIS = 5_000L;
    private static final int MAX_OUTPUT_BYTES = 32 * 1024;

    private NubiaCaptionVisibilityManager() {
    }

    public static synchronized boolean setTransport(final Transport target) {
        if (target == null) {
            throw new IllegalArgumentException("caption transport is required");
        }
        final Context context = MagicDeskApplication.applicationContext();
        if (context == null) {
            Log.w(TAG, "application context is unavailable");
            return false;
        }
        final SharedPreferences preferences =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        final Transport owned = readOwnedTransport(preferences);

        if (owned == target) {
            return target == Transport.NONE
                    || applyVisible(context, target);
        }
        if (owned != Transport.NONE
                && !restore(context, preferences, owned)) {
            return false;
        }
        if (target == Transport.NONE) {
            return true;
        }
        return acquire(context, preferences, target);
    }

    static Integer parsePrivacyValue(final String value) {
        if (value == null) {
            return null;
        }
        final String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("1".equals(normalized)
                || "true".equals(normalized)
                || "on".equals(normalized)
                || "enabled".equals(normalized)
                || "turn_on".equals(normalized)) {
            return Integer.valueOf(1);
        }
        if ("0".equals(normalized)
                || "false".equals(normalized)
                || "off".equals(normalized)
                || "disabled".equals(normalized)
                || "turn_off".equals(normalized)) {
            return Integer.valueOf(0);
        }
        return null;
    }

    private static boolean acquire(
            final Context context,
            final SharedPreferences preferences,
            final Transport transport) {
        final Integer restoreValue = readPrivacyMode(context, transport);
        if (restoreValue == null) {
            recordFailure(
                    "NUBIA-CAPTION-001",
                    "Could not read Nubia caption privacy mode",
                    "transport=" + transport.name().toLowerCase(Locale.ROOT));
            return false;
        }
        if (!preferences.edit()
                .putString(KEY_OWNED_TRANSPORT, transport.name())
                .putInt(KEY_RESTORE_VALUE, restoreValue.intValue())
                .commit()) {
            Log.w(TAG, "could not record caption visibility ownership");
            return false;
        }
        if (applyVisible(context, transport)) {
            Log.i(TAG, "captions enabled transport=" + transport
                    + " restoreValue=" + restoreValue);
            return true;
        }

        try {
            setSurfaceFlingerOption(
                    context, transport, restoreValue.intValue());
            if (!preferences.edit()
                    .remove(KEY_OWNED_TRANSPORT)
                    .remove(KEY_RESTORE_VALUE)
                    .commit()) {
                Log.w(TAG, "could not clear failed caption ownership");
            }
        } catch (IOException restoreError) {
            Log.w(TAG, "could not roll back caption visibility", restoreError);
        }
        return false;
    }

    private static boolean restore(
            final Context context,
            final SharedPreferences preferences,
            final Transport transport) {
        final Integer currentValue = readPrivacyMode(context, transport);
        final int restoreValue = currentValue != null
                ? currentValue.intValue()
                : preferences.getInt(KEY_RESTORE_VALUE, 1);
        try {
            final String output = setSurfaceFlingerOption(
                    context, transport, restoreValue);
            if (!preferences.edit()
                    .remove(KEY_OWNED_TRANSPORT)
                    .remove(KEY_RESTORE_VALUE)
                    .commit()) {
                Log.w(TAG, "could not clear caption visibility ownership");
                return false;
            }
            Log.i(TAG, output.replace('\n', ' ').trim());
            return true;
        } catch (IOException error) {
            Log.w(TAG, "could not restore external caption privacy", error);
            recordFailure(
                    "NUBIA-CAPTION-003",
                    "Could not restore Nubia caption privacy mode",
                    "transport=" + transport.name().toLowerCase(Locale.ROOT)
                            + " error=" + error.getMessage());
            return false;
        }
    }

    private static boolean applyVisible(
            final Context context,
            final Transport transport) {
        try {
            final String output = setSurfaceFlingerOption(context, transport, 0);
            Log.i(TAG, output.replace('\n', ' ').trim());
            return true;
        } catch (IOException error) {
            Log.w(TAG, "could not enable external captions", error);
            recordFailure(
                    "NUBIA-CAPTION-002",
                    "Could not enable native window captions",
                    "transport="
                            + transport.name().toLowerCase(Locale.ROOT)
                            + " error=" + error.getMessage());
            return false;
        }
    }

    private static Transport readOwnedTransport(
            final SharedPreferences preferences) {
        final String value = preferences.getString(
                KEY_OWNED_TRANSPORT, Transport.NONE.name());
        try {
            return Transport.valueOf(value);
        } catch (IllegalArgumentException error) {
            return Transport.NONE;
        }
    }

    private static Integer readPrivacyMode(
            final Context context,
            final Transport transport) {
        if (transport == Transport.NONE) {
            return null;
        }
        final Bundle request = new Bundle();
        request.putBoolean(transport.providerRequestKey, true);
        try {
            final Bundle result = context.getContentResolver().call(
                    Uri.parse(PROJECTION_PROVIDER),
                    PROVIDER_METHOD,
                    transport.providerArgument,
                    request);
            final String rawValue = result == null
                    ? null : result.getString(transport.providerResultKey);
            final Integer value = parsePrivacyValue(rawValue);
            Log.i(TAG, "Nubia privacy transport=" + transport
                    + " value=" + rawValue);
            return value;
        } catch (RuntimeException error) {
            Log.w(TAG, "cannot read Nubia privacy transport=" + transport, error);
            return null;
        }
    }

    private static String setSurfaceFlingerOption(
            final Context context,
            final Transport transport,
            final int value) throws IOException {
        final ProcessBuilder builder = new ProcessBuilder(
                APP_PROCESS,
                "/",
                COMMAND_CLASS,
                "set",
                Integer.toString(transport.surfaceFlingerOption),
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
                    "sf-option=" + transport.surfaceFlingerOption
                            + " value=" + value;
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

    private static void recordFailure(
            final String code,
            final String summary,
            final String detail) {
        CompatibilityDiagnostics.record(code, summary, detail);
        Log.w(TAG, summary + ": " + detail);
    }
}

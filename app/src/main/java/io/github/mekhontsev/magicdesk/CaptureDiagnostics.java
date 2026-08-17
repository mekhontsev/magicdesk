package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Records real capture workflow outcomes without running diagnostic captures. */
final class CaptureDiagnostics {
    private static final Object LOCK = new Object();
    private static final String PREFS = "capture_diagnostics";
    private static final String KEY_BUILD = "build";
    private static final String SCREENSHOT = "screenshot";
    private static final String RECORDING = "recording";
    private static final String STATE_STARTED = "started";
    private static final String STATE_PASSED = "passed";
    private static final String STATE_FAILED = "failed";

    private CaptureDiagnostics() {
    }

    static void recordScreenshot(
            final boolean success,
            final String detail) {
        write(SCREENSHOT, success ? STATE_PASSED : STATE_FAILED, detail);
    }

    static void recordRecordingStarted(final String detail) {
        write(RECORDING, STATE_STARTED, detail);
    }

    static void recordRecordingCompleted(final String detail) {
        write(RECORDING, STATE_PASSED, detail);
    }

    static void recordRecordingFailed(final String detail) {
        write(RECORDING, STATE_FAILED, detail);
    }

    static void appendReport(
            final StringBuilder report,
            final Context context) {
        report.append("## Capture workflows\n");
        if (context == null) {
            report.append("Screenshot: NOT_TESTED | diagnostics unavailable\n")
                    .append("Screen recording: NOT_TESTED"
                            + " | diagnostics unavailable\n\n");
            return;
        }
        final SharedPreferences preferences = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);
        final boolean currentBuild = buildId().equals(
                preferences.getString(KEY_BUILD, null));
        appendEntry(report, "Screenshot", preferences, SCREENSHOT, currentBuild);
        appendEntry(
                report,
                "Screen recording",
                preferences,
                RECORDING,
                currentBuild);
        report.append('\n');
    }

    private static void write(
            final String prefix,
            final String state,
            final String detail) {
        final Context context = MagicDeskApplication.applicationContext();
        if (context == null) {
            return;
        }
        synchronized (LOCK) {
            final SharedPreferences preferences = context.getSharedPreferences(
                    PREFS, Context.MODE_PRIVATE);
            final SharedPreferences.Editor editor = preferences.edit();
            if (!buildId().equals(preferences.getString(KEY_BUILD, null))) {
                editor.clear().putString(KEY_BUILD, buildId());
            }
            editor.putString(prefix + ".state", state)
                    .putLong(prefix + ".time", System.currentTimeMillis())
                    .putString(prefix + ".detail", clean(detail))
                    .commit();
        }
    }

    private static void appendEntry(
            final StringBuilder report,
            final String label,
            final SharedPreferences preferences,
            final String prefix,
            final boolean currentBuild) {
        final String state = currentBuild
                ? preferences.getString(prefix + ".state", null) : null;
        if (state == null) {
            report.append(label)
                    .append(": NOT_TESTED | no attempt recorded for this build\n");
            return;
        }
        report.append(label).append(": ")
                .append(reportState(state));
        final long time = preferences.getLong(prefix + ".time", 0L);
        if (time > 0L) {
            report.append(" | ").append(utc(time));
        }
        final String detail = preferences.getString(prefix + ".detail", "");
        if (detail != null && !detail.isEmpty()) {
            report.append(" | ").append(detail);
        }
        report.append('\n');
    }

    static String reportState(final String state) {
        if (STATE_PASSED.equals(state)) {
            return "PASS";
        }
        if (STATE_FAILED.equals(state)) {
            return "FAIL";
        }
        if (STATE_STARTED.equals(state)) {
            return "IN_PROGRESS";
        }
        return "NOT_TESTED";
    }

    private static String buildId() {
        return BuildConfig.VERSION_NAME + ':' + BuildConfig.VERSION_CODE;
    }

    private static String utc(final long millis) {
        final SimpleDateFormat format = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date(millis));
    }

    private static String clean(final String value) {
        if (value == null) {
            return "";
        }
        final String result = value.replace('\u0000', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        return result.length() <= 600 ? result : result.substring(0, 600);
    }
}

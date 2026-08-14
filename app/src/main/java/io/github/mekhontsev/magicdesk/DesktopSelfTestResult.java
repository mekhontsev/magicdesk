package io.github.mekhontsev.magicdesk;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public final class DesktopSelfTestResult {
    private static final String RESULT_FILE = "desktop-self-test.txt";
    private static final int MAX_RESULT_CHARS = 32_000;

    public enum State {
        PASS,
        WARN,
        FAIL,
        NOT_TESTED
    }

    private final long mStartedAtMillis;
    private final List<Check> mChecks = new ArrayList<>();
    private long mFinishedAtMillis;

    DesktopSelfTestResult(final long startedAtMillis) {
        mStartedAtMillis = startedAtMillis;
    }

    public void add(final State state, final String code,
            final String label, final String detail) {
        if (state == null || code == null || label == null) {
            throw new IllegalArgumentException("self-test check is incomplete");
        }
        mChecks.add(new Check(state, code, label, clean(detail)));
    }

    void finish(final long finishedAtMillis) {
        mFinishedAtMillis = Math.max(mStartedAtMillis, finishedAtMillis);
    }

    int count(final State state) {
        int count = 0;
        for (final Check check : mChecks) {
            if (check.state == state) {
                count++;
            }
        }
        return count;
    }

    boolean hasFailures() {
        return count(State.FAIL) > 0;
    }

    String summary() {
        return count(State.PASS) + " passed, "
                + count(State.WARN) + " warnings, "
                + count(State.FAIL) + " failed, "
                + count(State.NOT_TESTED) + " not tested";
    }

    String format() {
        final long finishedAt = mFinishedAtMillis > 0
                ? mFinishedAtMillis : System.currentTimeMillis();
        final StringBuilder output = new StringBuilder(4_096);
        output.append("## Desktop self-test\n")
                .append("Generated UTC: ").append(utc(mStartedAtMillis)).append('\n')
                .append("Duration: ")
                .append(Math.max(0L, finishedAt - mStartedAtMillis))
                .append(" ms\n")
                .append("Outcome: ")
                .append(hasFailures() ? "FAIL"
                        : count(State.WARN) > 0 ? "WARN" : "PASS")
                .append('\n')
                .append("Summary: ").append(summary()).append('\n');
        for (final Check check : mChecks) {
            output.append(check.state.name())
                    .append(" [").append(check.code).append("] ")
                    .append(check.label);
            if (!check.detail.isEmpty()) {
                output.append(": ").append(check.detail);
            }
            output.append('\n');
        }
        output.append('\n');
        return output.toString();
    }

    void save(final Context context) {
        if (context == null) {
            return;
        }
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(
                        new File(context.getFilesDir(), RESULT_FILE), false),
                StandardCharsets.UTF_8)) {
            writer.write(format());
        } catch (IOException ignored) {
            // A diagnostic write must not turn a completed test into a failure.
        }
    }

    static void appendLastResult(
            final StringBuilder report, final Context context) {
        report.append(readLastResult(context));
    }

    static String readLastResult(final Context context) {
        if (context == null) {
            return "## Desktop self-test\nNot run\n\n";
        }
        final File file = new File(context.getFilesDir(), RESULT_FILE);
        if (!file.isFile()) {
            return "## Desktop self-test\nNot run\n\n";
        }
        final StringBuilder value = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            final char[] buffer = new char[2_048];
            int read;
            while ((read = reader.read(buffer)) >= 0
                    && value.length() < MAX_RESULT_CHARS) {
                value.append(buffer, 0,
                        Math.min(read, MAX_RESULT_CHARS - value.length()));
            }
        } catch (IOException ignored) {
            return "## Desktop self-test\nResult unavailable\n\n";
        }
        if (value.length() == 0) {
            return "## Desktop self-test\nResult unavailable\n\n";
        }
        if (value.charAt(value.length() - 1) != '\n') {
            value.append('\n');
        }
        return value.toString();
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
        final String oneLine = value.replace('\u0000', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        return oneLine.length() <= 1_000
                ? oneLine : oneLine.substring(0, 1_000);
    }

    private static final class Check {
        final State state;
        final String code;
        final String label;
        final String detail;

        Check(final State state, final String code,
                final String label, final String detail) {
            this.state = state;
            this.code = code;
            this.label = label;
            this.detail = detail;
        }
    }
}

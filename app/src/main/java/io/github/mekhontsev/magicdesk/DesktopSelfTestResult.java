package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public final class DesktopSelfTestResult {
    private static final String RESULT_FILE = "desktop-self-test.json";
    private static final int MAX_RESULT_CHARS = 32_000;
    private static final int MAX_RESULT_BYTES = 2 * 1024 * 1024;
    private static final Object RESULT_LOCK = new Object();

    public enum State {
        PASS,
        WARN,
        FAIL,
        NOT_TESTED
    }

    private final long mStartedAtMillis;
    private final long mRunId;
    private final String mTarget;
    private final String mMode;
    private final List<Check> mChecks = new ArrayList<>();
    private long mFinishedAtMillis;
    private boolean mFailFastArmed;
    private boolean mCancelled;

    DesktopSelfTestResult(final long startedAtMillis) {
        this(startedAtMillis, 0L);
    }

    DesktopSelfTestResult(final long startedAtMillis, final long runId) {
        mStartedAtMillis = startedAtMillis;
        mRunId = runId;
        final DesktopSelfTestRunState.Snapshot run = DesktopSelfTestRunState.snapshot();
        mTarget = run.runId == runId ? run.target : "";
        mMode = run.runId == runId ? run.mode : "";
    }

    long startedAtMillis() {
        return mStartedAtMillis;
    }

    long runId() {
        return mRunId;
    }

    public void add(final State state, final String code,
            final String label, final String detail) {
        if (state == null || code == null || label == null) {
            throw new IllegalArgumentException("self-test check is incomplete");
        }
        mChecks.add(new Check(state, code, label, clean(detail)));
        DesktopSelfTestRunState.checkCompleted(mRunId, code, state, label, clean(detail));
        if (state == State.FAIL && mFailFastArmed) {
            mFailFastArmed = false;
            throw new StopAfterFirstFailure(code);
        }
    }

    void arm(final DesktopSelfTestExecutionPolicy policy) {
        mFailFastArmed = policy != null && policy.stopsAfterFailure();
    }

    void disarm() {
        mFailFastArmed = false;
    }

    void cancel() {
        mFailFastArmed = false;
        mCancelled = true;
    }

    boolean isCancelled() {
        return mCancelled;
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
        if (mCancelled) {
            return "cancelled";
        }
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
                .append("Run: ").append(mRunId).append(" target=").append(mTarget).append('\n')
                .append("Generated UTC: ").append(utc(mStartedAtMillis)).append('\n')
                .append("Duration: ")
                .append(Math.max(0L, finishedAt - mStartedAtMillis))
                .append(" ms\n")
                .append("Outcome: ")
                .append(mCancelled ? "CANCELLED"
                        : hasFailures() ? "FAIL"
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
        synchronized (RESULT_LOCK) {
            saveLocked(context);
        }
    }

    private void saveLocked(final Context context) {
        if (context == null || mCancelled) {
            return;
        }
        final AtomicFile file = new AtomicFile(new File(context.getFilesDir(), RESULT_FILE));
        FileOutputStream output = null;
        try {
            final byte[] bytes = toJson(true).toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_RESULT_BYTES) throw new IOException("test result exceeds limit");
            output = file.startWrite();
            output.write(bytes);
            file.finishWrite(output);
        } catch (IOException | JSONException ignored) {
            if (output != null) file.failWrite(output);
            // A diagnostic write must not turn a completed test into a failure.
        }
    }

    JSONObject toJson(final boolean includeReport) throws JSONException {
        final JSONArray checks = new JSONArray();
        final JSONArray failures = new JSONArray();
        for (int index = 0; index < mChecks.size(); index++) {
            final Check check = mChecks.get(index);
            if (index < DesktopSelfTestRunState.Progress.MAX_CHECKS) checks.put(check.toJson());
            if (check.state == State.FAIL
                    && failures.length() < DesktopSelfTestRunState.Progress.MAX_CHECKS) {
                failures.put(check.toJson());
            }
        }
        final JSONObject value = new JSONObject().put("available", true)
                .put("runId", mRunId).put("target", mTarget).put("mode", mMode)
                .put("buildId", BuildConfig.SOURCE_ID)
                .put("versionName", BuildConfig.VERSION_NAME)
                .put("startedAtMillis", mStartedAtMillis)
                .put("completedAtMillis", mFinishedAtMillis)
                .put("outcome", mCancelled ? "cancelled" : hasFailures() ? "failed"
                        : count(State.WARN) > 0 ? "warnings" : "passed")
                .put("checks", checks).put("failures", failures)
                .put("checksTruncated", checks.length() < mChecks.size())
                .put("failuresTruncated", failures.length() < count(State.FAIL))
                .put("summary", summary());
        if (includeReport) {
            final String report = format();
            value.put("report", report.substring(0, Math.min(report.length(), MAX_RESULT_CHARS)))
                    .put("reportTruncated", report.length() > MAX_RESULT_CHARS);
        }
        return value;
    }

    static JSONObject readSavedResult(final Context context, final boolean includeReport)
            throws JSONException {
        synchronized (RESULT_LOCK) {
            return readSavedResultLocked(context, includeReport);
        }
    }

    private static JSONObject readSavedResultLocked(final Context context, final boolean includeReport)
            throws JSONException {
        if (context == null) return null;
        final AtomicFile file = new AtomicFile(new File(context.getFilesDir(), RESULT_FILE));
        try (FileInputStream input = file.openRead()) {
            final byte[] bytes = input.readNBytes(MAX_RESULT_BYTES + 1);
            if (bytes.length > MAX_RESULT_BYTES) throw new IOException("test result exceeds limit");
            final JSONObject value = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (!includeReport) {
                value.remove("report");
                value.remove("reportTruncated");
            }
            return value;
        } catch (FileNotFoundException error) {
            return file.getBaseFile().exists()
                    ? new JSONObject().put("available", false).put("error", "Saved result unavailable")
                    : null;
        } catch (IOException | JSONException error) {
            return new JSONObject().put("available", false).put("error", "Saved result unavailable");
        }
    }

    static void appendLastResult(
            final StringBuilder report, final Context context) {
        report.append(readLastResult(context));
    }

    static String readLastResult(final Context context) {
        try {
            final JSONObject value = readSavedResult(context, true);
            if (value == null) return "## Desktop self-test\nNot run\n\n";
            return value.optString("report", "## Desktop self-test\nResult unavailable\n\n")
                    + (value.optBoolean("reportTruncated") ? "\n[Report truncated]\n" : "");
        } catch (JSONException ignored) {
            return "## Desktop self-test\nResult unavailable\n\n";
        }
    }

    static long lastModifiedMillis(final Context context) {
        if (context == null) {
            return 0L;
        }
        final File file = new File(context.getFilesDir(), RESULT_FILE);
        return file.isFile() ? file.lastModified() : 0L;
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

    static final class Check {
        final State state;
        final String code;
        final String label;
        final String detail;

        Check(final State state, final String code,
                final String label, final String detail) {
            this.state = state;
            this.code = code;
            this.label = label;
            this.detail = clean(detail);
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("code", code).put("state", state.name())
                    .put("label", label).put("detail", detail);
        }
    }

    static final class StopAfterFirstFailure extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String code;

        StopAfterFirstFailure(final String code) {
            super(code);
            this.code = code;
        }
    }
}

package io.github.mekhontsev.magicdesk;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CopyOnWriteArrayList;

/** Single process-local source of truth for the current self-test lifecycle. */
final class DesktopSelfTestRunState {
    static int preparedDisplayId() {
        final java.util.List<DesktopSessionSnapshot> sessions = DesktopRuntimeBridge.getWorkspaces();
        if (sessions.size() != 1) { return android.view.Display.INVALID_DISPLAY; }
        final DesktopSessionSnapshot session = sessions.get(0);
        return session.target() != null && session.policy() == DesktopSessionPolicy.ISOLATED_SELF_TEST
                ? session.target().workspaceDisplayId : android.view.Display.INVALID_DISPLAY;
    }

    enum State {
        IDLE,
        STARTING,
        RUNNING,
        CLEANUP,
        COMPLETED,
        CANCELLED;

        String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }

        boolean active() {
            return this == STARTING || this == RUNNING || this == CLEANUP;
        }

        boolean terminal() {
            return this == COMPLETED || this == CANCELLED;
        }
    }

    enum CancellationStatus {
        ACCEPTED,
        ALREADY_REQUESTED,
        NOT_ACTIVE,
        RUN_MISMATCH,
        CLEANUP_STARTED
    }

    private static final Object LOCK = new Object();
    private static final AtomicLong NEXT_RUN_ID = new AtomicLong(
            System.currentTimeMillis());

    private static final CopyOnWriteArrayList<Runnable> LISTENERS =
            new CopyOnWriteArrayList<>();

    private static Snapshot sSnapshot = Snapshot.idle();
    private static Runnable sPreparationCancellationHandler;
    private static int sExpectedSessionCloseDisplayId = -1;

    private DesktopSelfTestRunState() {
    }

    static long beginRequest(
            final String target,
            final DesktopSelfTestExecutionPolicy mode,
            final long requestedAtMillis) {
        final Snapshot snapshot;
        synchronized (LOCK) {
            if (sSnapshot.state.active()) {
                return 0L;
            }
            final long runId = NEXT_RUN_ID.incrementAndGet();
            snapshot = new Snapshot(
                    runId,
                    State.STARTING,
                    clean(target),
                    modeName(mode),
                    "PREPARE",
                    "",
                    "",
                    Math.max(0L, requestedAtMillis),
                    0L,
                    0L,
                    "request accepted", Progress.EMPTY, "pending");
            sSnapshot = snapshot;
            sPreparationCancellationHandler = null;
            sExpectedSessionCloseDisplayId = -1;
        }
        record(snapshot, "starting", true);
        return snapshot.runId;
    }

    static long startRun(
            final long requestedRunId,
            final String target,
            final DesktopSelfTestExecutionPolicy mode,
            final long startedAtMillis) {
        final Snapshot snapshot;
        synchronized (LOCK) {
            final Snapshot current = sSnapshot;
            final long runId;
            final long requestedAt;
            final String selectedTarget;
            final String selectedMode;
            final String cancellationReason;
            if (requestedRunId > 0L) {
                if (current.runId != requestedRunId
                        || current.state != State.STARTING) {
                    return 0L;
                }
                runId = requestedRunId;
                requestedAt = current.requestedAtMillis;
                selectedTarget = current.target.isEmpty()
                        ? clean(target) : current.target;
                selectedMode = current.mode.isEmpty()
                        ? modeName(mode) : current.mode;
                cancellationReason = current.cancellationReason;
            } else {
                if (current.state.active()) {
                    return 0L;
                }
                runId = NEXT_RUN_ID.incrementAndGet();
                requestedAt = Math.max(0L, startedAtMillis);
                selectedTarget = clean(target);
                selectedMode = modeName(mode);
                cancellationReason = "";
            }
            snapshot = new Snapshot(
                    runId,
                    State.RUNNING,
                    selectedTarget,
                    selectedMode,
                    "PREPARE",
                    current.runId == runId
                            ? current.lastCompletedStage : "",
                    cancellationReason,
                    requestedAt,
                    Math.max(requestedAt, startedAtMillis),
                    0L,
                    "self-test running", Progress.EMPTY, "pending");
            sSnapshot = snapshot;
            sPreparationCancellationHandler = null;
            if (current.runId != runId) sExpectedSessionCloseDisplayId = -1;
        }
        record(snapshot, "running", true);
        return snapshot.runId;
    }

    static void stage(final long runId, final String stage) {
        stage(runId, stage, "");
    }

    static void stage(final long runId, final String stage, final String label) {
        synchronized (LOCK) {
            if (runId <= 0L || sSnapshot.runId != runId
                    || (sSnapshot.state != State.RUNNING
                            && sSnapshot.state != State.STARTING)) {
                return;
            }
            if (sSnapshot.stage.equals(clean(stage))
                    && sSnapshot.progress.stageLabel.equals(clean(label))) {
                return;
            }
            sSnapshot = sSnapshot.withStage(clean(stage), clean(label));
        }
        notifyChanged();
    }

    static void checkCompleted(final long runId, final String stage,
            final DesktopSelfTestResult.State state,
            final String label, final String detail) {
        synchronized (LOCK) {
            if (runId <= 0L || sSnapshot.runId != runId
                    || (sSnapshot.state != State.RUNNING
                            && sSnapshot.state != State.CLEANUP)) {
                return;
            }
            sSnapshot = sSnapshot.withLastCompletedStage(
                    clean(stage), state, clean(label), detail);
        }
        notifyChanged();
    }

    static void addListener(final Runnable listener) {
        LISTENERS.addIfAbsent(listener);
    }

    static void removeListener(final Runnable listener) {
        LISTENERS.remove(listener);
    }

    // Invalidation callbacks read the latest snapshot; concurrent publishers
    // cannot deliver an older stage after a newer one.
    private static void notifyChanged() {
        for (Runnable listener : LISTENERS) {
            try {
                listener.run();
            } catch (RuntimeException error) {
                DesktopAutomationEventJournal.record(
                        "self_test", "progress_listener_failed", false, error.toString());
            }
        }
    }

    static void beginCleanup(final long runId) {
        final Snapshot snapshot;
        synchronized (LOCK) {
            if (runId <= 0L || sSnapshot.runId != runId
                    || sSnapshot.state != State.RUNNING) {
                return;
            }
            snapshot = sSnapshot.withState(
                    State.CLEANUP, "CLEANUP", "cleanup running", 0L, "pending");
            sSnapshot = snapshot;
        }
        record(snapshot, "cleanup", true);
    }

    static void complete(
            final long runId,
            final boolean cancelled,
            final boolean successful,
            final long completedAtMillis,
            final String detail,
            final long resultModifiedAtMillis) {
        final Snapshot snapshot;
        synchronized (LOCK) {
            if (runId <= 0L || sSnapshot.runId != runId
                    || !sSnapshot.state.active()) {
                return;
            }
            snapshot = sSnapshot.withState(
                    cancelled ? State.CANCELLED : State.COMPLETED,
                    cancelled ? "CANCELLED" : "COMPLETE",
                    clean(detail),
                    Math.max(sSnapshot.requestedAtMillis, completedAtMillis),
                    cancelled ? "cancelled" : !successful ? "failed"
                            : sSnapshot.progress.warnings > 0 ? "warnings" : "passed");
            sSnapshot = snapshot;
            sPreparationCancellationHandler = null;
        }
        record(snapshot, cancelled ? "cancelled" : "finished",
                cancelled || successful, resultModifiedAtMillis);
    }

    // Only the display-removal fixture declares this immediately before removing
    // its own display. An unrelated session loss must still cancel the test.
    static void expectSessionClose(final long runId, final int displayId) {
        synchronized (LOCK) {
            if (sSnapshot.runId == runId && sSnapshot.state == State.RUNNING
                    && !sSnapshot.cancellationRequested) {
                sExpectedSessionCloseDisplayId = displayId;
            }
        }
    }

    static boolean registerPreparationCancellationHandler(
            final long runId,
            final Runnable handler) {
        final boolean invokeNow;
        synchronized (LOCK) {
            if (runId <= 0L || sSnapshot.runId != runId
                    || sSnapshot.state != State.STARTING) {
                return false;
            }
            sPreparationCancellationHandler = handler;
            invokeNow = sSnapshot.cancellationRequested && handler != null;
        }
        if (invokeNow) {
            handler.run();
        }
        return true;
    }

    static void clearPreparationCancellationHandler(final long runId) {
        synchronized (LOCK) {
            if (sSnapshot.runId == runId) {
                sPreparationCancellationHandler = null;
            }
        }
    }

    static CancellationStatus requestCancellation(final long requestedRunId) {
        return requestCancellation(
                requestedRunId,
                "cancellation requested",
                "cancel_requested");
    }

    static void noteDesktopSessionClosed(
            final DesktopSessionPolicy policy,
            final int displayId) {
        if (policy != DesktopSessionPolicy.ISOLATED_SELF_TEST) {
            return;
        }
        final long runId;
        synchronized (LOCK) {
            runId = sSnapshot.runId;
            if (sExpectedSessionCloseDisplayId == displayId) {
                return;
            }
        }
        requestCancellation(
                runId,
                "desktop session closed on display " + displayId,
                "session_closed");
    }

    private static CancellationStatus requestCancellation(
            final long requestedRunId,
            final String detail,
            final String operation) {
        final Snapshot snapshot;
        final CancellationStatus status;
        final Runnable handler;
        synchronized (LOCK) {
            final Snapshot current = sSnapshot;
            if (!current.state.active()) {
                return CancellationStatus.NOT_ACTIVE;
            }
            if (requestedRunId <= 0L || current.runId != requestedRunId) {
                return CancellationStatus.RUN_MISMATCH;
            }
            if (current.state == State.CLEANUP) {
                return current.cancellationRequested
                        ? CancellationStatus.ALREADY_REQUESTED
                        : CancellationStatus.CLEANUP_STARTED;
            }
            if (current.cancellationRequested) {
                return CancellationStatus.ALREADY_REQUESTED;
            }
            snapshot = current.withCancellationRequested(clean(detail),
                    "session_closed".equals(operation) ? "session_closed" : "user");
            sSnapshot = snapshot;
            status = CancellationStatus.ACCEPTED;
            handler = current.state == State.STARTING
                    ? sPreparationCancellationHandler : null;
        }
        record(snapshot, operation, true);
        if (handler != null) {
            handler.run();
        }
        return status;
    }

    static boolean isCancellationRequested() {
        synchronized (LOCK) {
            return sSnapshot.state.active()
                    && sSnapshot.cancellationRequested;
        }
    }

    static void checkpoint() {
        synchronized (LOCK) {
            if (sSnapshot.state == State.RUNNING
                    && sSnapshot.cancellationRequested) {
                throw new Cancelled();
            }
        }
    }

    static boolean isExecuting() {
        synchronized (LOCK) {
            return sSnapshot.state == State.RUNNING
                    || sSnapshot.state == State.CLEANUP;
        }
    }

    static boolean isActive() {
        synchronized (LOCK) {
            return sSnapshot.state.active();
        }
    }

    static boolean isActive(final long runId) {
        synchronized (LOCK) {
            return runId > 0L && sSnapshot.runId == runId
                    && sSnapshot.state.active();
        }
    }

    static boolean isStarting(final long runId) {
        synchronized (LOCK) {
            return runId > 0L && sSnapshot.runId == runId
                    && sSnapshot.state == State.STARTING;
        }
    }

    static Snapshot snapshot() {
        synchronized (LOCK) {
            return sSnapshot;
        }
    }

    static void resetForTests() {
        synchronized (LOCK) {
            LISTENERS.clear();
            sSnapshot = Snapshot.idle();
            sPreparationCancellationHandler = null;
            sExpectedSessionCloseDisplayId = -1;
        }
    }

    private static String modeName(
            final DesktopSelfTestExecutionPolicy mode) {
        return (mode == null ? DesktopSelfTestExecutionPolicy.FULL : mode)
                .wireName();
    }

    private static void record(
            final Snapshot snapshot,
            final String operation,
            final boolean success) {
        record(snapshot, operation, success, 0L);
    }

    private static void record(
            final Snapshot snapshot,
            final String operation,
            final boolean success,
            final long resultModifiedAtMillis) {
        notifyChanged();
        try {
            final JSONObject data = snapshot.toJson();
            if (resultModifiedAtMillis > 0L) {
                data.put("resultModifiedAtMillis", resultModifiedAtMillis);
            }
            DesktopAutomationEventJournal.record(
                    "self_test", operation, success, snapshot.detail, data);
        } catch (JSONException ignored) {
            DesktopAutomationEventJournal.record(
                    "self_test", operation, success, snapshot.detail);
        }
    }

    private static String clean(final String value) {
        if (value == null) {
            return "";
        }
        final String normalized = value.replace('\u0000', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        return normalized.length() <= 256
                ? normalized : normalized.substring(0, 256);
    }

    static final class Snapshot {
        final long runId;
        final State state;
        final String target;
        final String mode;
        final String stage;
        final String lastCompletedStage;
        final boolean cancellationRequested;
        final String cancellationReason;
        final String outcome;
        final long requestedAtMillis;
        final long startedAtMillis;
        final long completedAtMillis;
        final String detail;
        final Progress progress;

        Snapshot(
                final long runId,
                final State state,
                final String target,
                final String mode,
                final String stage,
                final String lastCompletedStage,
                final String cancellationReason,
                final long requestedAtMillis,
                final long startedAtMillis,
                final long completedAtMillis,
                final String detail,
                final Progress progress,
                final String outcome) {
            this.runId = runId;
            this.state = state;
            this.target = target;
            this.mode = mode;
            this.stage = stage;
            this.lastCompletedStage = lastCompletedStage;
            this.cancellationRequested = !cancellationReason.isEmpty();
            this.cancellationReason = cancellationReason;
            this.outcome = outcome;
            this.requestedAtMillis = requestedAtMillis;
            this.startedAtMillis = startedAtMillis;
            this.completedAtMillis = completedAtMillis;
            this.detail = detail;
            this.progress = progress;
        }

        boolean active() {
            return state.active();
        }

        boolean terminal() {
            return state.terminal();
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("runId", runId > 0L ? runId : JSONObject.NULL)
                    .put("state", state.wireName())
                    .put("active", active())
                    .put("target", target.isEmpty() ? JSONObject.NULL : target)
                    .put("mode", mode.isEmpty() ? JSONObject.NULL : mode)
                    .put("stage", stage.isEmpty() ? JSONObject.NULL : stage)
                    .put("lastCompletedStage", lastCompletedStage.isEmpty()
                            ? JSONObject.NULL : lastCompletedStage)
                    .put("cancelRequested", cancellationRequested)
                    .put("cancellationReason", cancellationReason.isEmpty()
                            ? JSONObject.NULL : cancellationReason)
                    .put("outcome", outcome)
                    .put("requestedAtMillis", nullableTimestamp(
                            requestedAtMillis))
                    .put("startedAtMillis", nullableTimestamp(startedAtMillis))
                    .put("completedAtMillis", nullableTimestamp(
                            completedAtMillis))
                    .put("detail", detail)
                    .put("progress", progress.toJson());
        }

        private Snapshot withStage(final String value, final String label) {
            return new Snapshot(
                    runId, state, target, mode, value, lastCompletedStage,
                    cancellationReason,
                    requestedAtMillis, startedAtMillis, completedAtMillis,
                    detail, progress.withStageLabel(label), outcome);
        }

        private Snapshot withLastCompletedStage(final String value,
                final DesktopSelfTestResult.State result,
                final String label, final String checkDetail) {
            return new Snapshot(
                    runId, state, target, mode, stage, value,
                    cancellationReason,
                    requestedAtMillis, startedAtMillis, completedAtMillis,
                    detail, progress.completed(value, result, label, checkDetail), outcome);
        }

        private Snapshot withCancellationRequested(final String detail, final String reason) {
            return new Snapshot(
                    runId, state, target, mode, stage, lastCompletedStage, reason,
                    requestedAtMillis, startedAtMillis, completedAtMillis,
                    detail, progress, outcome);
        }

        private Snapshot withState(
                final State value,
                final String currentStage,
                final String currentDetail,
                final long completedAt,
                final String outcome) {
            return new Snapshot(
                    runId, value, target, mode, currentStage,
                    lastCompletedStage,
                    cancellationReason,
                    requestedAtMillis, startedAtMillis, completedAt,
                    currentDetail, progress.withStageLabel(""), outcome);
        }

        private static Snapshot idle() {
            return new Snapshot(
                    0L, State.IDLE, "", "", "", "", "",
                    0L, 0L, 0L, "no run in this process", Progress.EMPTY, "none");
        }

        private static Object nullableTimestamp(final long value) {
            return value > 0L ? Long.valueOf(value) : JSONObject.NULL;
        }
    }

    static final class Progress {
        static final Progress EMPTY = new Progress("", "", "", "", 0, 0, 0, 0,
                List.of(), null);
        static final int MAX_CHECKS = 512;
        final List<DesktopSelfTestResult.Check> checks;
        final DesktopSelfTestResult.Check firstFailure;
        final String stageLabel;
        final String lastLabel;
        final String lastResult;
        final String lastDetail;
        final int passed;
        final int warnings;
        final int failed;
        final int notTested;

        private Progress(final String stageLabel, final String lastLabel,
                final String lastResult, final String lastDetail, final int passed,
                final int warnings, final int failed, final int notTested,
                final List<DesktopSelfTestResult.Check> checks,
                final DesktopSelfTestResult.Check firstFailure) {
            this.stageLabel = stageLabel;
            this.lastLabel = lastLabel;
            this.lastResult = lastResult;
            this.lastDetail = lastDetail;
            this.passed = passed;
            this.warnings = warnings;
            this.failed = failed;
            this.notTested = notTested;
            this.checks = checks;
            this.firstFailure = firstFailure;
        }

        Progress withStageLabel(final String label) {
            return new Progress(label, lastLabel, lastResult, lastDetail,
                    passed, warnings, failed, notTested, checks, firstFailure);
        }

        Progress completed(final String code, final DesktopSelfTestResult.State result,
                final String label, final String detail) {
            final DesktopSelfTestResult.Check check =
                    new DesktopSelfTestResult.Check(result, code, label, detail);
            final List<DesktopSelfTestResult.Check> updated = new ArrayList<>(checks);
            if (updated.size() == MAX_CHECKS) updated.remove(0);
            updated.add(check);
            return new Progress(stageLabel, label, result.name(), detail,
                    passed + (result == DesktopSelfTestResult.State.PASS ? 1 : 0),
                    warnings + (result == DesktopSelfTestResult.State.WARN ? 1 : 0),
                    failed + (result == DesktopSelfTestResult.State.FAIL ? 1 : 0),
                    notTested + (result == DesktopSelfTestResult.State.NOT_TESTED ? 1 : 0),
                    List.copyOf(updated), firstFailure != null ? firstFailure
                            : result == DesktopSelfTestResult.State.FAIL ? check : null);
        }

        JSONObject checksJson() throws JSONException {
            final JSONArray values = new JSONArray();
            final JSONArray failures = new JSONArray();
            for (DesktopSelfTestResult.Check check : checks) {
                values.put(check.toJson());
                if (check.state == DesktopSelfTestResult.State.FAIL) failures.put(check.toJson());
            }
            return new JSONObject().put("checks", values)
                    .put("failures", failures)
                    .put("checksTruncated", passed + warnings + failed + notTested > checks.size())
                    .put("firstFailure", firstFailure == null ? JSONObject.NULL : firstFailure.toJson());
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject().put("stageLabel", stageLabel)
                    .put("lastLabel", lastLabel).put("lastResult", lastResult)
                    .put("lastDetail", lastDetail).put("passed", passed)
                    .put("warnings", warnings).put("failed", failed)
                    .put("notTested", notTested);
        }
    }

    static final class Cancelled extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}

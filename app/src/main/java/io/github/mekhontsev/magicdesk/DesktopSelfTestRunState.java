package io.github.mekhontsev.magicdesk;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CopyOnWriteArrayList;

/** Single process-local source of truth for the current self-test lifecycle. */
final class DesktopSelfTestRunState {
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
                    false,
                    Math.max(0L, requestedAtMillis),
                    0L,
                    0L,
                    "request accepted", Progress.EMPTY);
            sSnapshot = snapshot;
            sPreparationCancellationHandler = null;
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
            final boolean cancellationRequested;
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
                cancellationRequested = current.cancellationRequested;
            } else {
                if (current.state.active()) {
                    return 0L;
                }
                runId = NEXT_RUN_ID.incrementAndGet();
                requestedAt = Math.max(0L, startedAtMillis);
                selectedTarget = clean(target);
                selectedMode = modeName(mode);
                cancellationRequested = false;
            }
            snapshot = new Snapshot(
                    runId,
                    State.RUNNING,
                    selectedTarget,
                    selectedMode,
                    "PREPARE",
                    current.runId == runId
                            ? current.lastCompletedStage : "",
                    cancellationRequested,
                    requestedAt,
                    Math.max(requestedAt, startedAtMillis),
                    0L,
                    "self-test running", Progress.EMPTY);
            sSnapshot = snapshot;
            sPreparationCancellationHandler = null;
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
                    clean(stage), state, clean(label), clean(detail));
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
                    State.CLEANUP, "CLEANUP", "cleanup running", 0L);
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
                    Math.max(sSnapshot.requestedAtMillis, completedAtMillis));
            sSnapshot = snapshot;
            sPreparationCancellationHandler = null;
        }
        record(snapshot, cancelled ? "cancelled" : "finished",
                cancelled || successful, resultModifiedAtMillis);
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
            snapshot = current.withCancellationRequested(clean(detail));
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
                final boolean cancellationRequested,
                final long requestedAtMillis,
                final long startedAtMillis,
                final long completedAtMillis,
                final String detail,
                final Progress progress) {
            this.runId = runId;
            this.state = state;
            this.target = target;
            this.mode = mode;
            this.stage = stage;
            this.lastCompletedStage = lastCompletedStage;
            this.cancellationRequested = cancellationRequested;
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
                    cancellationRequested,
                    requestedAtMillis, startedAtMillis, completedAtMillis,
                    detail, progress.withStageLabel(label));
        }

        private Snapshot withLastCompletedStage(final String value,
                final DesktopSelfTestResult.State result,
                final String label, final String checkDetail) {
            return new Snapshot(
                    runId, state, target, mode, stage, value,
                    cancellationRequested,
                    requestedAtMillis, startedAtMillis, completedAtMillis,
                    detail, progress.completed(result, label, checkDetail));
        }

        private Snapshot withCancellationRequested(final String detail) {
            return new Snapshot(
                    runId, state, target, mode, stage, lastCompletedStage, true,
                    requestedAtMillis, startedAtMillis, completedAtMillis,
                    detail, progress);
        }

        private Snapshot withState(
                final State value,
                final String currentStage,
                final String currentDetail,
                final long completedAt) {
            return new Snapshot(
                    runId, value, target, mode, currentStage,
                    lastCompletedStage,
                    cancellationRequested,
                    requestedAtMillis, startedAtMillis, completedAt,
                    currentDetail, progress.withStageLabel(""));
        }

        private static Snapshot idle() {
            return new Snapshot(
                    0L, State.IDLE, "", "", "", "", false,
                    0L, 0L, 0L, "no run in this process", Progress.EMPTY);
        }

        private static Object nullableTimestamp(final long value) {
            return value > 0L ? Long.valueOf(value) : JSONObject.NULL;
        }
    }

    static final class Progress {
        static final Progress EMPTY = new Progress("", "", "", "", 0, 0, 0, 0);
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
                final int warnings, final int failed, final int notTested) {
            this.stageLabel = stageLabel;
            this.lastLabel = lastLabel;
            this.lastResult = lastResult;
            this.lastDetail = lastDetail;
            this.passed = passed;
            this.warnings = warnings;
            this.failed = failed;
            this.notTested = notTested;
        }

        Progress withStageLabel(final String label) {
            return new Progress(label, lastLabel, lastResult, lastDetail,
                    passed, warnings, failed, notTested);
        }

        Progress completed(final DesktopSelfTestResult.State result,
                final String label, final String detail) {
            return new Progress(stageLabel, label, result.name(), detail,
                    passed + (result == DesktopSelfTestResult.State.PASS ? 1 : 0),
                    warnings + (result == DesktopSelfTestResult.State.WARN ? 1 : 0),
                    failed + (result == DesktopSelfTestResult.State.FAIL ? 1 : 0),
                    notTested + (result == DesktopSelfTestResult.State.NOT_TESTED ? 1 : 0));
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

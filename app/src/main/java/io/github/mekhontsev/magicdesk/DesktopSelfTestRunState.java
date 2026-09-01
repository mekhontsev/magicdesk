package io.github.mekhontsev.magicdesk;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

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
                    "request accepted");
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
                    "self-test running");
            sSnapshot = snapshot;
            sPreparationCancellationHandler = null;
        }
        record(snapshot, "running", true);
        return snapshot.runId;
    }

    static void stage(final long runId, final String stage) {
        synchronized (LOCK) {
            if (runId <= 0L || sSnapshot.runId != runId
                    || sSnapshot.state != State.RUNNING) {
                return;
            }
            sSnapshot = sSnapshot.withStage(clean(stage));
        }
    }

    static void checkCompleted(final long runId, final String stage) {
        synchronized (LOCK) {
            if (runId <= 0L || sSnapshot.runId != runId
                    || (sSnapshot.state != State.RUNNING
                            && sSnapshot.state != State.CLEANUP)) {
                return;
            }
            sSnapshot = sSnapshot.withLastCompletedStage(clean(stage));
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
            snapshot = current.withCancellationRequested();
            sSnapshot = snapshot;
            status = CancellationStatus.ACCEPTED;
            handler = current.state == State.STARTING
                    ? sPreparationCancellationHandler : null;
        }
        record(snapshot, "cancel_requested", true);
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
                final String detail) {
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
                    .put("detail", detail);
        }

        private Snapshot withStage(final String value) {
            return new Snapshot(
                    runId, state, target, mode, value, lastCompletedStage,
                    cancellationRequested,
                    requestedAtMillis, startedAtMillis, completedAtMillis,
                    detail);
        }

        private Snapshot withLastCompletedStage(final String value) {
            return new Snapshot(
                    runId, state, target, mode, stage, value,
                    cancellationRequested,
                    requestedAtMillis, startedAtMillis, completedAtMillis,
                    detail);
        }

        private Snapshot withCancellationRequested() {
            return new Snapshot(
                    runId, state, target, mode, stage, lastCompletedStage, true,
                    requestedAtMillis, startedAtMillis, completedAtMillis,
                    "cancellation requested");
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
                    currentDetail);
        }

        private static Snapshot idle() {
            return new Snapshot(
                    0L, State.IDLE, "", "", "", "", false,
                    0L, 0L, 0L, "no run in this process");
        }

        private static Object nullableTimestamp(final long value) {
            return value > 0L ? Long.valueOf(value) : JSONObject.NULL;
        }
    }

    static final class Cancelled extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}

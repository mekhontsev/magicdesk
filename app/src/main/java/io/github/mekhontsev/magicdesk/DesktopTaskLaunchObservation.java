package io.github.mekhontsev.magicdesk;

import android.os.SystemClock;

/** Event-driven confirmation of the final task produced by an Activity launch. */
final class DesktopTaskLaunchObservation {
    final TaskRepository.TaskEntry task;
    final String error;

    private DesktopTaskLaunchObservation(
            final TaskRepository.TaskEntry task,
            final String error) {
        this.task = task;
        this.error = error == null ? "" : error;
    }

    static DesktopTaskLaunchObservation await(
            final LaunchActivityIdentity target,
            final DesktopLaunchMode requestedMode,
            final int displayId,
            final int preferredTaskId,
            final long timeoutMillis) throws InterruptedException {
        if (target == null) {
            throw new IllegalArgumentException(
                    "task launch target is required");
        }
        return awaitTask(
                target,
                requestedMode,
                displayId,
                preferredTaskId,
                timeoutMillis);
    }

    static DesktopTaskLaunchObservation awaitTopology(
            final DesktopLaunchMode requestedMode,
            final int displayId,
            final int taskId,
            final long timeoutMillis) throws InterruptedException {
        return awaitTask(
                null,
                requestedMode,
                displayId,
                taskId,
                timeoutMillis);
    }

    private static DesktopTaskLaunchObservation awaitTask(
            final LaunchActivityIdentity target,
            final DesktopLaunchMode requestedMode,
            final int displayId,
            final int preferredTaskId,
            final long timeoutMillis) throws InterruptedException {
        if (requestedMode == null || displayId < 0 || preferredTaskId < 0
                || timeoutMillis <= 0L) {
            throw new IllegalArgumentException(
                    "invalid task launch observation request");
        }
        final long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        long observedEventId = DesktopAutomationEventJournal.latestId();
        String lastError = "launched task did not appear";
        while (true) {
            final Evaluation evaluation = evaluate(
                    TaskRepository.loadAllNow(),
                    target,
                    requestedMode,
                    displayId,
                    preferredTaskId);
            if (evaluation.task != null) {
                return new DesktopTaskLaunchObservation(
                        evaluation.task, "");
            }
            if (!evaluation.error.isEmpty()) {
                lastError = evaluation.error;
            }
            final long remaining = deadline - SystemClock.uptimeMillis();
            if (remaining <= 0L) {
                return new DesktopTaskLaunchObservation(null, lastError);
            }
            observedEventId = DesktopAutomationEventJournal.awaitChange(
                    observedEventId, remaining);
        }
    }

    static Evaluation evaluate(
            final TaskRepository.Snapshot snapshot,
            final LaunchActivityIdentity target,
            final DesktopLaunchMode requestedMode,
            final int displayId,
            final int preferredTaskId) {
        if (snapshot == null || !snapshot.available) {
            return Evaluation.failure(snapshot == null
                    ? "task observation unavailable"
                    : snapshot.error);
        }
        TaskRepository.TaskEntry candidate = null;
        for (final TaskRepository.TaskEntry task : snapshot.tasks) {
            if (task.displayId != displayId) {
                continue;
            }
            if (task.taskId == preferredTaskId) {
                candidate = task;
                break;
            }
        }
        if (candidate == null) {
            return Evaluation.failure("launched task did not appear");
        }
        if (target != null && !target.matchesTask(candidate)) {
            return Evaluation.failure(
                    "observed task identity does not match the request");
        }
        if (candidate.activityType
                != FrameworkTaskSnapshot.ACTIVITY_TYPE_STANDARD) {
            return Evaluation.failure("observed activityType="
                    + candidate.activityType + " instead of STANDARD");
        }
        if (requestedMode != DesktopLaunchMode.AUTO
                && !DesktopLaunchMode.matchesWindowingMode(
                        requestedMode.wireName,
                        candidate.windowingMode)) {
            return Evaluation.failure("observed mode="
                    + candidate.windowingMode + " instead of "
                    + requestedMode.wireName);
        }
        return Evaluation.success(candidate);
    }

    static final class Evaluation {
        final TaskRepository.TaskEntry task;
        final String error;

        private Evaluation(
                final TaskRepository.TaskEntry task,
                final String error) {
            this.task = task;
            this.error = error == null ? "" : error;
        }

        static Evaluation success(final TaskRepository.TaskEntry task) {
            return new Evaluation(task, "");
        }

        static Evaluation failure(final String error) {
            return new Evaluation(null, error);
        }
    }
}

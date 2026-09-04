package io.github.mekhontsev.magicdesk;

import android.os.SystemClock;

/** Typed completion of an Activity launch through a desktop launch context. */
final class DesktopActivityLaunchResult {
    enum Outcome {
        OBSERVED_TASK,
        UNMANAGED_ACCEPTED,
        DEFINITIVE_FAILURE,
        INDETERMINATE_FAILURE
    }

    interface Completion {
        void onComplete(DesktopActivityLaunchResult result);
    }

    static final class Awaiter implements Completion {
        private DesktopActivityLaunchResult mResult;

        @Override
        public synchronized void onComplete(
                final DesktopActivityLaunchResult result) {
            if (mResult != null) {
                return;
            }
            mResult = result == null
                    ? failed("launch completed without a result") : result;
            notifyAll();
        }

        synchronized DesktopActivityLaunchResult await(
                final long timeoutMillis) {
            if (timeoutMillis <= 0L) {
                throw new IllegalArgumentException(
                        "launch completion timeout must be positive");
            }
            final long deadline = SystemClock.uptimeMillis() + timeoutMillis;
            while (mResult == null) {
                final long remaining = deadline - SystemClock.uptimeMillis();
                if (remaining <= 0L) {
                    break;
                }
                try {
                    EventDrivenWaits.await(
                            this,
                            EventDrivenWaits.Reason.ACTIVITY_LAUNCH_RESULT,
                            remaining);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return indeterminateFailure(
                            "Activity launch wait was interrupted");
                }
            }
            return mResult == null
                    ? indeterminateFailure(
                            "Activity launch completion timed out")
                    : mResult;
        }
    }

    final Outcome outcome;
    final int taskId;
    final int displayId;
    final boolean reused;
    final String error;

    private DesktopActivityLaunchResult(
            final Outcome outcome,
            final int taskId,
            final int displayId,
            final boolean reused,
            final String error) {
        this.outcome = outcome;
        this.taskId = taskId;
        this.displayId = displayId;
        this.reused = reused;
        this.error = error == null ? "" : error;
        validate();
    }

    static DesktopActivityLaunchResult observedTask(
            final int taskId,
            final int displayId,
            final boolean reused) {
        return new DesktopActivityLaunchResult(
                Outcome.OBSERVED_TASK,
                taskId,
                displayId,
                reused,
                "");
    }

    static DesktopActivityLaunchResult unmanagedAccepted(
            final int displayId) {
        return new DesktopActivityLaunchResult(
                Outcome.UNMANAGED_ACCEPTED,
                -1,
                displayId,
                false,
                "");
    }

    static DesktopActivityLaunchResult failed(final Throwable error) {
        return failed(ShellAccess.usefulMessage(error));
    }

    static DesktopActivityLaunchResult failed(final String error) {
        return failure(Outcome.DEFINITIVE_FAILURE, error);
    }

    private static DesktopActivityLaunchResult indeterminateFailure(
            final String error) {
        return failure(Outcome.INDETERMINATE_FAILURE, error);
    }

    private static DesktopActivityLaunchResult failure(
            final Outcome outcome,
            final String error) {
        final String message = error == null || error.isBlank()
                ? "Activity launch failed" : error;
        return new DesktopActivityLaunchResult(
                outcome, -1, -1, false, message);
    }

    boolean succeeded() {
        return outcome == Outcome.OBSERVED_TASK
                || outcome == Outcome.UNMANAGED_ACCEPTED;
    }

    boolean hasObservedTask() {
        return outcome == Outcome.OBSERVED_TASK;
    }

    boolean isDefinitiveFailure() {
        return outcome == Outcome.DEFINITIVE_FAILURE;
    }

    private void validate() {
        if (outcome == null) {
            throw new IllegalArgumentException("launch outcome is required");
        }
        if (outcome == Outcome.OBSERVED_TASK) {
            if (taskId < 0 || displayId < 0 || !error.isEmpty()) {
                throw new IllegalArgumentException(
                        "observed launch requires task and display ids");
            }
            return;
        }
        if (outcome == Outcome.UNMANAGED_ACCEPTED) {
            if (taskId >= 0 || displayId < 0 || reused || !error.isEmpty()) {
                throw new IllegalArgumentException(
                        "invalid unmanaged Activity acceptance");
            }
            return;
        }
        if (taskId >= 0 || displayId >= 0 || reused || error.isEmpty()) {
            throw new IllegalArgumentException("invalid Activity launch failure");
        }
    }
}

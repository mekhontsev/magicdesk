package io.github.mekhontsev.magicdesk;

import java.util.concurrent.atomic.AtomicBoolean;

/** Runs desktop cleanup in order without allowing one failed step to block exit. */
final class MagicDeskExitCoordinator {
    enum Step {
        RESTORE_HARDWARE,
        RESTORE_PHONE_SCREEN,
        RETURN_CONSOLE_TASKS,
        CLEAN_PHONE_TASKS,
        RESTORE_MIRROR
    }

    interface Callback {
        void onComplete(boolean success);
    }

    interface AsyncStep {
        void run(Callback callback);
    }

    interface Operations {
        void restoreHardware(Callback callback);

        void restorePhoneScreen(Callback callback);

        void returnConsoleTasks(Callback callback);

        void cleanPhoneTasks(Callback callback);

        void restoreMirror(Callback callback);

        void finishExit();
    }

    interface FailureListener {
        void onFailure(Step step, Throwable error);
    }

    private final Operations mOperations;
    private final FailureListener mFailures;

    MagicDeskExitCoordinator(
            final Operations operations,
            final FailureListener failures) {
        mOperations = operations;
        mFailures = failures;
    }

    void start() {
        runStep(
                Step.RESTORE_HARDWARE,
                mOperations::restoreHardware,
                () -> runStep(
                        Step.RESTORE_PHONE_SCREEN,
                        mOperations::restorePhoneScreen,
                        () -> runStep(
                                Step.RETURN_CONSOLE_TASKS,
                                mOperations::returnConsoleTasks,
                                () -> runStep(
                                        Step.CLEAN_PHONE_TASKS,
                                        mOperations::cleanPhoneTasks,
                                        () -> runStep(
                                                Step.RESTORE_MIRROR,
                                                mOperations::restoreMirror,
                                                mOperations::finishExit)))));
    }

    private void runStep(
            final Step step,
            final AsyncStep operation,
            final Runnable continuation) {
        final AtomicBoolean completed = new AtomicBoolean();
        try {
            operation.run(success -> {
                if (!completed.compareAndSet(false, true)) {
                    return;
                }
                if (!success) {
                    mFailures.onFailure(step, null);
                }
                continuation.run();
            });
        } catch (RuntimeException error) {
            if (completed.compareAndSet(false, true)) {
                mFailures.onFailure(step, error);
                continuation.run();
            }
        }
    }
}

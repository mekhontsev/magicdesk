package io.github.mekhontsev.magicdesk;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/** Dispatcher-confined queue covering snapshot, commit, and acknowledgement. */
final class DesktopWorkspaceQueue {
    interface Completion extends TaskRepository.ActionCallback {
        boolean isCurrent();
    }

    interface Operation {
        void run(Completion completion);
    }

    private final Executor mDispatcher;
    private final ArrayDeque<Request> mPending = new ArrayDeque<>();
    private Request mActive;

    DesktopWorkspaceQueue(final Executor dispatcher) {
        mDispatcher = dispatcher;
    }

    void enqueue(final Operation operation,
            final TaskRepository.ActionCallback callback) {
        mPending.addLast(new Request(operation, callback));
        runNext();
    }

    boolean isRunning() {
        return mActive != null;
    }

    void cancelAll(final String message) {
        final List<Request> cancelled = new ArrayList<>();
        if (mActive != null) {
            cancelled.add(mActive);
            mActive = null;
        }
        cancelled.addAll(mPending);
        mPending.clear();
        for (final Request request : cancelled) {
            request.notifyResult(new TaskRepository.ActionResult(false, message));
        }
    }

    private void runNext() {
        if (mActive != null || mPending.isEmpty()) {
            return;
        }
        final Request request = mPending.removeFirst();
        mActive = request;
        try {
            request.operation.run(request);
        } catch (RuntimeException error) {
            request.onComplete(new TaskRepository.ActionResult(false,
                    error.getMessage() == null
                            ? error.getClass().getSimpleName() : error.getMessage()));
        }
    }

    private final class Request implements Completion {
        final Operation operation;
        final TaskRepository.ActionCallback callback;

        Request(final Operation operation, final TaskRepository.ActionCallback callback) {
            this.operation = operation;
            this.callback = callback;
        }

        @Override
        public boolean isCurrent() {
            return mActive == this;
        }

        @Override
        public void onComplete(final TaskRepository.ActionResult result) {
            mDispatcher.execute(() -> {
                if (!isCurrent()) {
                    return;
                }
                mActive = null;
                try {
                    notifyResult(result);
                } finally {
                    runNext();
                }
            });
        }

        void notifyResult(final TaskRepository.ActionResult result) {
            if (callback != null) {
                callback.onComplete(result);
            }
        }
    }
}

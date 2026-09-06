package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;

/** Owns each App Function from cancellation registration through service shutdown. */
final class AppFunctionRequestQueue implements AutoCloseable {
    private final ExecutorService mExecutor;
    private final Set<FutureTask<Void>> mPending = new LinkedHashSet<>();
    private boolean mClosed;

    AppFunctionRequestQueue(final ExecutorService executor) {
        mExecutor = executor;
    }

    FutureTask<Void> create(final Runnable action, final Runnable onCancelled) {
        final FutureTask<Void> request = new FutureTask<Void>(action, null) {
            @Override
            protected void done() {
                synchronized (AppFunctionRequestQueue.this) {
                    mPending.remove(this);
                }
                if (isCancelled()) {
                    onCancelled.run();
                }
            }
        };
        synchronized (this) {
            if (!mClosed) {
                mPending.add(request);
                return request;
            }
        }
        request.cancel(false);
        return request;
    }

    void execute(final FutureTask<Void> request) {
        if (request.isDone()) {
            return;
        }
        try {
            mExecutor.execute(request);
        } catch (RejectedExecutionException error) {
            request.cancel(true);
        }
    }

    synchronized int pendingCount() {
        return mPending.size();
    }

    @Override
    public void close() {
        final ArrayList<FutureTask<Void>> pending;
        synchronized (this) {
            mClosed = true;
            pending = new ArrayList<>(mPending);
        }
        try {
            for (final FutureTask<Void> request : pending) {
                try {
                    request.cancel(true);
                } catch (RuntimeException ignored) {
                    // A failed client callback must not strand other requests.
                }
            }
        } finally {
            mExecutor.shutdownNow();
        }
    }
}

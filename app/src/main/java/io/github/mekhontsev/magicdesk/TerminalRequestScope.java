package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Completes terminal metadata requests even when teardown discards queued work. */
final class TerminalRequestScope implements AutoCloseable {
    private final Executor mExecutor;
    private final Set<CompletableFuture<?>> mPending = new HashSet<>();
    private boolean mClosed;

    TerminalRequestScope(final Executor executor) {
        mExecutor = executor;
    }

    <T> CompletableFuture<T> submit(final Callable<T> operation) {
        final CompletableFuture<T> result = new CompletableFuture<>();
        synchronized (this) {
            if (mClosed) {
                result.completeExceptionally(new IOException("terminal closed"));
                return result;
            }
            mPending.add(result);
        }
        result.whenComplete((value, failure) -> {
            synchronized (this) {
                mPending.remove(result);
            }
        });
        try {
            mExecutor.execute(() -> {
                if (result.isDone()) {
                    return;
                }
                try {
                    result.complete(operation.call());
                } catch (Exception failure) {
                    if (failure instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    result.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    @Override
    public void close() {
        final List<CompletableFuture<?>> pending;
        synchronized (this) {
            mClosed = true;
            pending = new ArrayList<>(mPending);
            mPending.clear();
        }
        // Complete outside the monitor: callbacks may call back into the session.
        for (final CompletableFuture<?> request : pending) {
            request.completeExceptionally(new IOException("terminal closed"));
        }
    }
}

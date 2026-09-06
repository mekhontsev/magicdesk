package io.github.mekhontsev.magicdesk;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;

/** Owns content requests and their grants independently of late UI callbacks. */
final class ContentRequestScope implements AutoCloseable {
    interface Operation<T> {
        T run(BooleanSupplier cancelled) throws Exception;
    }

    static final class Completion<T> {
        final T value;
        final Throwable failure;

        private Completion(final T value, final Throwable failure) {
            this.value = value;
            this.failure = failure;
        }
    }

    private final Executor mExecutor;
    private final Set<Request<?>> mRequests = new HashSet<>();
    private volatile boolean mClosed;

    ContentRequestScope(final Executor executor) {
        mExecutor = executor;
    }

    <T> CompletableFuture<Completion<T>> submit(
            final Operation<T> operation, final Runnable release) {
        final Request<T> request = new Request<>(operation, release);
        synchronized (this) {
            if (!mClosed) {
                mRequests.add(request);
            }
        }
        if (mClosed) {
            request.reject(new InterruptedIOException("content request owner closed"));
        } else {
            try {
                mExecutor.execute(request);
            } catch (RejectedExecutionException failure) {
                request.reject(failure);
            }
        }
        return request.result;
    }

    void deliver(final Executor executor, final Runnable callback) {
        if (!mClosed) {
            executor.execute(() -> {
                if (!mClosed) {
                    callback.run();
                }
            });
        }
    }

    @Override
    public void close() {
        final ArrayList<Request<?>> requests;
        synchronized (this) {
            mClosed = true;
            requests = new ArrayList<>(mRequests);
        }
        for (final Request<?> request : requests) {
            request.reject(new InterruptedIOException("content request owner closed"));
        }
    }

    private final class Request<T> implements Runnable {
        final Operation<T> operation;
        final Runnable release;
        final CompletableFuture<Completion<T>> result = new CompletableFuture<>();
        boolean claimed;

        Request(final Operation<T> operation, final Runnable release) {
            this.operation = operation;
            this.release = release;
        }

        private boolean claim() {
            synchronized (ContentRequestScope.this) {
                if (claimed) {
                    return false;
                }
                claimed = true;
                return true;
            }
        }

        @Override
        public void run() {
            if (!claim()) {
                return;
            }
            T value = null;
            Throwable failure = null;
            try {
                if (mClosed) {
                    throw new InterruptedIOException("content request owner closed");
                }
                value = operation.run(() -> mClosed);
            } catch (Throwable error) {
                failure = error;
            }
            finish(value, failure);
        }

        void reject(final Throwable failure) {
            if (claim()) {
                finish(null, failure);
            }
            // Running work keeps its grant until its own finally-equivalent
            // finish. Closing the scope signals cancellation without revoking
            // access underneath an in-flight provider read.
        }

        private void finish(final T value, Throwable failure) {
            try {
                if (release != null) {
                    release.run();
                }
            } catch (Throwable error) {
                if (failure == null) {
                    failure = error;
                } else if (failure != error) {
                    failure.addSuppressed(error);
                }
            } finally {
                synchronized (ContentRequestScope.this) {
                    mRequests.remove(this);
                }
            }
            // Releasing a grant can fail after a copy or launch has committed.
            // Publish both facts; exceptional futures would discard the value.
            result.complete(new Completion<>(value, failure));
        }
    }
}

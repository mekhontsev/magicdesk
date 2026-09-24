package io.github.mekhontsev.magicdesk.hosted;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/** Revocable frame receipt. Completion callbacks never run under the state lock. */
public final class FramePresentation {
    private volatile long generation;
    private CompletableFuture<Void> pending;

    public long begin(CompletableFuture<Void> completion) {
        final long next;
        final CompletableFuture<Void> previous;
        synchronized (this) {
            next = ++generation;
            previous = pending;
            pending = completion;
        }
        if (previous != null) previous.completeExceptionally(new CancellationException("Output presentation superseded"));
        return next;
    }

    public long generation() { return generation; }
    public boolean accepts(long value) { return value == generation; }

    public void submitted(long value) {
        CompletableFuture<Void> completion;
        synchronized (this) {
            if (!accepts(value)) return;
            completion = pending;
            pending = null;
        }
        if (completion != null) completion.complete(null);
    }

    public void fail(Throwable error) {
        fail(generation, error);
    }

    public void fail(long value, Throwable error) {
        CompletableFuture<Void> completion;
        synchronized (this) {
            if (!accepts(value)) return;
            completion = pending;
            pending = null;
        }
        if (completion != null) completion.completeExceptionally(error);
    }

    public void invalidate(String reason) {
        CompletableFuture<Void> completion;
        synchronized (this) {
            ++generation;
            completion = pending;
            pending = null;
        }
        if (completion != null) completion.completeExceptionally(new CancellationException(reason));
    }
}

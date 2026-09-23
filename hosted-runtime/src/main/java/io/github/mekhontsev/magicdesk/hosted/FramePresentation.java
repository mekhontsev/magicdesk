package io.github.mekhontsev.magicdesk.hosted;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/** Control-thread receipt; UI callbacks may check the current presentation generation. */
public final class FramePresentation {
    private volatile long generation;
    private CompletableFuture<Void> pending;

    public long begin(CompletableFuture<Void> completion) {
        long next = ++generation;
        var previous = pending;
        pending = completion;
        if (previous != null) previous.completeExceptionally(new CancellationException("Output presentation superseded"));
        return next;
    }

    public long generation() { return generation; }
    public boolean accepts(long value) { return value == generation; }

    public void submitted(long value) {
        if (!accepts(value) || pending == null) return;
        var completion = pending;
        pending = null;
        completion.complete(null);
    }

    public void fail(Throwable error) {
        var completion = pending;
        pending = null;
        if (completion != null) completion.completeExceptionally(error);
    }

    public void invalidate(String reason) {
        ++generation;
        fail(new CancellationException(reason));
    }
}

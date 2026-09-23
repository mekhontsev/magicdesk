package io.github.mekhontsev.magicdesk.wayland;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/** Control-thread receipt; UI callbacks may check the current presentation generation. */
final class FramePresentation {
    private volatile long generation;
    private CompletableFuture<Void> pending;

    long begin(CompletableFuture<Void> completion) {
        long next = ++generation;
        var previous = pending;
        pending = completion;
        if (previous != null) previous.completeExceptionally(new CancellationException("Wayland output presentation superseded"));
        return next;
    }

    long generation() { return generation; }
    boolean accepts(long value) { return value == generation; }

    void submitted(long value) {
        if (!accepts(value) || pending == null) return;
        var completion = pending;
        pending = null;
        completion.complete(null);
    }

    void fail(Throwable error) {
        var completion = pending;
        pending = null;
        if (completion != null) completion.completeExceptionally(error);
    }

    void invalidate(String reason) {
        ++generation;
        fail(new CancellationException(reason));
    }
}

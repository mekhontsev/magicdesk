package io.github.mekhontsev.magicdesk;

import java.util.List;
import java.util.function.LongConsumer;

/** A protocol close is a request, never a timed promise to kill the server. */
final class HostedSessionClose {
    private boolean requested;

    boolean requested() { return requested; }

    void request(List<Long> windows, LongConsumer closeWindow) {
        if (requested) return;
        requested = true;
        try {
            for (long window : windows) closeWindow.accept(window);
        } catch (RuntimeException error) {
            requested = false;
            throw error;
        }
    }

    boolean completed(boolean ready, boolean hasWindows) {
        return requested && ready && !hasWindows;
    }

    void cancel() { requested = false; }
}

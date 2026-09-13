package io.github.mekhontsev.magicdesk;

/** Fences queued input commands at their owner, including cancellation before main-thread execution. */
final class DisplayInputRequests {
    final class Request {
        final int displayId;
        final long version;
        private volatile boolean cancelled;

        private Request(int displayId, long version) {
            this.displayId = displayId;
            this.version = version;
        }

        void cancel() { cancelled = true; }
        boolean isCurrent() {
            synchronized (DisplayInputRequests.this) {
                return !cancelled && current == this;
            }
        }
    }

    private long version;
    private Request current;

    synchronized long version() { return version; }

    synchronized Request begin(int displayId, long expectedVersion) {
        if (expectedVersion >= 0 && expectedVersion != version) return null;
        current = new Request(displayId, ++version);
        return current;
    }

    synchronized Request beginRelease(int displayId) {
        // Parking an output must not supersede a user's queued selection of another display.
        if (current != null && !current.cancelled && current.displayId != displayId) return null;
        return begin(-1, -1);
    }

    synchronized void invalidate() { current = null; version++; }

    synchronized void release(int displayId) {
        if (current != null && current.displayId == displayId) invalidate();
    }
}

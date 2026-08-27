package io.github.mekhontsev.magicdesk;

/** Serializes state replacement while discarding operations superseded before execution. */
final class LatestOperationSerializer {
    interface Operation<E extends Exception> {
        void run() throws E;
    }

    static final class Ticket {
        private final long generation;

        private Ticket(final long generation) {
            this.generation = generation;
        }
    }

    private final Object mExecutionLock = new Object();
    private long mGeneration;

    synchronized Ticket supersede() {
        return new Ticket(++mGeneration);
    }

    synchronized void invalidate() {
        mGeneration++;
    }

    <E extends Exception> boolean executeIfCurrent(
            final Ticket ticket,
            final Operation<E> operation) throws E {
        if (ticket == null || operation == null) {
            throw new IllegalArgumentException("ticket and operation are required");
        }
        synchronized (mExecutionLock) {
            synchronized (this) {
                if (ticket.generation != mGeneration) {
                    return false;
                }
            }
            operation.run();
            return true;
        }
    }
}

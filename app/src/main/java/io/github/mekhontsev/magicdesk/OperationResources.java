package io.github.mekhontsev.magicdesk;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Reserves ownership before asynchronous startup; completion and cancellation may arrive first. */
final class OperationResources implements Closeable {
    private final List<Slot> slots = new ArrayList<>();
    private boolean closed;

    synchronized Slot reserve() {
        Slot slot = new Slot();
        slot.done = closed;
        if (!closed) slots.add(slot);
        return slot;
    }

    @Override public void close() {
        List<Slot> owned;
        synchronized (this) {
            closed = true;
            owned = new ArrayList<>(slots);
            slots.clear();
        }
        for (int i = owned.size() - 1; i >= 0; i--) owned.get(i).close();
    }

    final class Slot implements Closeable {
        private boolean done;
        private Closeable resource;

        void attach(Closeable value) {
            synchronized (OperationResources.this) {
                if (!done) { resource = value; return; }
            }
            release(value);
        }

        @Override public void close() {
            Closeable value;
            synchronized (OperationResources.this) {
                if (done) return;
                done = true;
                slots.remove(this);
                value = resource;
                resource = null;
            }
            release(value);
        }
    }

    private static void release(Closeable value) {
        if (value != null) try { value.close(); } catch (IOException ignored) { }
    }
}

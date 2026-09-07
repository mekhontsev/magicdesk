package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Owns the secondary display default, independently of individual task modes. */
final class DisplayWindowingSession {
    private static final int FREEFORM = 5;

    interface Api {
        int[] displayIds() throws IOException;
        DisplayWindowingSnapshot read(int displayId) throws IOException;
        void set(int displayId, String uniqueId, int mode) throws IOException;
    }

    interface Storage {
        Map<String, DisplayWindowingSnapshot> read() throws IOException;
        void write(Map<String, DisplayWindowingSnapshot> pending) throws IOException;
    }

    private final Api mApi;
    private final Storage mStorage;
    private DisplayWindowingSnapshot mActive;

    DisplayWindowingSession(final Api api, final Storage storage) {
        mApi = api;
        mStorage = storage;
    }

    synchronized void prepare(final int displayId) throws IOException {
        if (displayId <= 0 || mActive != null && mActive.displayId == displayId) {
            return;
        }
        if (mActive != null) {
            throw new IOException("another display windowing session is active");
        }
        recover();
        final DisplayWindowingSnapshot before = mApi.read(displayId);
        if (before == null || before.mode <= 0
                || before.uniqueId == null || before.uniqueId.isEmpty()) {
            throw new IOException("secondary display default mode is unavailable");
        }
        if (before.mode != FREEFORM) {
            final Map<String, DisplayWindowingSnapshot> pending = mStorage.read();
            pending.put(before.uniqueId, before);
            // Persist before the Binder write: a lost acknowledgement or process
            // death must not discard restoration of WindowManager's override.
            mStorage.write(pending);
        }
        mActive = before;
        if (before.mode != FREEFORM) {
            mApi.set(displayId, before.uniqueId, FREEFORM);
        }
    }

    synchronized void release(final int displayId) throws IOException {
        if (mActive == null || mActive.displayId != displayId) {
            return;
        }
        final DisplayWindowingSnapshot owned = mActive;
        mActive = null;
        final Map<String, DisplayWindowingSnapshot> pending = mStorage.read();
        final DisplayWindowingSnapshot previous = pending.get(owned.uniqueId);
        if (previous == null) {
            return;
        }
        final DisplayWindowingSnapshot current = mApi.read(displayId);
        if (current != null && owned.uniqueId.equals(current.uniqueId)) {
            restore(current, previous);
        } else if (!previous.virtual) {
            // Physical display overrides survive disconnect. Numeric ids do
            // not, so recovery matches the stable identity on reconnect.
            return;
        }
        pending.remove(owned.uniqueId);
        mStorage.write(pending);
    }

    synchronized void recover() throws IOException {
        final Map<String, DisplayWindowingSnapshot> pending = mStorage.read();
        if (pending.isEmpty()) {
            return;
        }
        final Set<String> connected = new HashSet<>();
        for (int displayId : mApi.displayIds()) {
            if (displayId <= 0) {
                continue;
            }
            final DisplayWindowingSnapshot current = mApi.read(displayId);
            if (current == null) {
                continue;
            }
            connected.add(current.uniqueId);
            final DisplayWindowingSnapshot previous = pending.get(current.uniqueId);
            if (previous != null && (mActive == null
                    || !mActive.uniqueId.equals(current.uniqueId))) {
                restore(current, previous);
                pending.remove(current.uniqueId);
                mStorage.write(pending);
            }
        }
        // Android discards a virtual display's override when that display dies.
        if (pending.values().removeIf(previous -> previous.virtual
                && !connected.contains(previous.uniqueId)
                && (mActive == null || !mActive.uniqueId.equals(previous.uniqueId)))) {
            mStorage.write(pending);
        }
    }

    private void restore(final DisplayWindowingSnapshot current,
            final DisplayWindowingSnapshot previous) throws IOException {
        if (current.mode <= 0) {
            throw new IOException("display default mode is unavailable during restore");
        }
        // Do not replace a different mode selected by another owner.
        if (current.mode == FREEFORM) {
            mApi.set(current.displayId, current.uniqueId, previous.mode);
        }
    }

    synchronized String diagnostics() throws IOException {
        return "policy=freeform, activeDisplay=" + (mActive == null ? -1 : mActive.displayId)
                + ", restoreEntries=" + mStorage.read().size()
                + ", restoration=previous-effective-mode";
    }
}

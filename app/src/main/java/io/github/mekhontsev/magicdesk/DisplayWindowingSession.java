package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Owns the secondary display default, independently of individual task modes. */
final class DisplayWindowingSession {
    record Override(DisplayWindowingSnapshot previous, int appliedMode) { }

    interface Api {
        int[] displayIds() throws IOException;
        DisplayWindowingSnapshot read(int displayId) throws IOException;
        void set(int displayId, String uniqueId, int mode) throws IOException;
    }

    interface Storage {
        Map<String, Override> read() throws IOException;
        void write(Map<String, Override> pending) throws IOException;
    }

    private final Api mApi;
    private final Storage mStorage;
    private final Map<Integer, DisplayWindowingSnapshot> mActive = new java.util.LinkedHashMap<>();

    DisplayWindowingSession(final Api api, final Storage storage) {
        mApi = api;
        mStorage = storage;
    }

    synchronized void prepare(final int displayId, final int mode) throws IOException {
        if (mode != 1 && mode != 5) throw new IllegalArgumentException("unsupported display default mode");
        if (displayId <= 0 || mActive.containsKey(displayId)) {
            return;
        }
        recover();
        final DisplayWindowingSnapshot before = mApi.read(displayId);
        if (before == null || before.mode <= 0
                || before.uniqueId == null || before.uniqueId.isEmpty()) {
            throw new IOException("secondary display default mode is unavailable");
        }
        final boolean changesDefaults = before.mode != mode;
        if (changesDefaults) {
            final Map<String, Override> pending = mStorage.read();
            pending.put(before.uniqueId, new Override(before, mode));
            // Persist before the Binder write: a lost acknowledgement or process
            // death must not discard restoration of WindowManager's override.
            mStorage.write(pending);
        }
        mActive.put(displayId, before);
        if (changesDefaults) {
            mApi.set(displayId, before.uniqueId, mode);
        }
    }

    synchronized void release(final int displayId) throws IOException {
        final DisplayWindowingSnapshot owned = mActive.remove(displayId);
        if (owned == null) {
            return;
        }
        final Map<String, Override> pending = mStorage.read();
        final Override previous = pending.get(owned.uniqueId);
        if (previous == null) {
            return;
        }
        final DisplayWindowingSnapshot current = mApi.read(displayId);
        if (current != null && owned.uniqueId.equals(current.uniqueId)) {
            restore(current, previous);
        } else if (!previous.previous().virtual) {
            // Physical display overrides survive disconnect. Numeric ids do
            // not, so recovery matches the stable identity on reconnect.
            return;
        }
        pending.remove(owned.uniqueId);
        mStorage.write(pending);
    }

    synchronized void recover() throws IOException {
        final Map<String, Override> pending = mStorage.read();
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
            final Override previous = pending.get(current.uniqueId);
            if (previous != null && !owns(current.uniqueId)) {
                restore(current, previous);
                pending.remove(current.uniqueId);
                mStorage.write(pending);
            }
        }
        // Android discards a virtual display's override when that display dies.
        if (pending.values().removeIf(entry -> entry.previous().virtual
                && !connected.contains(entry.previous().uniqueId)
                && !owns(entry.previous().uniqueId))) {
            mStorage.write(pending);
        }
    }

    private void restore(final DisplayWindowingSnapshot current,
            final Override previous) throws IOException {
        if (current.mode <= 0) {
            throw new IOException("display default mode is unavailable during restore");
        }
        // Do not replace a different mode selected by another owner.
        final int mode = current.mode == previous.appliedMode() ? previous.previous().mode : current.mode;
        if (current.mode != mode) {
            mApi.set(current.displayId, current.uniqueId, mode);
        }
    }

    private boolean owns(final String uniqueId) {
        return mActive.values().stream().anyMatch(value -> value.uniqueId.equals(uniqueId));
    }

    synchronized String diagnostics() throws IOException {
        return "policy=system-desktop-aware, activeDisplays=" + mActive.keySet()
                + ", restoreEntries=" + mStorage.read().size()
                + ", restoration=previous-effective-mode";
    }
}

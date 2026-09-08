package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Reversible ownership of Android port associations, never ownership of key streams. */
final class InputRoutingLease {
    interface Api {
        FrameworkInputRoutingSnapshot snapshot() throws IOException;
        void setUniqueId(String port, String uniqueId) throws IOException;
        void setDisplayPort(String port, Integer displayPort) throws IOException;
    }

    interface Storage {
        Map<String, Entry> read() throws IOException;
        void write(Map<String, Entry> entries) throws IOException;
    }

    static final class Entry {
        final String target;
        final String previousUniqueId;
        final Integer previousDisplayPort;

        Entry(final String target, final String previousUniqueId,
                final Integer previousDisplayPort) {
            this.target = target;
            this.previousUniqueId = previousUniqueId;
            this.previousDisplayPort = previousDisplayPort;
        }
    }

    private final Api mApi;
    private final Storage mStorage;
    private final Map<String, Entry> mOwned = new LinkedHashMap<>();

    InputRoutingLease(final Api api, final Storage storage) {
        mApi = api;
        mStorage = storage;
    }

    void recover() throws IOException {
        if (!mOwned.isEmpty()) {
            throw new IllegalStateException("cannot recover an active routing lease");
        }
        mOwned.putAll(mStorage.read());
        release();
    }

    void reconcile(final String target, final Set<String> ports) throws IOException {
        if (target == null || target.isEmpty()) {
            throw new IllegalArgumentException("missing routing display identity");
        }
        final FrameworkInputRoutingSnapshot before = mApi.snapshot();
        for (final String port : new ArrayList<>(mOwned.keySet())) {
            if (!ports.contains(port)) {
                restore(port, before);
            }
        }
        for (final String port : ports) {
            final Entry owned = mOwned.get(port);
            if (owned != null) {
                if (!owned.target.equals(target)) {
                    throw new IOException("input lease cannot change displays");
                }
                final String uniqueId = before.uniqueIds.get(port);
                final Integer displayPort = before.runtimePorts.get(port);
                if ((!Objects.equals(uniqueId, target)
                        && !Objects.equals(uniqueId, owned.previousUniqueId))
                        || (displayPort != null
                        && !Objects.equals(displayPort, owned.previousDisplayPort))) {
                    throw new IOException("input route changed by another owner: " + port);
                }
                // Retry an incomplete acquisition without replacing its original journal.
                if (!Objects.equals(uniqueId, target)) {
                    mApi.setUniqueId(port, target);
                }
                if (displayPort != null) {
                    mApi.setDisplayPort(port, null);
                }
                continue;
            }
            // A firmware-static port cannot be cleared by the runtime API.
            // Never pretend its device now follows our unique-id association.
            if (before.staticPorts.containsKey(port)) {
                throw new IOException("input port has a firmware-static display route: " + port);
            }
            final Entry entry = new Entry(target, before.uniqueIds.get(port),
                    before.runtimePorts.get(port));
            mOwned.put(port, entry);
            try {
                // Journal before either Binder call, including uncertain acknowledgement.
                mStorage.write(mOwned);
            } catch (IOException error) {
                mOwned.remove(port);
                throw error;
            }
            if (!Objects.equals(entry.previousUniqueId, target)) {
                mApi.setUniqueId(port, target);
            }
            if (entry.previousDisplayPort != null) {
                mApi.setDisplayPort(port, null);
            }
        }
    }

    void release() throws IOException {
        if (mOwned.isEmpty()) {
            return;
        }
        final FrameworkInputRoutingSnapshot current = mApi.snapshot();
        IOException failure = null;
        for (final String port : new ArrayList<>(mOwned.keySet())) {
            try {
                restore(port, current);
            } catch (IOException error) {
                if (failure == null) {
                    failure = error;
                } else {
                    failure.addSuppressed(error);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    Set<String> ports() {
        return Set.copyOf(mOwned.keySet());
    }

    private void restore(final String port, final FrameworkInputRoutingSnapshot current)
            throws IOException {
        final Entry entry = mOwned.get(port);
        // Each map has independent ownership. Preserve a route changed by another
        // owner, and recover even when only the first acquisition write completed.
        if (entry.previousDisplayPort != null && !current.runtimePorts.containsKey(port)) {
            mApi.setDisplayPort(port, entry.previousDisplayPort);
        }
        if (!Objects.equals(entry.previousUniqueId, entry.target)
                && Objects.equals(current.uniqueIds.get(port), entry.target)) {
            mApi.setUniqueId(port, entry.previousUniqueId);
        }
        mOwned.remove(port);
        try {
            mStorage.write(mOwned);
        } catch (IOException error) {
            mOwned.put(port, entry);
            throw error;
        }
    }
}

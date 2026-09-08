package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

/** Shell-owned direct routes for physical input and the phone's virtual mouse. */
public final class DesktopInputRoutingSession implements AutoCloseable {
    static final String VIRTUAL_MOUSE_LOCATION = "magicdesk-mouse";
    private final FrameworkInputRoutingApi mApi;
    private final InputRoutingLease mLease;
    private final DesktopShortcutFilterLease mShortcuts;
    private final int mDisplayId;
    private final String mDisplayUniqueId;
    private boolean mClosed;

    private DesktopInputRoutingSession(final int displayId) throws Exception {
        mApi = FrameworkRuntime.current().inputRouting();
        mDisplayId = displayId;
        mDisplayUniqueId = mApi.displayUniqueId(displayId);
        mLease = new InputRoutingLease(mApi, new DesktopInputRoutingOwnership());
        mShortcuts = new DesktopShortcutFilterLease();
    }

    static DesktopInputRoutingSession open(final int displayId) throws Exception {
        final DesktopInputRoutingSession session = new DesktopInputRoutingSession(displayId);
        try {
            session.mLease.recover();
            session.refresh();
            session.mShortcuts.acquire();
            return session;
        } catch (Exception error) {
            try {
                session.close();
            } catch (IOException cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
    }

    int displayId() {
        return mDisplayId;
    }

    synchronized int associationCount() {
        return mLease.ports().size();
    }

    synchronized void refresh() throws IOException {
        if (mClosed) {
            return;
        }
        if (!mDisplayUniqueId.equals(mApi.displayUniqueId(mDisplayId))) {
            throw new IOException("input routing display identity changed");
        }
        final String dump;
        try {
            dump = FrameworkInputSnapshotSource.readLocal();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("input inventory interrupted", error);
        }
        final Set<String> ports = selectPorts(dump);
        if (mDisplayId == 0) ports.remove(VIRTUAL_MOUSE_LOCATION);
        mLease.reconcile(mDisplayUniqueId, ports);
    }

    static Set<String> selectPorts(final String inputDump) throws IOException {
        final Set<String> ports = new LinkedHashSet<>();
        // Preassociate the phone pointer before its device is registered.
        ports.add(VIRTUAL_MOUSE_LOCATION);
        for (final DesktopKeyboardDevice keyboard :
                DesktopInputDeviceDiscovery.findKeyboards(inputDump)) {
            ports.add(keyboard.location);
        }
        for (final DesktopMouseDevice mouse : DesktopInputDeviceDiscovery.findMice(inputDump)) {
            ports.add(mouse.location);
        }
        return ports;
    }

    static int cleanupStaleAssociations() throws Exception {
        Exception failure = null;
        try {
            new DesktopShortcutFilterLease().release();
        } catch (Exception error) {
            failure = error;
        }
        final InputRoutingLease.Storage storage = new DesktopInputRoutingOwnership();
        int count = 0;
        try {
            count = storage.read().size();
            if (count != 0) {
                new InputRoutingLease(FrameworkRuntime.current().inputRouting(), storage).recover();
            }
        } catch (Exception error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        if (failure != null) throw failure;
        return count;
    }

    @Override
    public synchronized void close() throws IOException {
        mClosed = true;
        // Release remains retryable when a Binder or journal write failed.
        IOException failure = null;
        try {
            mShortcuts.release();
        } catch (IOException error) {
            failure = error;
        }
        try {
            mLease.release();
        } catch (IOException error) {
            if (failure == null) failure = error;
            else failure.addSuppressed(error);
        }
        if (failure != null) throw failure;
    }
}

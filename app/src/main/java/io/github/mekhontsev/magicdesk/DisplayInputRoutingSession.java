package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

/** Shell-owned direct routes for physical input and the phone's virtual mouse. */
public final class DisplayInputRoutingSession implements AutoCloseable {
    static final String VIRTUAL_MOUSE_LOCATION = "magicdesk-mouse";
    private final FrameworkInputRoutingApi mApi;
    private final InputRoutingLease mLease;
    private final DesktopShortcutFilterLease mShortcuts;
    private final DisplayImePolicyController mImePolicy;
    private final int mDisplayId;
    private final String mDisplayUniqueId;
    private boolean mClosed;

    private DisplayInputRoutingSession(final int displayId, final boolean desktop) throws Exception {
        mApi = FrameworkRuntime.current().inputRouting();
        mDisplayId = displayId;
        mDisplayUniqueId = mApi.displayUniqueId(displayId);
        mLease = new InputRoutingLease(mApi, new DesktopInputRoutingOwnership());
        mShortcuts = desktop ? new DesktopShortcutFilterLease() : null;
        // One owner for both ordinary and Desktop input, including promotion
        // on the same display: no overlapping saved IME policies.
        mImePolicy = new DisplayImePolicyController();
    }

    static DisplayInputRoutingSession open(final int displayId, final boolean desktop) throws Exception {
        final DisplayInputRoutingSession session = new DisplayInputRoutingSession(displayId, desktop);
        try {
            session.mLease.recover();
            session.refresh();
            if (session.mShortcuts != null) { session.mShortcuts.acquire(); }
            session.mImePolicy.configure(displayId);
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
            if (mShortcuts != null) { mShortcuts.release(); }
        } catch (IOException error) {
            failure = error;
        }
        try {
            mImePolicy.close();
        } catch (RuntimeException error) {
            if (failure == null) { failure = new IOException("cannot restore display IME policy", error); }
            else { failure.addSuppressed(error); }
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

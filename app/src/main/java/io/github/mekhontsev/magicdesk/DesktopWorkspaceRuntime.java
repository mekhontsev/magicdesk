package io.github.mekhontsev.magicdesk;

import java.lang.ref.WeakReference;

/** One workspace residency, retained across host recreation but never across Close. */
final class DesktopWorkspaceRuntime {
    final int displayId;
    private volatile DesktopWorkspaceSnapshot mSnapshot;
    private WeakReference<DesktopShellActivity> mHost = new WeakReference<>(null);
    private volatile boolean mClosed;

    DesktopWorkspaceRuntime(final DesktopDisplayTarget target) {
        displayId = target.workspaceDisplayId;
        mSnapshot = DesktopWorkspaceSnapshot.empty().withTarget(target);
    }

    DesktopWorkspaceSnapshot snapshot() {
        return mSnapshot;
    }

    void update(final DesktopWorkspaceSnapshot snapshot) {
        if (mClosed || (snapshot.target != null && !snapshot.target.ownsWorkspace(displayId))
                || (snapshot.hasHost() && snapshot.hostDisplayId != displayId)) {
            throw new IllegalStateException("workspace residency cannot change in place");
        }
        mSnapshot = snapshot;
    }

    DesktopShellActivity host() {
        return mClosed ? null : mHost.get();
    }

    void attachHost(final DesktopShellActivity activity) {
        if (mClosed || activity.getCurrentDisplayId() != displayId
                || activity.getTaskId() != mSnapshot.hostTaskId) {
            throw new IllegalStateException("UI host does not own this workspace");
        }
        mHost = new WeakReference<>(activity);
    }

    void detachHost() {
        mHost.clear();
    }

    boolean isClosed() {
        return mClosed;
    }

    void close() {
        mClosed = true;
        detachHost();
    }
}

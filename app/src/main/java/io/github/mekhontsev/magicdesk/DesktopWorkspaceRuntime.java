package io.github.mekhontsev.magicdesk;

import java.lang.ref.WeakReference;

/** One workspace residency, retained across host recreation but never across Close. */
final class DesktopWorkspaceRuntime {
    final String id = java.util.UUID.randomUUID().toString();
    final int displayId;
    final DesktopSessionPolicy policy;
    private volatile DesktopWorkspaceSnapshot mSnapshot;
    private WeakReference<DesktopShellActivity> mHost = new WeakReference<>(null);
    private volatile boolean mClosed;

    DesktopWorkspaceRuntime(final DesktopDisplayTarget target) {
        this(target, DesktopSessionPolicy.USER);
    }

    DesktopWorkspaceRuntime(final DesktopDisplayTarget target, final DesktopSessionPolicy policy) {
        displayId = target.workspaceDisplayId;
        this.policy = java.util.Objects.requireNonNull(policy);
        mSnapshot = DesktopWorkspaceSnapshot.admitted(id, target);
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

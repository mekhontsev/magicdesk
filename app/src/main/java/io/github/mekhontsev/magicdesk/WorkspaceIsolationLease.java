package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.view.Display;

/** Scopes a self-test desktop session away from the saved user workspace. */
final class WorkspaceIsolationLease implements AutoCloseable {
    private boolean mClosed;

    private WorkspaceIsolationLease() {
    }

    static WorkspaceIsolationLease open() {
        return new WorkspaceIsolationLease();
    }

    void adoptPreparedSession(final int displayId) {
        requireOpen();
        final DesktopDisplayTarget target =
                DesktopRuntimeBridge.getDesktopTarget(displayId);
        if (target == null || displayId < Display.DEFAULT_DISPLAY) {
            throw new IllegalStateException(
                    "prepared desktop target is unavailable for display="
                            + displayId);
        }
        DesktopRuntimeBridge.noteDesktopTarget(
                target, DesktopSessionPolicy.ISOLATED_SELF_TEST);
        AppWindowStateStore.beginSession(
                DesktopSessionPolicy.ISOLATED_SELF_TEST, false);
    }

    void showReady(
            final Activity source,
            final DesktopDisplayTarget target) {
        requireOpen();
        DesktopDisplayDrivers.forTarget(target).showReady(
                source, target, DesktopSessionPolicy.ISOLATED_SELF_TEST);
    }

    @Override
    public void close() {
        mClosed = true;
    }

    private void requireOpen() {
        if (mClosed) {
            throw new IllegalStateException(
                    "workspace isolation lease is closed");
        }
    }
}

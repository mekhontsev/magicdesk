package io.github.mekhontsev.magicdesk;

import android.view.Display;

/** Emits display-scoped changes for focused system-owned transient windows. */
final class ShellSystemDialogTracker {
    interface Listener {
        void onVisibilityChanged(int displayId, boolean visible);
    }

    private final Listener mListener;
    private final ShellSystemDialogPolicy mPolicy;

    private int mDisplayId = Display.INVALID_DISPLAY;
    private boolean mVisible;
    private boolean mReported;

    ShellSystemDialogTracker(
            final ShellSystemDialogPolicy policy,
            final Listener listener) {
        if (policy == null) {
            throw new IllegalArgumentException("missing system-dialog policy");
        }
        mPolicy = policy;
        mListener = listener;
    }

    void configure(
            final int displayId,
            final FrameworkInputWindowState.Snapshot snapshot) {
        final int previousDisplayId;
        final boolean clearPrevious;
        synchronized (this) {
            previousDisplayId = mDisplayId;
            clearPrevious = mReported && mVisible
                    && previousDisplayId != displayId;
            mDisplayId = displayId >= 0
                    ? displayId : Display.INVALID_DISPLAY;
            mReported = false;
            mVisible = false;
        }
        if (clearPrevious) {
            report(previousDisplayId, false);
        }
        update(snapshot, true);
    }

    void onInputWindowsChanged(
            final FrameworkInputWindowState.Snapshot snapshot) {
        update(snapshot, false);
    }

    private void update(
            final FrameworkInputWindowState.Snapshot snapshot,
            final boolean force) {
        final int displayId;
        final boolean visible;
        synchronized (this) {
            displayId = mDisplayId;
            if (displayId == Display.INVALID_DISPLAY) {
                return;
            }
            visible = snapshot != null
                    && snapshot.available
                    && mPolicy.isSystemDialog(
                            snapshot.focusedWindow(displayId));
            if (!force && mReported && visible == mVisible) {
                return;
            }
            mVisible = visible;
            mReported = true;
        }
        report(displayId, visible);
    }

    private void report(final int displayId, final boolean visible) {
        if (mListener != null) {
            mListener.onVisibilityChanged(displayId, visible);
        }
    }
}

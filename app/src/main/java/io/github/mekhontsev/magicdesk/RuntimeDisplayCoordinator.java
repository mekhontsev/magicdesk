package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;

final class RuntimeDisplayCoordinator implements DisplayManager.DisplayListener {
    interface Listener {
        void onDisplayStateChanged(boolean displayRemoved);
    }

    private final DisplayManager mDisplayManager;
    private final Handler mHandler;
    private final Listener mListener;

    RuntimeDisplayCoordinator(
            final Context context,
            final Handler handler,
            final Listener listener) {
        mDisplayManager = context.getSystemService(DisplayManager.class);
        mHandler = handler;
        mListener = listener;
    }

    void start() {
        if (mDisplayManager != null) {
            mDisplayManager.registerDisplayListener(this, mHandler);
        }
    }

    void stop() {
        if (mDisplayManager != null) {
            mDisplayManager.unregisterDisplayListener(this);
        }
    }

    boolean hasDisplay(final int displayId) {
        return displayId > 0
                && mDisplayManager != null
                && mDisplayManager.getDisplay(displayId) != null;
    }

    @Override
    public void onDisplayAdded(final int displayId) {
        mListener.onDisplayStateChanged(false);
    }

    @Override
    public void onDisplayRemoved(final int displayId) {
        mListener.onDisplayStateChanged(true);
    }

    @Override
    public void onDisplayChanged(final int displayId) {
        mListener.onDisplayStateChanged(false);
    }
}

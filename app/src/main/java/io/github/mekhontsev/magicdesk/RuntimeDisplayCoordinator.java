package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;

final class RuntimeDisplayCoordinator implements DisplayManager.DisplayListener {
    interface Listener {
        void onDisplayStateChanged(int displayId, boolean displayRemoved);
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
        record("added", displayId);
        mListener.onDisplayStateChanged(displayId, false);
    }

    @Override
    public void onDisplayRemoved(final int displayId) {
        record("removed", displayId);
        mListener.onDisplayStateChanged(displayId, true);
    }

    @Override
    public void onDisplayChanged(final int displayId) {
        record("changed", displayId);
        mListener.onDisplayStateChanged(displayId, false);
    }

    private static void record(final String operation, final int displayId) {
        try {
            DesktopAutomationEventJournal.record(
                    "display",
                    operation,
                    true,
                    "display=" + displayId,
                    new org.json.JSONObject().put("displayId", displayId));
        } catch (org.json.JSONException ignored) {
            DesktopAutomationEventJournal.record(
                    "display", operation, true, "display=" + displayId);
        }
    }
}

package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.PowerManager;
import android.util.Log;
import android.view.Display;

final class DesktopSessionWakeLock {
    private static final String TAG = "MagicDeskWakeLock";
    private static final String LOCK_TAG = "MagicDesk:DesktopSession";

    private final PowerManager.WakeLock mWakeLock;

    DesktopSessionWakeLock(final Context context) {
        final PowerManager powerManager = context == null
                ? null : context.getSystemService(PowerManager.class);
        mWakeLock = powerManager == null ? null : powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG);
        if (mWakeLock != null) {
            mWakeLock.setReferenceCounted(false);
        }
    }

    @SuppressLint("WakelockTimeout")
    void reconcile(final boolean enabled, final int desktopDisplayId) {
        final boolean shouldHold = shouldHold(enabled, desktopDisplayId);
        if (mWakeLock == null || shouldHold == mWakeLock.isHeld()) {
            return;
        }
        try {
            if (shouldHold) {
                // The foreground desktop service owns this lock and releases
                // it when the session or the preference ends.
                mWakeLock.acquire();
            } else {
                mWakeLock.release();
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "could not update desktop session wake lock", error);
            CompatibilityDiagnostics.record(
                    "POWER-AWAKE-001",
                    "Could not update the desktop session wake lock",
                    "enabled=" + enabled
                            + " display=" + desktopDisplayId,
                    error);
        }
    }

    boolean isHeld() {
        return mWakeLock != null && mWakeLock.isHeld();
    }

    void release() {
        if (mWakeLock == null || !mWakeLock.isHeld()) {
            return;
        }
        try {
            mWakeLock.release();
        } catch (RuntimeException error) {
            Log.w(TAG, "could not release desktop session wake lock", error);
        }
    }

    static boolean shouldHold(
            final boolean enabled,
            final int desktopDisplayId) {
        return enabled && desktopDisplayId >= Display.DEFAULT_DISPLAY;
    }
}

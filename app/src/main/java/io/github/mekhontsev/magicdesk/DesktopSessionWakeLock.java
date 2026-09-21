package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.PowerManager;
import android.util.Log;

final class DesktopSessionWakeLock {
    private static final String TAG = "MagicDeskWakeLock";
    private final PowerManager.WakeLock mWakeLock;

    DesktopSessionWakeLock(final Context context) {
        this(context, PowerManager.PARTIAL_WAKE_LOCK, "MagicDesk:DesktopSession");
    }

    @SuppressWarnings("deprecation")
    static DesktopSessionWakeLock screen(final Context context) {
        // HOME can be covered, so a window flag cannot own this session policy.
        // No wake-up/on-release flags: explicit power-off and lock take priority.
        return new DesktopSessionWakeLock(context, PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                "MagicDesk:DesktopScreen");
    }

    private DesktopSessionWakeLock(final Context context, final int level, final String tag) {
        final PowerManager powerManager = context == null
                ? null : context.getSystemService(PowerManager.class);
        mWakeLock = powerManager == null ? null : powerManager.newWakeLock(
                level, tag);
        if (mWakeLock != null) {
            mWakeLock.setReferenceCounted(false);
        }
    }

    @SuppressLint("WakelockTimeout")
    void reconcile(final boolean enabled, final boolean hasWorkspaces) {
        final boolean shouldHold = shouldHold(enabled, hasWorkspaces);
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
                            + " workspaces=" + hasWorkspaces,
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
            final boolean hasWorkspaces) {
        return enabled && hasWorkspaces;
    }
}

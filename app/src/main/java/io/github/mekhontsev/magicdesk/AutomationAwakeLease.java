package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.UUID;

/** A bounded automation lease, not a screen-timeout setting and not a Desktop wake lock. */
final class AutomationAwakeLease implements AutoCloseable {
    private final Context mContext;
    private PowerManager.WakeLock mLock;
    private String mId;
    private long mExpiresAt;

    AutomationAwakeLease(final Context context) { mContext = context; }

    @SuppressWarnings("deprecation")
    synchronized JSONObject acquire(final JSONObject args) throws JSONException {
        final int duration = AndroidUiSelector.integer(args, "durationMillis", 300000, 1000, 1800000);
        final String reason = AutomationDeviceState.capture(mContext).phoneUiUnavailableReason();
        if (reason != null) throw new IllegalStateException(reason);
        if (mLock != null && mLock.isHeld()) {
            if (!mId.equals(args.optString("leaseId"))) {
                throw new IllegalArgumentException("an awake lease is already held; renew using its leaseId");
            }
        } else {
            if (args.has("leaseId")) throw new IllegalArgumentException("awake lease expired; acquire a new one");
            final PowerManager power = mContext.getSystemService(PowerManager.class);
            // Screen locks remain the public API for keeping another app's foreground UI awake.
            // No wake-up flag: acquiring this lease cannot unlock or light an unattended phone.
            mLock = power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "MagicDesk:Automation");
            mLock.setReferenceCounted(false);
            mId = UUID.randomUUID().toString();
        }
        mLock.acquire(duration);
        mExpiresAt = SystemClock.elapsedRealtime() + duration;
        return state();
    }

    synchronized JSONObject release(final String id) throws JSONException {
        if (mId == null || !mId.equals(id)) throw new IllegalArgumentException("unknown awake leaseId");
        close();
        return new JSONObject().put("released", true);
    }

    synchronized JSONObject state() throws JSONException {
        final boolean held = mLock != null && mLock.isHeld();
        return new JSONObject().put("held", held).put("leaseId", held ? mId : JSONObject.NULL)
                .put("remainingMillis", held ? Math.max(0, mExpiresAt - SystemClock.elapsedRealtime()) : 0);
    }

    @Override public synchronized void close() {
        if (mLock != null && mLock.isHeld()) mLock.release();
        mLock = null;
        mId = null;
        mExpiresAt = 0;
    }
}

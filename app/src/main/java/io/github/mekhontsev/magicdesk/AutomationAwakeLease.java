package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.UUID;

/** Bounded display work, not a screen-timeout setting or a Desktop wake lock. */
final class AutomationAwakeLease implements AutoCloseable {
    private final Context mContext;
    private PowerManager.WakeLock mLock;
    private IBackgroundWorkLease mWork;
    private final android.os.IBinder mOwner = new android.os.Binder();
    private int mDisplayId;
    private String mId;
    private long mExpiresAt;

    AutomationAwakeLease(final Context context) { mContext = context; }

    @SuppressWarnings("deprecation")
    synchronized JSONObject acquire(final JSONObject args) throws JSONException, java.io.IOException {
        final int duration = AndroidUiSelector.integer(args, "durationMillis", 300000, 1000, 1800000);
        final int display = AndroidUiSelector.integer(args, "displayId",
                args.has("leaseId") ? mDisplayId : 0, 0, Integer.MAX_VALUE);
        final boolean held = state().getBoolean("held");
        if (held) {
            if (!mId.equals(args.optString("leaseId")) || display != mDisplayId) {
                throw new IllegalArgumentException("an awake lease is already held; renew its leaseId and display");
            }
        } else {
            if (args.has("leaseId")) throw new IllegalArgumentException("awake lease expired; acquire a new one");
            close();
        }
        if (display != 0) {
            try {
                if (held) mWork.renew(duration);
                else mWork = ShellAccess.acquireBackgroundWork(display, duration, true, mOwner);
            } catch (android.os.RemoteException error) {
                throw new java.io.IOException("background work connection lost", error);
            }
        } else {
            final String reason = AutomationDeviceState.capture(mContext).phoneUiUnavailableReason();
            if (reason != null) throw new IllegalStateException(reason);
            if (!held) {
                final PowerManager power = mContext.getSystemService(PowerManager.class);
                // No wake-up flag: ordinary phone automation cannot light or unlock it.
                mLock = power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "MagicDesk:Automation");
                mLock.setReferenceCounted(false);
            }
            mLock.acquire(duration);
        }
        if (!held) mId = UUID.randomUUID().toString();
        mDisplayId = display;
        mExpiresAt = SystemClock.elapsedRealtime() + duration;
        return state();
    }

    synchronized JSONObject release(final String id) throws JSONException {
        if (mId == null || !mId.equals(id)) throw new IllegalArgumentException("unknown awake leaseId");
        close();
        return new JSONObject().put("released", true);
    }

    synchronized JSONObject state() throws JSONException {
        if (mWork != null) {
            try {
                final JSONObject state = new JSONObject(mWork.state());
                return state.put("leaseId", state.getBoolean("held") ? mId : JSONObject.NULL);
            } catch (android.os.RemoteException | RuntimeException error) {
                return new JSONObject().put("held", false).put("leaseId", JSONObject.NULL)
                        .put("displayId", mDisplayId).put("remainingMillis", 0)
                        .put("error", "background work service disconnected");
            }
        }
        final boolean held = mLock != null && mLock.isHeld();
        return new JSONObject().put("held", held).put("leaseId", held ? mId : JSONObject.NULL)
                .put("displayId", mDisplayId)
                .put("remainingMillis", held ? Math.max(0, mExpiresAt - SystemClock.elapsedRealtime()) : 0);
    }

    @Override public synchronized void close() {
        if (mWork != null) {
            try { mWork.close(); }
            catch (android.os.RemoteException | RuntimeException error) {
                android.util.Log.w("MagicDeskWork", "Work release failed; service deadline remains active", error);
            }
            mWork = null;
        }
        if (mLock != null && mLock.isHeld()) mLock.release();
        mLock = null;
        mId = null;
        mDisplayId = 0;
        mExpiresAt = 0;
    }
}

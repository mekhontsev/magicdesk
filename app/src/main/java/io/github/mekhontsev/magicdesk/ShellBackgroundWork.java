package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Scoped power and firmware work hints, independent of Desktop and HOME. */
final class ShellBackgroundWork implements AutoCloseable {
    private final Context mContext;
    private final ShellVirtualDisplays mDisplays;
    private final PlatformBackgroundWork mPlatform;
    private final BackgroundWorkProtection mProtection;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Set<Lease> mLeases = new LinkedHashSet<>();
    private DisplayManager mDisplayManager;
    private boolean mClosed;
    private final Runnable mRefresh = this::refresh;
    private final DisplayManager.DisplayListener mDisplayListener = new DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { }
        @Override public void onDisplayChanged(int id) { }
        @Override public void onDisplayRemoved(int id) {
            synchronized (ShellBackgroundWork.this) {
                for (Lease lease : new ArrayList<>(mLeases)) if (lease.displayId == id) lease.close();
            }
        }
    };

    ShellBackgroundWork(Context context, ShellVirtualDisplays displays, PlatformBackgroundWork platform) {
        mContext = context;
        mDisplays = displays;
        mPlatform = platform;
        mProtection = new BackgroundWorkProtection(platform);
    }

    // The lease owns expiry/renewal and Binder death; CPU power must last through resource cleanup.
    @android.annotation.SuppressLint("WakelockTimeout")
    synchronized IBackgroundWorkLease acquire(int displayId, String uniqueId, int appUid,
            long durationMillis, boolean keepDisplayAwake, IBinder displayOwner, IBinder owner) {
        if (mClosed) throw new IllegalStateException("background work service closed");
        if (displayId <= 0 || owner == null || appUid < 10000) throw new IllegalArgumentException("invalid work owner");
        validateDuration(durationMillis, !keepDisplayAwake);
        DesktopDisplayInfo target = null;
        for (DesktopDisplayInfo info : mDisplays.list()) {
            if (info.id == displayId && info.uniqueId.equals(uniqueId)) target = info;
        }
        if (target == null) throw new IllegalArgumentException("display identity changed");
        final Lease lease = new Lease(displayId, owner, durationMillis == 0);
        try {
            owner.linkToDeath(lease.death, 0);
            lease.linked = true;
            if (mLeases.isEmpty()) {
                mDisplayManager = ShellIdentityContext.create(mContext).getSystemService(DisplayManager.class);
                mDisplayManager.registerDisplayListener(mDisplayListener, mHandler);
            }
            mLeases.add(lease);
            final Context context = ShellIdentityContext.create(mContext);
            lease.cpu = context.getSystemService(PowerManager.class).newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "MagicDesk:BackgroundWork");
            lease.cpu.acquire();
            if (keepDisplayAwake) lease.displayPower = mDisplays.keepAwake(displayId, uniqueId, displayOwner);
            mProtection.retain(lease, Set.of(appUid));
            if (mPlatform != PlatformBackgroundWork.NONE) {
                lease.observation = FrameworkTaskObservationSource.observeApplicationUids(displayId, mHandler,
                        uids -> retain(lease, uids), error -> fail(lease, error));
            }
            lease.renew(durationMillis);
            if (mLeases.size() == 1 && mPlatform.refreshIntervalMillis() > 0) {
                scheduleRefresh();
            }
            return lease;
        } catch (Exception error) {
            lease.close();
            throw new IllegalStateException("cannot acquire background work", error);
        }
    }

    private synchronized void retain(Lease lease, Set<Integer> uids) {
        if (!mLeases.contains(lease)) return;
        try { mProtection.retain(lease, uids); }
        catch (Exception error) { throw new IllegalStateException("application protection failed", error); }
    }

    private synchronized void fail(Lease lease, Exception error) {
        Log.w("MagicDeskWork", "Background protection failed", error);
        lease.failure = ShellAccess.usefulMessage(error);
        lease.close();
    }

    private synchronized void refresh() {
        if (mLeases.isEmpty()) return;
        try { mProtection.refresh(); }
        catch (Exception error) {
            for (Lease lease : new ArrayList<>(mLeases)) fail(lease, error);
            return;
        }
        // Transient firmware protocol renewal, never a task/state polling loop.
        scheduleRefresh();
    }

    private void scheduleRefresh() {
        RuntimeDelays.schedule(mHandler, mRefresh, RuntimeDelays.Reason.WORKING_STATE_REFRESH,
                mPlatform.refreshIntervalMillis());
    }

    static void validateDuration(long duration, boolean allowUnbounded) {
        if (allowUnbounded && duration == 0) return;
        if (duration < 1000 || duration > 1800000) throw new IllegalArgumentException("invalid work duration");
    }

    @Override public synchronized void close() {
        mClosed = true;
        for (Lease lease : new ArrayList<>(mLeases)) lease.close();
        mProtection.close();
    }

    private final class Lease extends IBackgroundWorkLease.Stub {
        final int displayId;
        final IBinder owner;
        final boolean unbounded;
        final IBinder.DeathRecipient death = this::close;
        final Runnable expiry = this::expire;
        AutoCloseable observation;
        AutoCloseable displayPower;
        PowerManager.WakeLock cpu;
        long expiresAt;
        boolean closed;
        boolean linked;
        String failure = "";

        Lease(int displayId, IBinder owner, boolean unbounded) {
            this.displayId = displayId; this.owner = owner; this.unbounded = unbounded;
        }
        @Override public void renew(long durationMillis) {
            synchronized (ShellBackgroundWork.this) {
                if (closed || (expiresAt != 0 && SystemClock.elapsedRealtime() >= expiresAt)) {
                    close();
                    throw new IllegalStateException("background work lease expired");
                }
                validateDuration(durationMillis, unbounded);
                mHandler.removeCallbacks(expiry);
                expiresAt = durationMillis == 0 ? 0 : SystemClock.elapsedRealtime() + durationMillis;
                if (durationMillis > 0) mHandler.postDelayed(expiry, durationMillis);
            }
        }
        private void expire() {
            synchronized (ShellBackgroundWork.this) {
                if (!closed && expiresAt > 0) {
                    final long remaining = expiresAt - SystemClock.elapsedRealtime();
                    if (remaining > 0) mHandler.postDelayed(expiry, remaining);
                    else close();
                }
            }
        }
        @Override public String state() {
            synchronized (ShellBackgroundWork.this) {
                if (!closed && expiresAt > 0 && SystemClock.elapsedRealtime() >= expiresAt) close();
                try {
                    return new JSONObject().put("held", !closed).put("displayId", displayId)
                            .put("remainingMillis", closed || expiresAt == 0 ? 0
                                    : Math.max(0, expiresAt - SystemClock.elapsedRealtime()))
                            .put("protectedUids", new JSONArray(mProtection.uids(this)))
                            .put("error", failure).toString();
                } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
            }
        }
        @Override public void close() {
            final long identity = android.os.Binder.clearCallingIdentity();
            try {
                synchronized (ShellBackgroundWork.this) {
                    if (closed) return;
                    closed = true;
                    mHandler.removeCallbacks(expiry);
                    if (linked) { linked = false; owner.unlinkToDeath(death, 0); }
                    release(observation);
                    release(displayPower);
                    mProtection.release(this);
                    if (cpu != null && cpu.isHeld()) cpu.release();
                    mLeases.remove(this);
                    if (mLeases.isEmpty()) {
                        mHandler.removeCallbacks(mRefresh);
                        if (mDisplayManager != null) mDisplayManager.unregisterDisplayListener(mDisplayListener);
                        mDisplayManager = null;
                    }
                }
            } finally { android.os.Binder.restoreCallingIdentity(identity); }
        }
        private void release(AutoCloseable resource) {
            try { if (resource != null) resource.close(); }
            catch (Exception error) { Log.w("MagicDeskWork", "Work cleanup failed", error); }
        }
    }
}

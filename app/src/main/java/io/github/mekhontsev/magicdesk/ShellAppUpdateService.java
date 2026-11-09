package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.os.ParcelFileDescriptor;
import android.os.Binder;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

/** A one-operation privileged worker. It never owns desktop or the general command service. */
public final class ShellAppUpdateService extends IAppUpdateWorker.Stub {
    private static final String TAG = "MagicDeskUpdate";
    private static final long HANDOFF_TIMEOUT_MS = 30_000;
    private static final long INSTALL_TIMEOUT_MS = 180_000;
    private final Object mLock = new Object();
    private final Context mContext;
    private int mSessionId = -1;
    private int mUserId;
    private String mUpdateId;
    private ParcelFileDescriptor mReceipt;
    private Intent mResult;
    private boolean mClosed;
    private int mAppUid;
    private AppUpdatePowerState mPower;
    private PowerManager.WakeLock mPowerBridge;
    private long mResumeDeadline;
    private boolean mClaimed;
    private boolean mResumed;
    private String mResumeError;

    public ShellAppUpdateService(Context context) {
        mContext = context;
        new Thread(this::run, "MagicDeskAppUpdate").start();
    }

    @Override public int uid() { return android.os.Process.myUid(); }
    @Override public String sourceId() { return BuildConfig.SOURCE_ID; }

    @Override public void begin(int sessionId, int userId, String updateId,
            ParcelFileDescriptor receipt, Bundle powerState) {
        synchronized (mLock) {
            try {
                if (mClosed || mSessionId >= 0) throw new IllegalStateException("updater already used");
                AutomationFileTransfers.identifier(updateId);
                final PackageInstaller.SessionInfo info = ShellAppUpdate.installer(mContext, userId)
                        .getSessionInfo(sessionId);
                if (info == null || !BuildConfig.APPLICATION_ID.equals(info.getAppPackageName())) {
                    throw new IllegalArgumentException("MagicDesk install session required");
                }
                if (receipt == null) throw new IllegalArgumentException("receipt descriptor required");
                mPower = AppUpdatePowerState.fromBundle(powerState, SystemClock.elapsedRealtime());
                mAppUid = Binder.getCallingUid();
                mReceipt = receipt;
                mSessionId = sessionId;
                mUserId = userId;
                mUpdateId = updateId;
                mLock.notifyAll();
            } catch (Exception error) {
                if (receipt != null) {
                    try { receipt.close(); } catch (Exception ignored) { }
                }
                throw new IllegalStateException("cannot accept update", error);
            }
        }
    }

    @Override public Bundle claimPowerState(String updateId) {
        synchronized (mLock) {
            requireResume(updateId);
            if (mClaimed) throw new IllegalStateException("update power state already claimed");
            mClaimed = true;
            var state = mPower.toBundle();
            state.putLong("resumeDeadline", mResumeDeadline);
            return state;
        }
    }

    @Override public void completeResume(String updateId, String error) {
        synchronized (mLock) {
            requireResume(updateId);
            if (!mClaimed) throw new IllegalStateException("update power state not claimed");
            mResumeError = error == null ? "" : error.substring(0, Math.min(1024, error.length()));
            mResumed = true;
            mLock.notifyAll();
        }
    }

    private void requireResume(String updateId) {
        if (Binder.getCallingUid() != mAppUid) throw new SecurityException("update application required");
        if (mClosed || mResumed || !java.util.Objects.equals(mUpdateId, updateId)
                || mResumeDeadline == 0 || SystemClock.uptimeMillis() >= mResumeDeadline) {
            throw new IllegalStateException("update resume is unavailable");
        }
    }

    @SuppressWarnings("deprecation")
    private void acquirePowerBridge() throws android.content.pm.PackageManager.NameNotFoundException {
        long duration = mPower.bridgeDuration(SystemClock.elapsedRealtime(),
                INSTALL_TIMEOUT_MS + AppUpdatePowerState.RESUME_TIMEOUT_MS);
        if (duration == 0) return;
        var power = ShellIdentityContext.create(mContext).getSystemService(PowerManager.class);
        // No wake-up flags or timeout-setting changes. Power-button sleep remains authoritative.
        mPowerBridge = power.newWakeLock(mPower.leaseId().isEmpty()
                ? PowerManager.SCREEN_DIM_WAKE_LOCK : PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                "MagicDesk:Update");
        mPowerBridge.setReferenceCounted(false);
        mPowerBridge.acquire(duration);
        Log.i(TAG, "power bridge acquired update=" + mUpdateId);
    }

    private void onResult(Intent intent) {
        synchronized (mLock) {
            if (!mClosed && intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) == mSessionId) {
                mResult = new Intent(intent);
                mLock.notifyAll();
            }
        }
    }

    private void run() {
        try {
            synchronized (mLock) {
                final long deadline = SystemClock.uptimeMillis() + HANDOFF_TIMEOUT_MS;
                while (mReceipt == null) {
                    if (!await(deadline, EventDrivenWaits.Reason.APP_UPDATE_HANDOFF)) return;
                }
            }
            final JSONObject result = new JSONObject().put("updateId", mUpdateId)
                    .put("sessionId", mSessionId);
            boolean installed = false;
            boolean commitAttempted = false;
            try {
                acquirePowerBridge();
                final IntentSender callback = FrameworkPackageInstallerApi.resultCallback(this::onResult);
                commitAttempted = true;
                ShellAppUpdate.commit(mContext, mSessionId, mUserId, callback);
                synchronized (mLock) {
                    final long deadline = SystemClock.uptimeMillis() + INSTALL_TIMEOUT_MS;
                    while (mResult == null) {
                        if (!await(deadline, EventDrivenWaits.Reason.APP_UPDATE_RESULT)) break;
                    }
                    if (mResult == null) {
                        result.put("state", "completion_unknown")
                                .put("detail", "installer callback deadline expired");
                    } else {
                        final int status = mResult.getIntExtra(PackageInstaller.EXTRA_STATUS,
                                PackageInstaller.STATUS_FAILURE);
                        installed = status == PackageInstaller.STATUS_SUCCESS;
                        final String detail = mResult.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                        result.put("installerStatus", status).put("state", installed ? "installed"
                                : status == PackageInstaller.STATUS_PENDING_USER_ACTION
                                ? "user_action_required" : "failed")
                                .put("detail", detail == null ? "" : detail.substring(0, Math.min(1024, detail.length())));
                    }
                }
            } catch (Exception error) {
                if (!commitAttempted) {
                    try { ShellAppUpdate.abandon(mContext, mSessionId, mUserId); }
                    catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
                }
                result.put("state", commitAttempted ? "completion_unknown" : "failed")
                        .put("detail", ShellAccess.usefulMessage(error));
            }
            result.put("powerRestore", installed && mPower.requested() ? "pending" : "not_requested");
            writeResult(result);
            if (installed) {
                // An explicit installer entry creates the new app process even when firmware
                // rejects background service autolaunch. The Activity has no displayed window.
                var resume = new Bundle();
                if (mPower.requested()) {
                    synchronized (mLock) {
                        mResumeDeadline = SystemClock.uptimeMillis() + AppUpdatePowerState.RESUME_TIMEOUT_MS;
                    }
                    resume.putBinder("worker", this);
                    resume.putString("updateId", mUpdateId);
                }
                try {
                    FrameworkUserApi.startShellActivity(new Intent().setClassName(BuildConfig.APPLICATION_ID,
                        AppUpdateResumeActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                            .putExtra("updateResume", resume), mUserId);
                    Log.i(TAG, "installed session=" + mSessionId + "; automation start requested");
                    if (mPower.requested()) {
                        synchronized (mLock) {
                            // EVENT_WAIT: new process owns the lease and/or drawn black window; expiry releases the bridge.
                            while (!mResumed && await(mResumeDeadline, EventDrivenWaits.Reason.APP_UPDATE_RESUME)) { }
                            result.put("powerRestore", mResumed && mResumeError.isEmpty() ? "restored" : "failed")
                                    .put("powerRestoreError", mResumed ? mResumeError : "resume deadline expired");
                        }
                    }
                } catch (Exception error) {
                    result.put("powerRestore", "failed").put("powerRestoreError", ShellAccess.usefulMessage(error));
                }
                writeResult(result);
            }
        } catch (Exception error) {
            Log.e(TAG, "update worker failed session=" + mSessionId, error);
        } finally {
            synchronized (mLock) { mClosed = true; }
            try {
                if (mPowerBridge != null && mPowerBridge.isHeld()) mPowerBridge.release();
            } catch (RuntimeException error) {
                Log.w(TAG, "Power bridge release failed; process exit releases its Binder token", error);
            }
            Log.i(TAG, "power bridge released update=" + mUpdateId);
            if (mReceipt != null) {
                try { mReceipt.close(); } catch (Exception ignored) { }
            }
            System.exit(0);
        }
    }

    private void writeResult(JSONObject result) throws Exception {
        // Only this descriptor is shared, never access to the application's private directory.
        try (var out = new ParcelFileDescriptor.AutoCloseOutputStream(ParcelFileDescriptor.dup(mReceipt.getFileDescriptor()))) {
            out.getChannel().position(0);
            AppUpdateReceipt.write(out, result);
            out.getChannel().truncate(out.getChannel().position());
            out.getFD().sync();
        }
    }

    private boolean await(long deadline, EventDrivenWaits.Reason reason) throws InterruptedException {
        final long remaining = deadline - SystemClock.uptimeMillis();
        if (remaining <= 0) return false;
        EventDrivenWaits.await(mLock, reason, remaining);
        return true;
    }

    @Override public void destroy() { System.exit(0); }
}

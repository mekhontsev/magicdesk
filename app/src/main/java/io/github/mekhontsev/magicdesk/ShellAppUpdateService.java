package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageInstaller;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import org.json.JSONObject;

/** A one-operation Shizuku daemon. It never owns desktop or the general command service. */
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

    public ShellAppUpdateService(Context context) {
        mContext = context;
        new Thread(this::run, "MagicDeskAppUpdate").start();
    }

    @Override public void begin(int sessionId, int userId, String updateId,
            ParcelFileDescriptor receipt) {
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
            try {
                final IntentSender callback = FrameworkPackageInstallerApi.resultCallback(this::onResult);
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
                result.put("state", "completion_unknown").put("detail", ShellAccess.usefulMessage(error));
            }
            // The worker gets only this descriptor, not access to the application's private directory.
            try (var out = new ParcelFileDescriptor.AutoCloseOutputStream(mReceipt)) {
                AppUpdateReceipt.write(out, result);
                out.getFD().sync();
            }
            if (installed) {
                // An explicit installer entry creates the new app process even when firmware
                // rejects background service autolaunch. The Activity has no displayed window.
                FrameworkUserApi.startShellActivity(new Intent().setClassName(BuildConfig.APPLICATION_ID,
                        AppUpdateResumeActivity.class.getName()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS), mUserId);
                Log.i(TAG, "installed session=" + mSessionId + "; automation start requested");
            }
        } catch (Exception error) {
            Log.e(TAG, "update worker failed session=" + mSessionId, error);
        } finally {
            synchronized (mLock) { mClosed = true; }
            if (mReceipt != null) {
                try { mReceipt.close(); } catch (Exception ignored) { }
            }
            System.exit(0);
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

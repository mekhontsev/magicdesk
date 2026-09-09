package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import java.io.IOException;

import rikka.shizuku.Shizuku;

/** The daemon owns accepted work; the app owns only its bounded binding attempt. */
final class AppUpdateWorkerConnection implements ServiceConnection {
    static final class HandoffException extends IOException {
        final boolean mayHaveStarted;

        HandoffException(String message, Throwable cause, boolean mayHaveStarted) {
            super(message, cause);
            this.mayHaveStarted = mayHaveStarted;
        }
    }
    private final Object mLock = new Object();
    private IAppUpdateWorker mWorker;
    private boolean mDisconnected;

    static void begin(Context context, int sessionId, int userId, String updateId,
            ParcelFileDescriptor receipt) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new HandoffException("update handoff must not block the UI thread", null, false);
        }
        final var connection = new AppUpdateWorkerConnection();
        final var args = new Shizuku.UserServiceArgs(new ComponentName(context, ShellAppUpdateService.class))
                .daemon(true).processNameSuffix("app_update").tag("app-update-" + updateId)
                .version(BuildConfig.SOURCE_ID.hashCode() & Integer.MAX_VALUE);
        boolean submitted = false;
        try {
            Shizuku.bindUserService(args, connection);
            synchronized (connection.mLock) {
                final long deadline = SystemClock.uptimeMillis() + 10_000;
                while (connection.mWorker == null) {
                    final long remaining = deadline - SystemClock.uptimeMillis();
                    if (remaining <= 0 || connection.mDisconnected) {
                        throw new IOException("update worker unavailable");
                    }
                    EventDrivenWaits.await(connection.mLock, EventDrivenWaits.Reason.APP_UPDATE_HANDOFF, remaining);
                }
                // A lost Binder reply may mean begin was accepted. Do not kill that worker or resubmit.
                submitted = true;
                connection.mWorker.begin(sessionId, userId, updateId, receipt);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new HandoffException("update handoff interrupted", error, false);
        } catch (android.os.RemoteException error) {
            throw new HandoffException("update handoff reply lost", error, submitted);
        } catch (IOException | RuntimeException error) {
            // A returned validation error is different from a lost Binder reply.
            submitted = false;
            throw new HandoffException("update handoff failed", error, false);
        } finally {
            try { Shizuku.unbindUserService(args, connection, !submitted); }
            catch (RuntimeException ignored) { }
        }
    }

    @Override public void onServiceConnected(ComponentName name, IBinder binder) {
        synchronized (mLock) {
            mWorker = IAppUpdateWorker.Stub.asInterface(binder);
            mLock.notifyAll();
        }
    }

    @Override public void onServiceDisconnected(ComponentName name) {
        synchronized (mLock) {
            mDisconnected = true;
            mLock.notifyAll();
        }
    }
}

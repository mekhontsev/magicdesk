package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import java.io.IOException;

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

    static void begin(int sessionId, int userId, String updateId,
            ParcelFileDescriptor receipt) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new HandoffException("update handoff must not block the UI thread", null, false);
        }
        final var connection = new AppUpdateWorkerConnection();
        ShellServiceLauncher.Binding binding = null;
        boolean submitted = false;
        try {
            binding = ShellServiceLauncher.current().bind(
                    ShellServiceLauncher.Service.UPDATE, "app-update-" + updateId, connection);
            synchronized (connection.mLock) {
                final long deadline = SystemClock.uptimeMillis() + ShellServiceLauncher.current().bindTimeoutMillis();
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
            try { if (binding != null) binding.close(!submitted); }
            catch (RuntimeException ignored) { }
        }
    }

    @Override public void onServiceConnected(ComponentName name, IBinder binder) {
        synchronized (mLock) {
            try {
                final IAppUpdateWorker worker = IAppUpdateWorker.Stub.asInterface(binder);
                if (!BuildConfig.SOURCE_ID.equals(worker.sourceId())) {
                    throw new SecurityException("update worker APK build does not match");
                }
                ShellPrivilegePolicy.verifyServiceUid(worker.uid());
                mWorker = worker;
            } catch (android.os.RemoteException | RuntimeException error) {
                mDisconnected = true;
                android.util.Log.w("MagicDeskUpdate", "Rejected update worker", error);
            }
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

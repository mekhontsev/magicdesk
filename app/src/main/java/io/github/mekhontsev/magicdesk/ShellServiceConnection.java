package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;

/** Owns one binding attempt; a cancelled or failed attempt cannot publish a service. */
final class ShellServiceConnection {
    private final Object mLock = new Object();
    private final Runnable mConnectedCallback;
    private IShellCommandService mService;
    private int mUid = -1;
    private Attempt mAttempt;

    ShellServiceConnection(Runnable connectedCallback) {
        mConnectedCallback = connectedCallback;
    }

    private static void initializeFramework(IShellCommandService service) throws RemoteException {
        if (!BuildConfig.SOURCE_ID.equals(service.sourceId())) {
            throw new SecurityException("command service APK build does not match");
        }
        ShellPrivilegePolicy.verifyServiceUid(service.uid());
        int desktopToggle = -1;
        String settingError = "";
        try {
            desktopToggle = FrameworkWindowingCompat.readDesktopToggle(MagicDeskApplication.applicationContext());
        } catch (RuntimeException error) {
            // Unknown Settings must not disable the rest of the privileged service.
            settingError = "desktop developer setting unavailable: " + ShellAccess.usefulMessage(error);
        }
        service.initializeFramework(desktopToggle, settingError);
    }

    ShellAccess.Snapshot snapshot(ShellAccess.Snapshot source) {
        synchronized (mLock) {
            final String error = mService != null ? "" : mAttempt != null
                    ? mAttempt.error : source.error.isEmpty() ? "Privileged service is not connected" : source.error;
            return new ShellAccess.Snapshot(source.backend, source.installed, source.running,
                    source.permissionGranted, mUid, source.version, error);
        }
    }

    IShellCommandService require(ShellAccess.Snapshot snapshot) throws IOException {
        connect();
        synchronized (mLock) {
            if (mService != null) return mService;
            final Attempt attempt = mAttempt;
            if (attempt == null) throw new IOException(snapshot.error.isEmpty()
                    ? "Privileged service is unavailable" : snapshot.error);
            if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException(attempt.error);
            while (mService == null && mAttempt == attempt && !attempt.finished) {
                final long remaining = attempt.deadline - SystemClock.uptimeMillis();
                if (remaining <= 0) throw new IOException("Timed out waiting for privileged service binding");
                try {
                    EventDrivenWaits.await(mLock, EventDrivenWaits.Reason.SERVICE_BINDING, remaining);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for privileged service binding", error);
                }
            }
            if (mService != null) return mService;
            throw new IOException(mAttempt == attempt ? attempt.error : "Privileged service binding was cancelled");
        }
    }

    void connect() {
        final ShellServiceLauncher launcher = ShellServiceLauncher.current();
        final Attempt attempt;
        synchronized (mLock) {
            if (mService != null || mAttempt != null || !launcher.canBind()) return;
            attempt = new Attempt(launcher.bindTimeoutMillis());
            mAttempt = attempt;
        }
        try {
            final ShellServiceLauncher.Binding owner = launcher.bind(ShellServiceLauncher.Service.COMMAND, "command", attempt);
            synchronized (mLock) {
                if (mAttempt == attempt && (!attempt.finished || mService != null)) {
                    attempt.owner = owner;
                    return;
                }
            }
            owner.close(true);
        } catch (RuntimeException error) {
            synchronized (mLock) {
                if (mAttempt != attempt) return;
                attempt.finished = true;
                attempt.error = "Could not bind privileged service: " + ShellAccess.usefulMessage(error);
                mLock.notifyAll();
            }
        }
    }

    IShellCommandService connectedService() {
        synchronized (mLock) { return mService; }
    }

    void disconnect() { clear(); }

    void clear() {
        final Attempt attempt;
        synchronized (mLock) {
            attempt = mAttempt;
            mAttempt = null;
            mService = null;
            mUid = -1;
            mLock.notifyAll();
        }
        if (attempt != null) attempt.close();
    }

    private final class Attempt implements ServiceConnection {
        final long deadline;
        ShellServiceLauncher.Binding owner;
        boolean finished;
        String error = "Privileged service is connecting";

        Attempt(long timeout) { deadline = SystemClock.uptimeMillis() + timeout; }

        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (mLock) { if (mAttempt != this || finished) return; }
            IShellCommandService service = null;
            int uid = -1;
            String failure = "Privileged service disconnected during binding";
            try {
                if (binder != null && binder.pingBinder()) {
                    service = IShellCommandService.Stub.asInterface(binder);
                    initializeFramework(service);
                    uid = service.uid();
                    failure = "";
                }
            } catch (RemoteException | RuntimeException exception) {
                service = null;
                failure = "Could not initialize privileged service: " + ShellAccess.usefulMessage(exception);
                Log.w("MagicDeskShell", failure, exception);
            }
            synchronized (mLock) {
                if (mAttempt != this || finished) return;
                mService = service;
                mUid = service == null ? -1 : uid;
                finished = true;
                error = failure;
                mLock.notifyAll();
            }
            if (service == null) close();
            mConnectedCallback.run();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                if (mAttempt != this || (finished && mService == null)) return;
                mService = null;
                mUid = -1;
                finished = true;
                final String launchError = ShellServiceLauncher.current().inspect().error;
                error = launchError.isEmpty() ? "Privileged service disconnected" : launchError;
                mLock.notifyAll();
            }
            close();
            mConnectedCallback.run();
        }

        void close() {
            final ShellServiceLauncher.Binding binding;
            synchronized (mLock) { binding = owner; owner = null; }
            try { if (binding != null) binding.close(true); }
            catch (RuntimeException ignored) { /* The launcher or service may already be gone. */ }
        }
    }
}

package io.github.mekhontsev.magicdesk;

import android.os.IBinder;
import android.os.RemoteException;

import java.io.Closeable;
import java.util.concurrent.atomic.AtomicBoolean;

final class ShellDirectoryObserverHandle implements Closeable {
    private final IShizukuCommandService mService;
    private final IBinder mServiceBinder;
    private final String mAbsolutePath;
    private final IShellDirectoryObserverCallback mCallback;
    private final Runnable mDisconnected;
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final IBinder.DeathRecipient mServiceDeath =
            this::serviceDisconnected;
    private boolean mRegistered;
    private boolean mServiceLinked;

    ShellDirectoryObserverHandle(
            final IShizukuCommandService service,
            final String absolutePath,
            final IShellDirectoryObserverCallback callback,
            final Runnable disconnected) {
        mService = service;
        mServiceBinder = service.asBinder();
        mAbsolutePath = absolutePath;
        mCallback = callback;
        mDisconnected = disconnected;
    }

    void start() throws RemoteException {
        mServiceBinder.linkToDeath(mServiceDeath, 0);
        synchronized (this) {
            mServiceLinked = true;
        }
        mService.startShellDirectoryObserver(mAbsolutePath, mCallback);
        synchronized (this) {
            if (!mClosed.get()) {
                mRegistered = true;
                return;
            }
        }
        stopRemote();
        throw new RemoteException(
                "directory observer disconnected during registration");
    }

    @Override
    public void close() {
        if (!mClosed.compareAndSet(false, true)) {
            return;
        }
        unlinkServiceDeath();
        final boolean registered;
        synchronized (this) {
            registered = mRegistered;
            mRegistered = false;
        }
        if (registered) {
            stopRemote();
        }
    }

    void closeAfterStartFailure() {
        stopRemote();
        mClosed.set(true);
        unlinkServiceDeath();
    }

    private void stopRemote() {
        try {
            mService.stopShellDirectoryObserver(mCallback);
        } catch (RemoteException | RuntimeException ignored) {
            // The service or observer may already have disconnected.
        }
    }

    private void serviceDisconnected() {
        if (!mClosed.compareAndSet(false, true)) {
            return;
        }
        synchronized (this) {
            mRegistered = false;
        }
        unlinkServiceDeath();
        if (mDisconnected != null) {
            mDisconnected.run();
        }
    }

    private synchronized void unlinkServiceDeath() {
        if (!mServiceLinked) {
            return;
        }
        mServiceBinder.unlinkToDeath(mServiceDeath, 0);
        mServiceLinked = false;
    }
}

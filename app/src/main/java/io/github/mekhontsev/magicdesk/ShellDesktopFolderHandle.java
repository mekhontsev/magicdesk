package io.github.mekhontsev.magicdesk;

import android.os.IBinder;
import android.os.RemoteException;

import java.io.Closeable;
import java.util.concurrent.atomic.AtomicBoolean;

final class ShellDesktopFolderHandle implements Closeable {
    private final IShizukuCommandService mService;
    private final IBinder mServiceBinder;
    private final IDesktopFolderObserverCallback mCallback;
    private final Runnable mDisconnected;
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final IBinder.DeathRecipient mServiceDeathRecipient =
            this::serviceDisconnected;
    private boolean mRegistered;
    private boolean mServiceLinked;

    ShellDesktopFolderHandle(
            final IShizukuCommandService service,
            final IDesktopFolderObserverCallback callback,
            final Runnable disconnected) {
        mService = service;
        mServiceBinder = service.asBinder();
        mCallback = callback;
        mDisconnected = disconnected;
    }

    void start() throws RemoteException {
        mServiceBinder.linkToDeath(mServiceDeathRecipient, 0);
        synchronized (this) {
            mServiceLinked = true;
        }
        mService.startDesktopFolderObserver(mCallback);
        if (mClosed.get()) {
            stopRemoteObserver();
            throw new RemoteException(
                    "desktop folder observer disconnected during registration");
        }
        mRegistered = true;
    }

    @Override
    public void close() {
        if (!mClosed.compareAndSet(false, true)) {
            return;
        }
        unlinkServiceDeath();
        if (mRegistered) {
            mRegistered = false;
            stopRemoteObserver();
        }
    }

    void closeAfterStartFailure() {
        stopRemoteObserver();
        if (mClosed.compareAndSet(false, true)) {
            unlinkServiceDeath();
        }
    }

    private void stopRemoteObserver() {
        try {
            mService.stopDesktopFolderObserver(mCallback);
        } catch (RemoteException | RuntimeException ignored) {
            // The observer may already have failed or disconnected.
        }
    }

    private void serviceDisconnected() {
        if (!mClosed.compareAndSet(false, true)) {
            return;
        }
        mRegistered = false;
        unlinkServiceDeath();
        if (mDisconnected != null) {
            mDisconnected.run();
        }
    }

    private synchronized void unlinkServiceDeath() {
        if (!mServiceLinked) {
            return;
        }
        mServiceBinder.unlinkToDeath(mServiceDeathRecipient, 0);
        mServiceLinked = false;
    }
}

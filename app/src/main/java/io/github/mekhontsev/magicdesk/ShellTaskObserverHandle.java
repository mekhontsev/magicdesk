package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.os.IBinder;
import android.os.RemoteException;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

final class ShellTaskObserverHandle implements Closeable {
    private final IShizukuCommandService mService;
    private final IBinder mServiceBinder;
    private final ITaskObserverCallback mCallback;
    private final Runnable mDisconnected;
    private final IBinder.DeathRecipient mServiceDeathRecipient;
    private final AtomicBoolean mClosed = new AtomicBoolean();

    private volatile boolean mRegistered;
    private boolean mServiceLinked;

    ShellTaskObserverHandle(
            final IShizukuCommandService service,
            final ITaskObserverCallback callback,
            final Runnable disconnected) {
        mService = service;
        mServiceBinder = service.asBinder();
        mCallback = callback;
        mDisconnected = disconnected;
        mServiceDeathRecipient = this::serviceDisconnected;
    }

    void start() throws RemoteException {
        mServiceBinder.linkToDeath(mServiceDeathRecipient, 0);
        synchronized (this) {
            mServiceLinked = true;
        }
        mService.startTaskObserver(mCallback);
        if (mClosed.get()) {
            stopRemoteObserver();
            throw new RemoteException(
                    "task observer disconnected during registration");
        }
        mRegistered = true;
    }

    void configure(
            final int displayId,
            final Rect displayBounds,
            final Rect workAreaBounds) throws IOException {
        if (displayBounds == null || workAreaBounds == null) {
            throw new IOException("missing task observer bounds");
        }
        callService(() -> mService.configureTaskObserver(
                mCallback,
                displayId,
                displayBounds.left,
                displayBounds.top,
                displayBounds.right,
                displayBounds.bottom,
                workAreaBounds.left,
                workAreaBounds.top,
                workAreaBounds.right,
                workAreaBounds.bottom));
    }

    void focusStack(
            final long sequence,
            final int displayId,
            final int[] taskIds) throws IOException {
        callService(() -> mService.focusTaskStack(
                mCallback, sequence, displayId, taskIds));
    }

    boolean releaseFullscreenTask(
            final int displayId,
            final int taskId) throws IOException {
        return callServiceForResult(() -> mService.releaseFullscreenTask(
                mCallback, displayId, taskId));
    }

    boolean closeFullscreenTask(
            final int displayId,
            final int taskId) throws IOException {
        return callServiceForResult(() -> mService.closeFullscreenTask(
                mCallback, displayId, taskId));
    }

    void startSelfTestTaskStackGuard(
            final int displayId,
            final int hostTaskId,
            final String stage) throws IOException {
        callService(() -> mService.startSelfTestTaskStackGuard(
                mCallback, displayId, hostTaskId, stage));
    }

    void setSelfTestTaskStackGuardStage(final String stage)
            throws IOException {
        callService(() -> mService.setSelfTestTaskStackGuardStage(
                mCallback, stage));
    }

    SelfTestTaskStackReport stopSelfTestTaskStackGuard()
            throws IOException {
        return callServiceForResult(() ->
                mService.stopSelfTestTaskStackGuard(mCallback));
    }

    void setPhoneTouchpadPreservation(final boolean enabled)
            throws IOException {
        callService(() -> mService.setPhoneTouchpadPreservation(
                mCallback, enabled));
    }

    void setExternalTaskMigrationProtection(final boolean enabled)
            throws IOException {
        callService(() -> mService.setExternalTaskMigrationProtection(
                mCallback, enabled));
    }

    void refreshTaskCaption(
            final int displayId,
            final int taskId,
            final int sourceId) throws IOException {
        callService(() -> mService.refreshTaskCaption(
                mCallback, displayId, taskId, sourceId));
    }

    boolean isClosed() {
        return mClosed.get();
    }

    @Override
    public void close() {
        if (!mClosed.compareAndSet(false, true)) {
            return;
        }
        unlinkServiceDeath();
        if (!mRegistered) {
            return;
        }
        mRegistered = false;
        stopRemoteObserver();
    }

    void closeAfterStartFailure() {
        stopRemoteObserver();
        if (mClosed.compareAndSet(false, true)) {
            unlinkServiceDeath();
        }
    }

    private void callService(final RemoteServiceCall call)
            throws IOException {
        if (mClosed.get()) {
            throw new IOException("task observer is closed");
        }
        try {
            call.run();
        } catch (RemoteException error) {
            serviceDisconnected();
            throw new IOException(
                    "task observer call failed: "
                            + ShellAccess.usefulMessage(error),
                    error);
        } catch (RuntimeException error) {
            stopRemoteObserver();
            serviceDisconnected();
            throw new IOException(
                    "task observer call failed: "
                            + ShellAccess.usefulMessage(error),
                    error);
        }
    }

    private <T> T callServiceForResult(
            final RemoteResultServiceCall<T> call) throws IOException {
        if (mClosed.get()) {
            throw new IOException("task observer is closed");
        }
        try {
            return call.run();
        } catch (RemoteException error) {
            serviceDisconnected();
            throw new IOException(
                    "task observer call failed: "
                            + ShellAccess.usefulMessage(error),
                    error);
        } catch (RuntimeException error) {
            stopRemoteObserver();
            serviceDisconnected();
            throw new IOException(
                    "task observer call failed: "
                            + ShellAccess.usefulMessage(error),
                    error);
        }
    }

    private void stopRemoteObserver() {
        try {
            mService.stopTaskObserver(mCallback);
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

    @FunctionalInterface
    private interface RemoteServiceCall {
        void run() throws RemoteException;
    }

    @FunctionalInterface
    private interface RemoteResultServiceCall<T> {
        T run() throws RemoteException;
    }
}

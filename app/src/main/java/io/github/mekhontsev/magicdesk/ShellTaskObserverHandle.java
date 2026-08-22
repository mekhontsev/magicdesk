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

    private boolean mRegistered;
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
        synchronized (this) {
            if (!mClosed.get()) {
                mRegistered = true;
                return;
            }
        }
        stopRemoteObserver();
        throw new RemoteException(
                "task observer disconnected during registration");
    }

    void configure(
            final int displayId,
            final Rect displayBounds,
            final Rect workAreaBounds,
            final boolean managedTaskArea,
            final int desktopHostTaskId) throws IOException {
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
                workAreaBounds.bottom,
                managedTaskArea,
                desktopHostTaskId));
    }

    void focusStack(
            final long sequence,
            final int displayId,
            final int[] taskIds) throws IOException {
        callService(() -> mService.focusTaskStack(
                mCallback, sequence, displayId, taskIds));
    }

    boolean restoreFullscreenTask(
            final int displayId,
            final int taskId,
            final Rect bounds) throws IOException {
        if (bounds == null || bounds.isEmpty()) {
            return false;
        }
        return callServiceForResult(() -> mService.restoreFullscreenTask(
                mCallback,
                displayId,
                taskId,
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom));
    }

    boolean beginAppFullscreenTask(
            final int displayId,
            final int taskId,
            final Rect restoreBounds) throws IOException {
        if (restoreBounds == null || restoreBounds.isEmpty()) {
            return false;
        }
        return callServiceForResult(() -> mService.beginAppFullscreenTask(
                mCallback,
                displayId,
                taskId,
                restoreBounds.left,
                restoreBounds.top,
                restoreBounds.right,
                restoreBounds.bottom));
    }

    boolean beginFullscreenTask(
            final int displayId,
            final int taskId) throws IOException {
        return callServiceForResult(() -> mService.beginFullscreenTask(
                mCallback, displayId, taskId));
    }

    boolean protectExplicitFullscreenTask(
            final int displayId,
            final int taskId) throws IOException {
        return callServiceForResult(() ->
                mService.protectExplicitFullscreenTask(
                        mCallback, displayId, taskId));
    }

    boolean closeFullscreenTask(
            final int displayId,
            final int taskId) throws IOException {
        return callServiceForResult(() -> mService.closeFullscreenTask(
                mCallback, displayId, taskId));
    }

    boolean closeDesktopTask(
            final int displayId,
            final int taskId,
            final int focusTaskId) throws IOException {
        return callServiceForResult(() -> mService.closeDesktopTask(
                mCallback, displayId, taskId, focusTaskId));
    }

    boolean removeDesktopPackageTasks(
            final int displayId,
            final String packageName,
            final int focusTaskId) throws IOException {
        return callServiceForResult(() ->
                mService.removeDesktopPackageTasks(
                        mCallback, displayId, packageName, focusTaskId));
    }

    int launchWindowedTask(
            final int displayId,
            final String intentUri,
            final Rect bounds) throws IOException {
        if (bounds == null || bounds.isEmpty()) {
            throw new IOException("invalid desktop task bounds");
        }
        return callServiceForResult(() -> mService.launchWindowedTask(
                mCallback,
                displayId,
                intentUri,
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom));
    }

    int launchFullscreenTaskInDesktopArea(
            final int displayId,
            final String intentUri) throws IOException {
        return callServiceForResult(() ->
                mService.launchFullscreenTaskInDesktopArea(
                mCallback, displayId, intentUri));
    }

    void launchTaskAction(
            final int displayId,
            final int taskId,
            final String intentUri) throws IOException {
        callService(() -> mService.launchTaskAction(
                mCallback, displayId, taskId, intentUri));
    }

    void placeTaskInDesktopArea(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final Rect bounds) throws IOException {
        if (bounds == null || bounds.isEmpty()) {
            throw new IOException("invalid desktop task bounds");
        }
        callService(() -> mService.placeTaskInDesktopArea(
                mCallback,
                taskId,
                sourceDisplayId,
                targetDisplayId,
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom));
    }

    void placeFullscreenTaskInDesktopArea(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId) throws IOException {
        callService(() -> mService.placeFullscreenTaskInDesktopArea(
                mCallback,
                taskId,
                sourceDisplayId,
                targetDisplayId));
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

    void setPhoneTouchpadRequested(final boolean requested)
            throws IOException {
        callService(() -> mService.setPhoneTouchpadRequested(
                mCallback, requested));
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
        final boolean registered;
        synchronized (this) {
            registered = mRegistered;
            mRegistered = false;
        }
        if (registered) {
            stopRemoteObserver();
        }
    }

    void closeAfterStartFailure() {
        stopRemoteObserver();
        mClosed.set(true);
        unlinkServiceDeath();
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
            // A remote method can report an operation-level failure as a
            // RuntimeException while its Binder and observer remain healthy.
            // Only a transport failure should tear down the observer session.
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
            // Keep the registered observer after a rejected task operation.
            // Runtime exceptions cross AIDL for ordinary service-side errors;
            // Binder death is reported separately as RemoteException.
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

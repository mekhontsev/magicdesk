package io.github.mekhontsev.magicdesk;

import android.content.Intent;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

final class ShellTaskObserverHandle implements Closeable {
    private final IShizukuCommandService mService;
    private final IBinder mServiceBinder;
    private final ITaskObserverCallback mCallback;
    private final IActivityLaunchCallback mActivityLauncher;
    private final Runnable mDisconnected;
    private final IBinder.DeathRecipient mServiceDeathRecipient;
    private final AtomicBoolean mClosed = new AtomicBoolean();

    private boolean mRegistered;
    private boolean mServiceLinked;

    ShellTaskObserverHandle(
            final IShizukuCommandService service,
            final ITaskObserverCallback callback,
            final IActivityLaunchCallback activityLauncher,
            final Runnable disconnected) {
        mService = service;
        mServiceBinder = service.asBinder();
        mCallback = callback;
        mActivityLauncher = activityLauncher;
        mDisconnected = disconnected;
        mServiceDeathRecipient = this::serviceDisconnected;
    }

    void start() throws RemoteException {
        mServiceBinder.linkToDeath(mServiceDeathRecipient, 0);
        synchronized (this) {
            mServiceLinked = true;
        }
        mService.startTaskObserver(mCallback, mActivityLauncher);
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
            final Rect taskbarBounds,
            final int taskAreaPolicy,
            final int desktopHostTaskId) throws IOException {
        if (displayBounds == null || workAreaBounds == null
                || taskbarBounds == null) {
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
                taskbarBounds.left,
                taskbarBounds.top,
                taskbarBounds.right,
                taskbarBounds.bottom,
                taskAreaPolicy,
                desktopHostTaskId));
    }

    void updateDesktopTaskbarBounds(
            final int displayId,
            final Rect bounds) throws IOException {
        if (bounds == null || bounds.isEmpty()) {
            throw new IOException("missing desktop taskbar bounds");
        }
        callService(() -> mService.updateDesktopTaskbarBounds(
                mCallback,
                displayId,
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom));
    }

    void configureDesktopTaskbarInput(
            final int displayId,
            final IBinder activityToken) throws IOException {
        if (activityToken == null) {
            throw new IOException("missing desktop taskbar activity token");
        }
        callService(() -> mService.configureDesktopTaskbarInput(
                mCallback, displayId, activityToken));
    }

    void raiseDesktopTaskbarPlane(final int displayId) throws IOException {
        callService(() -> mService.raiseDesktopTaskbarPlane(
                mCallback, displayId));
    }

    boolean clearConfiguration(final int expectedDisplayId)
            throws IOException {
        return callServiceForResult(() ->
                mService.clearTaskObserverConfiguration(
                        mCallback, expectedDisplayId));
    }

    void focusStack(
            final long sequence,
            final int displayId,
            final int[] taskIds) throws IOException {
        callService(() -> mService.focusTaskStack(
                mCallback, sequence, displayId, taskIds));
    }

    void executeWorkspaceCommand(
            final long sequence,
            final DesktopWorkspaceCommand command) throws IOException {
        callService(() -> mService.executeDesktopWorkspaceCommand(
                mCallback, sequence, command));
    }

    void notifyInputFocusRefreshComplete(final int taskId)
            throws IOException {
        callService(() -> mService.notifyDesktopInputFocusRefreshComplete(
                mCallback, taskId));
    }

    boolean concealFullscreenTaskPlanes(final int displayId)
            throws IOException {
        return callServiceForResult(() ->
                mService.concealFullscreenTaskPlanes(
                        mCallback, displayId));
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
            final Intent intent,
            final Rect bounds) throws IOException {
        if (bounds == null || bounds.isEmpty()) {
            throw new IOException("invalid desktop task bounds");
        }
        return callServiceForResult(() -> mService.launchWindowedTask(
                mCallback,
                displayId,
                intent,
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom));
    }

    int launchFullscreenTaskInManagedSession(
            final int displayId,
            final Intent intent) throws IOException {
        return callServiceForResult(() ->
                mService.launchFullscreenTaskInManagedSession(
                mCallback, displayId, intent));
    }

    int launchFullscreenTask(
            final int displayId,
            final Intent intent) throws IOException {
        return callServiceForResult(() -> mService.launchFullscreenTask(
                mCallback, displayId, intent));
    }

    int launchAppShortcut(
            final int displayId,
            final String packageName,
            final String shortcutId,
            final UserHandle user,
            final int windowingMode,
            final Rect bounds,
            final int existingTaskId) throws IOException {
        if (bounds == null) {
            throw new IllegalArgumentException("shortcut bounds are required");
        }
        return callServiceForResult(() -> mService.launchAppShortcut(
                mCallback,
                displayId,
                packageName,
                shortcutId,
                user,
                windowingMode,
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom,
                existingTaskId));
    }

    void launchTaskAction(
            final int displayId,
            final int taskId,
            final Intent intent) throws IOException {
        callService(() -> mService.launchTaskAction(
                mCallback, displayId, taskId, intent));
    }

    void placeWindowedTaskInManagedSession(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final Rect bounds) throws IOException {
        if (bounds == null || bounds.isEmpty()) {
            throw new IOException("invalid desktop task bounds");
        }
        callService(() -> mService.placeWindowedTaskInManagedSession(
                mCallback,
                taskId,
                sourceDisplayId,
                targetDisplayId,
                bounds.left,
                bounds.top,
                bounds.right,
                bounds.bottom));
    }

    void placeFullscreenTaskInManagedSession(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId) throws IOException {
        callService(() -> mService.placeFullscreenTaskInManagedSession(
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

    TaskWindowSnapshot inspectTaskWindow(
            final int displayId,
            final int taskId) throws IOException {
        return callServiceForResult(() -> mService.inspectTaskWindow(
                mCallback, displayId, taskId));
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

package io.github.mekhontsev.magicdesk;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.UserHandle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.io.Closeable;

final class ShellTaskObserverManager implements Closeable {
    private static final String TAG = "MagicDeskTasks";

    private final Object mLock = new Object();
    private final Context mContext;
    private final ShellWorkspaceMembership mMembership = new ShellWorkspaceMembership();

    private final java.util.Map<IBinder, Session> mSessions = new java.util.LinkedHashMap<>();

    ShellTaskObserverManager(final Context context) {
        mContext = context;
    }

    void start(
            final int displayId,
            final ITaskObserverCallback callback,
            final IActivityLaunchCallback activityLauncher) {
        if (callback == null || activityLauncher == null) {
            throw new IllegalArgumentException(
                    "missing task observer callbacks");
        }
        synchronized (mLock) {
            final Session previous = mSessions.remove(callback.asBinder());
            if (previous != null) {
                publishMembership();
                previous.stop();
            }
            if (mSessions.values().stream().anyMatch(value -> value.displayId == displayId)) {
                throw new IllegalStateException("display already has a task observer: " + displayId);
            }
            Session session = null;
            try {
                session = new Session(displayId, callback, activityLauncher);
                mSessions.put(session.ownerToken, session);
                publishMembership();
                session.start();
                Log.i(TAG, "task observer started");
            } catch (ReflectiveOperationException | RemoteException
                    | RuntimeException error) {
                if (session != null) {
                    mSessions.remove(session.ownerToken, session);
                    publishMembership();
                }
                if (session != null) {
                    session.stop();
                }
                throw new IllegalStateException(
                        "cannot start task observer: "
                                + usefulMessage(error),
                        error);
            }
        }
    }

    void configure(
            final ITaskObserverCallback callback,
            final int displayId,
            final Rect displayBounds,
            final Rect workAreaBounds,
            final int desktopHostTaskId,
            final DesktopCompatibilityPolicy compatibility) {
        final Session session = requireSession(callback);
        if (displayId != session.displayId && displayId >= 0) {
            throw new IllegalArgumentException("observer belongs to display " + session.displayId);
        }
        session.observer.configure(
                displayId,
                displayBounds,
                workAreaBounds,
                desktopHostTaskId, compatibility);
    }

    void configureDesktopHomeDelegate(final ITaskObserverCallback callback,
            final int displayId, final int taskId, final IBinder activityToken) {
        requireSession(callback).observer.configureDesktopHomeDelegate(
                displayId, taskId, activityToken);
    }

    void configureDesktopActivityInput(
            final ITaskObserverCallback callback,
            final int displayId,
            final IBinder activityToken) {
        requireSession(callback).observer.configureDesktopActivityInput(
                displayId, activityToken);
    }

    void setDesktopChromeFocusable(final ITaskObserverCallback callback,
            final int displayId, final int taskId, final boolean focusable) {
        requireSession(callback).observer.setDesktopChromeFocusable(
                displayId, taskId, focusable);
    }

    int prepareDesktopChromeHost(
            final ITaskObserverCallback callback,
            final int displayId) {
        return requireSession(callback).observer.prepareDesktopChromeHost(
                displayId);
    }

    boolean clearConfiguration(
            final ITaskObserverCallback callback,
            final int expectedDisplayId) {
        return requireSession(callback).observer.clearConfiguration(
                expectedDisplayId);
    }

    void executeWorkspaceCommand(
            final ITaskObserverCallback callback,
            final long sequence,
            final DesktopWorkspaceCommand command) {
        requireSession(callback).observer.executeWorkspaceCommand(
                sequence, command);
    }

    void notifyInputFocusRefreshComplete(
            final ITaskObserverCallback callback,
            final int taskId) {
        requireSession(callback).observer
                .notifyInputFocusRefreshComplete(taskId);
    }

    boolean concealFullscreenTaskPlanes(
            final ITaskObserverCallback callback,
            final int displayId) {
        return requireSession(callback).observer
                .concealFullscreenTaskPlanes(displayId);
    }

    TaskWindowSnapshot inspectTaskWindow(
            final ITaskObserverCallback callback,
            final int displayId,
            final int taskId) {
        return requireSession(callback).observer.inspectTaskWindow(
                displayId, taskId);
    }

    int launchDesktopHost(
            final int displayId,
            final String intentUri) {
        final Session session;
        synchronized (mLock) {
            session = mSessions.values().stream()
                    .filter(value -> value.displayId == displayId).findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "task observer is not active on display " + displayId));
        }
        return session.observer.launchDesktopHost(
                displayId, intentUri);
    }

    boolean restoreFullscreenTask(
            final ITaskObserverCallback callback,
            final int displayId,
            final int taskId,
            final Rect bounds,
            final int densityDpi) {
        return requireSession(callback).observer.restoreFullscreenTask(
                displayId, taskId, bounds, densityDpi);
    }

    boolean beginAppFullscreenTask(
            final ITaskObserverCallback callback,
            final int displayId,
            final int taskId,
            final Rect restoreBounds,
            final int densityDpi) {
        return requireSession(callback).observer.beginAppFullscreenTask(
                displayId, taskId, restoreBounds, densityDpi);
    }

    boolean beginFullscreenTask(
            final ITaskObserverCallback callback,
            final int displayId,
            final int taskId,
            final int densityDpi) {
        return requireSession(callback).observer.beginFullscreenTask(
                displayId, taskId, densityDpi);
    }

    boolean beginWindowedTask(
            final ITaskObserverCallback callback,
            final int displayId,
            final int taskId,
            final Rect bounds,
            final int densityDpi) {
        return requireSession(callback).observer.beginWindowedTask(
                displayId, taskId, bounds, densityDpi);
    }

    boolean protectExplicitFullscreenTask(
            final ITaskObserverCallback callback,
            final int displayId,
            final int taskId) {
        return requireSession(callback).observer
                .protectExplicitFullscreenTask(displayId, taskId);
    }

    boolean closeDesktopTask(
            final ITaskObserverCallback callback,
            final int displayId,
            final int taskId,
            final int focusTaskId) {
        return requireSession(callback).observer.closeDesktopTask(
                displayId, taskId, focusTaskId);
    }

    int launchWindowedTask(
            final ITaskObserverCallback callback,
            final int displayId,
            final Intent intent,
            final Rect bounds,
            final int densityDpi) {
        return requireSession(callback).observer.launchWindowedTask(
                displayId, intent, bounds, densityDpi);
    }

    int launchFullscreenTask(
            final ITaskObserverCallback callback,
            final int displayId,
            final Intent intent,
            final int densityDpi) {
        return requireSession(callback).observer.launchFullscreenTask(
                displayId, intent, densityDpi);
    }

    int launchAppShortcut(
            final ITaskObserverCallback callback,
            final int displayId,
            final String packageName,
            final String shortcutId,
            final UserHandle user,
            final int windowingMode,
            final Rect bounds,
            final int densityDpi,
            final int existingTaskId) {
        return requireSession(callback).observer.launchAppShortcut(
                displayId,
                packageName,
                shortcutId,
                user,
                windowingMode,
                bounds,
                densityDpi,
                existingTaskId);
    }

    int launchPendingActivity(
            final ITaskObserverCallback callback,
            final int displayId,
            final String expectedPackage,
            final ComponentName expectedComponent,
            final PendingIntent pendingIntent,
            final int windowingMode,
            final Rect bounds,
            final int densityDpi,
            final int existingTaskId) {
        return requireSession(callback).observer.launchPendingActivity(
                displayId,
                expectedPackage,
                expectedComponent,
                pendingIntent,
                windowingMode,
                bounds,
                densityDpi,
                existingTaskId);
    }

    boolean setDesktopTaskDensity(
            final ITaskObserverCallback callback,
            final int displayId,
            final int[] taskIds,
            final int densityDpi) {
        return requireSession(callback).observer.setDesktopTaskDensity(
                displayId, taskIds, densityDpi);
    }

    void launchTaskAction(
            final ITaskObserverCallback callback,
            final int displayId,
            final int taskId,
            final Intent intent) {
        requireSession(callback).observer.launchTaskAction(
                displayId, taskId, intent);
    }

    void startSelfTestTaskStackGuard(
            final ITaskObserverCallback callback,
            final int displayId,
            final int hostTaskId,
            final String stage) {
        requireSession(callback).observer.startSelfTestTaskStackGuard(
                displayId, hostTaskId, stage);
    }

    void setSelfTestTaskStackGuardStage(
            final ITaskObserverCallback callback,
            final String stage) {
        requireSession(callback).observer.setSelfTestTaskStackGuardStage(stage);
    }

    SelfTestTaskStackReport stopSelfTestTaskStackGuard(
            final ITaskObserverCallback callback) {
        return requireSession(callback).observer.stopSelfTestTaskStackGuard();
    }

    void setPhoneTouchpadPreservation(
            final ITaskObserverCallback callback,
            final boolean enabled) {
        requireSession(callback).observer
                .setPhoneTouchpadPreservation(enabled);
    }

    void setPhoneTouchpadRequested(
            final ITaskObserverCallback callback,
            final boolean requested) {
        requireSession(callback).observer
                .setPhoneTouchpadRequested(requested);
    }

    void setExternalTaskMigrationProtection(
            final ITaskObserverCallback callback,
            final boolean enabled) {
        requireSession(callback).observer
                .setExternalTaskMigrationProtection(enabled);
    }

    void refreshTaskCaption(
            final ITaskObserverCallback callback,
            final int displayId,
            final int taskId,
            final int sourceId) {
        try {
            requireSession(callback).observer.refreshTaskCaption(
                    displayId, taskId, sourceId);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IllegalStateException(
                    "cannot refresh task caption: " + usefulMessage(error),
                    error);
        }
    }

    void stop(final ITaskObserverCallback callback) {
        final Session session;
        synchronized (mLock) {
            session = callback == null ? null : mSessions.remove(callback.asBinder());
            if (session == null) {
                return;
            }
            publishMembership();
        }
        session.stop();
        Log.i(TAG, "task observer stopped");
    }

    @Override
    public void close() {
        final java.util.List<Session> sessions;
        synchronized (mLock) {
            sessions = java.util.List.copyOf(mSessions.values());
            mSessions.clear();
            publishMembership();
        }
        for (final Session session : sessions) {
            session.stop();
        }
    }

    private Session requireSession(final ITaskObserverCallback callback) {
        synchronized (mLock) {
            final Session session = callback == null ? null : mSessions.get(callback.asBinder());
            if (session == null) {
                throw new IllegalStateException(
                        "task observer is not active for this client");
            }
            return session;
        }
    }

    private void ownerDisconnected(final Session session) {
        synchronized (mLock) {
            if (!mSessions.remove(session.ownerToken, session)) {
                return;
            }
            publishMembership();
        }
        session.stop();
        Log.i(TAG, "task observer owner disconnected");
    }

    private static String usefulMessage(final Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        final String message = cause.getMessage();
        return message == null || message.isEmpty()
                ? cause.getClass().getSimpleName() : message;
    }

    private void publishMembership() {
        mMembership.update(mSessions.values().stream().map(session -> session.displayId)
                .collect(java.util.stream.Collectors.toSet()));
    }

    private final class Session {
        final int displayId;
        final IBinder ownerToken;
        final IBinder.DeathRecipient ownerDeathRecipient;
        final ShellTaskObserver observer;

        boolean ownerLinked;
        boolean stopped;

        Session(
                final int displayId,
                final ITaskObserverCallback callback,
                final IActivityLaunchCallback activityLauncher)
                throws ReflectiveOperationException {
            this.displayId = displayId;
            ownerToken = callback.asBinder();
            ownerDeathRecipient = this::ownerDisconnected;
            observer = new ShellTaskObserver(
                    mContext,
                    callback,
                    activityLauncher,
                    mMembership,
                    this::ownerDisconnected);
        }

        synchronized void start()
                throws RemoteException, ReflectiveOperationException {
            ownerToken.linkToDeath(ownerDeathRecipient, 0);
            ownerLinked = true;
            observer.start();
        }

        synchronized void stop() {
            if (stopped) {
                return;
            }
            stopped = true;
            if (ownerLinked) {
                ownerToken.unlinkToDeath(ownerDeathRecipient, 0);
                ownerLinked = false;
            }
            try {
                observer.close();
            } catch (RuntimeException error) {
                Log.w(TAG, "task observer cleanup failed", error);
            }
        }

        private void ownerDisconnected() {
            ShellTaskObserverManager.this.ownerDisconnected(this);
        }
    }
}

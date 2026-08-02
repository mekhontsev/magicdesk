package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.io.Closeable;

final class ShellTaskObserverManager implements Closeable {
    private static final String TAG = "MagicDeskTasks";

    private final Object mLock = new Object();
    private final Context mContext;

    private Session mSession;

    ShellTaskObserverManager(final Context context) {
        mContext = context;
    }

    void start(final ITaskObserverCallback callback) {
        if (callback == null) {
            throw new IllegalArgumentException("missing task observer callback");
        }
        synchronized (mLock) {
            if (mSession != null) {
                mSession.stop();
                mSession = null;
            }
            Session session = null;
            try {
                session = new Session(callback);
                mSession = session;
                session.start();
                Log.i(TAG, "task observer started");
            } catch (ReflectiveOperationException | RemoteException
                    | RuntimeException error) {
                if (mSession == session) {
                    mSession = null;
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
            final Rect workAreaBounds) {
        requireSession(callback).observer.configure(
                displayId, displayBounds, workAreaBounds);
    }

    void focusStack(
            final ITaskObserverCallback callback,
            final long sequence,
            final int displayId,
            final int[] taskIds) {
        requireSession(callback).observer.focusStack(
                sequence, displayId, taskIds);
    }

    void stop(final ITaskObserverCallback callback) {
        final Session session;
        synchronized (mLock) {
            if (!ownsSession(callback)) {
                return;
            }
            session = mSession;
            mSession = null;
        }
        session.stop();
        Log.i(TAG, "task observer stopped");
    }

    @Override
    public void close() {
        final Session session;
        synchronized (mLock) {
            session = mSession;
            mSession = null;
        }
        if (session != null) {
            session.stop();
        }
    }

    private Session requireSession(final ITaskObserverCallback callback) {
        synchronized (mLock) {
            if (!ownsSession(callback)) {
                throw new IllegalStateException(
                        "task observer is not active for this client");
            }
            return mSession;
        }
    }

    private boolean ownsSession(final ITaskObserverCallback callback) {
        return callback != null
                && mSession != null
                && mSession.ownerToken.equals(callback.asBinder());
    }

    private void ownerDisconnected(final Session session) {
        synchronized (mLock) {
            if (mSession != session) {
                return;
            }
            mSession = null;
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

    private final class Session {
        final IBinder ownerToken;
        final IBinder.DeathRecipient ownerDeathRecipient;
        final ShellTaskObserver observer;

        boolean ownerLinked;
        boolean stopped;

        Session(final ITaskObserverCallback callback)
                throws ReflectiveOperationException {
            ownerToken = callback.asBinder();
            ownerDeathRecipient = this::ownerDisconnected;
            observer = new ShellTaskObserver(
                    mContext, callback, this::ownerDisconnected);
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
            observer.close();
        }

        private void ownerDisconnected() {
            ShellTaskObserverManager.this.ownerDisconnected(this);
        }
    }
}

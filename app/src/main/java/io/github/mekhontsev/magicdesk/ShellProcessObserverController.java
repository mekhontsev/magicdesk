package io.github.mekhontsev.magicdesk;

import android.app.IProcessObserver;
import android.util.Log;

import java.lang.reflect.Method;

/** Owns the system process observer used for launcher failure detection. */
final class ShellProcessObserverController implements AutoCloseable {
    interface Listener {
        void onProcessDied(int pid, int uid);
    }

    interface ErrorListener {
        void onError(String message);
    }

    private static final String TAG = "MagicDeskTasks";

    private final boolean mEnabled;
    private final Listener mListener;
    private final ErrorListener mErrorListener;
    private final IProcessObserver mObserver = new IProcessObserver.Stub() {
        @Override
        public void onProcessStarted(
                final int pid,
                final int processUid,
                final int packageUid,
                final String packageName,
                final String processName) {
        }

        @Override
        public void onForegroundActivitiesChanged(
                final int pid,
                final int uid,
                final boolean foregroundActivities) {
        }

        @Override
        public void onForegroundServicesChanged(
                final int pid, final int uid, final int serviceTypes) {
        }

        @Override
        public void onProcessDied(final int pid, final int uid) {
            if (mRegistered) {
                dispatch(() -> mListener.onProcessDied(pid, uid));
            }
        }
    };

    private volatile boolean mRegistered;
    private Object mService;

    ShellProcessObserverController(
            final boolean enabled,
            final Listener listener,
            final ErrorListener errorListener) {
        mEnabled = enabled;
        mListener = listener;
        mErrorListener = errorListener;
    }

    synchronized void start() {
        if (!mEnabled || mRegistered) {
            return;
        }
        try {
            final Method getService = Class.forName("android.app.ActivityManager")
                    .getDeclaredMethod("getService");
            getService.setAccessible(true);
            final Object service = getService.invoke(null);
            if (service == null) {
                throw new IllegalStateException(
                        "activity manager service is unavailable");
            }
            service.getClass().getMethod(
                    "registerProcessObserver", IProcessObserver.class)
                    .invoke(service, mObserver);
            mService = service;
            mRegistered = true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            report("process observer unavailable: " + usefulMessage(error));
        }
    }

    @Override
    public synchronized void close() {
        final Object service = mService;
        mRegistered = false;
        mService = null;
        if (service == null) {
            return;
        }
        try {
            service.getClass().getMethod(
                    "unregisterProcessObserver", IProcessObserver.class)
                    .invoke(service, mObserver);
        } catch (ReflectiveOperationException | RuntimeException error) {
            report("process observer release failed: "
                    + usefulMessage(error));
        }
    }

    private void report(final String message) {
        Log.w(TAG, message);
        if (mErrorListener != null) {
            mErrorListener.onError(message);
        }
    }

    private void dispatch(final Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException error) {
            report("process observer callback failed: "
                    + usefulMessage(error));
        }
    }

    private static String usefulMessage(final Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        final String message = cause.getMessage();
        return message == null || message.trim().isEmpty()
                ? cause.getClass().getSimpleName() : message;
    }
}

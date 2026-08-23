package io.github.mekhontsev.magicdesk;

import android.app.IActivityController;
import android.content.Intent;
import android.util.Log;

/** Claims Android's single system-wide activity-controller slot when needed. */
final class ShellActivityStartController implements AutoCloseable {
    interface Listener {
        boolean onActivityStarting(Intent intent, String packageName);
    }

    interface ErrorListener {
        void onError(String message);
    }

    interface Observer {
        void onActivityStarting(
                Intent intent, String packageName, boolean allowed);
    }

    interface ProcessFailureListener {
        void onProcessCrashed(
                String processName, int pid, String shortMessage);
        void onProcessEarlyNotResponding(
                String processName, int pid, String annotation);
        void onProcessNotResponding(String processName, int pid);
    }

    private static final String TAG = "MagicDeskTasks";

    private final Object mService;
    private final Listener[] mListeners;
    private final ErrorListener mErrorListener;
    private final ProcessFailureListener mProcessFailureListener;
    private final Observer mObserver;
    private final IActivityController mController =
            new IActivityController.Stub() {
                @Override
                public boolean activityStarting(
                        final Intent intent,
                        final String packageName) {
                    if (!mEnabled) {
                        return true;
                    }
                    boolean allowed = true;
                    for (final Listener listener : mListeners) {
                        try {
                            if (!listener.onActivityStarting(
                                    intent, packageName)) {
                                allowed = false;
                                break;
                            }
                        } catch (RuntimeException error) {
                            report("activity-start observer failed: "
                                    + usefulMessage(error));
                        }
                    }
                    notifyActivityStart(intent, packageName, allowed);
                    return allowed;
                }

                @Override
                public boolean activityResuming(final String packageName) {
                    return true;
                }

                @Override
                public boolean appCrashed(
                        final String processName,
                        final int pid,
                        final String shortMessage,
                        final String longMessage,
                        final long timeMillis,
                        final String stackTrace) {
                    notifyProcessFailure(() ->
                            mProcessFailureListener.onProcessCrashed(
                                    processName, pid, shortMessage));
                    return true;
                }

                @Override
                public int appEarlyNotResponding(
                        final String processName,
                        final int pid,
                        final String annotation) {
                    notifyProcessFailure(() ->
                            mProcessFailureListener
                                    .onProcessEarlyNotResponding(
                                            processName, pid, annotation));
                    return 0;
                }

                @Override
                public int appNotResponding(
                        final String processName,
                        final int pid,
                        final String processStats) {
                    notifyProcessFailure(() ->
                            mProcessFailureListener.onProcessNotResponding(
                                    processName, pid));
                    return 0;
                }

                @Override
                public int systemNotResponding(final String message) {
                    return -1;
                }
            };

    private boolean mInstalled;
    private volatile boolean mEnabled;

    ShellActivityStartController(
            final Object service,
            final ErrorListener errorListener,
            final ProcessFailureListener processFailureListener,
            final Observer observer,
            final Listener... listeners) {
        mService = service;
        mErrorListener = errorListener;
        mProcessFailureListener = processFailureListener;
        mObserver = observer;
        mListeners = listeners == null ? new Listener[0] : listeners.clone();
    }

    synchronized void start() throws ReflectiveOperationException {
        if (mEnabled) {
            return;
        }
        installController();
        mInstalled = true;
        mEnabled = true;
    }

    @Override
    public synchronized void close() {
        mEnabled = false;
        if (!mInstalled) {
            return;
        }
        try {
            setController(null);
            mInstalled = false;
        } catch (ReflectiveOperationException | RuntimeException error) {
            // A stale controller can block Home and Recents even after the
            // shell process exits on vendor firmware. Report the failed
            // release, but let the rest of desktop cleanup continue.
            report("activity-start observer release failed: "
                    + usefulMessage(error));
        }
    }

    private void installController()
            throws ReflectiveOperationException {
        setController(mController);
    }

    private void setController(final IActivityController controller)
            throws ReflectiveOperationException {
        mService.getClass().getMethod(
                "setActivityController",
                IActivityController.class,
                Boolean.TYPE)
                .invoke(mService, controller, Boolean.FALSE);
    }

    private void report(final String message) {
        Log.w(TAG, message);
        if (mErrorListener != null) {
            mErrorListener.onError(message);
        }
    }

    private void notifyProcessFailure(final Runnable callback) {
        if (!mEnabled || mProcessFailureListener == null) {
            return;
        }
        try {
            callback.run();
        } catch (RuntimeException error) {
            report("process-failure observer failed: "
                    + usefulMessage(error));
        }
    }

    private void notifyActivityStart(
            final Intent intent,
            final String packageName,
            final boolean allowed) {
        if (mObserver == null) {
            return;
        }
        try {
            mObserver.onActivityStarting(intent, packageName, allowed);
        } catch (RuntimeException error) {
            report("activity-start diagnostics failed: "
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

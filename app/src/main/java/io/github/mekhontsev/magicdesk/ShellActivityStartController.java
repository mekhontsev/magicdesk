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
    private final IActivityController mController =
            new IActivityController.Stub() {
                @Override
                public boolean activityStarting(
                        final Intent intent,
                        final String packageName) {
                    if (!mEnabled) {
                        return true;
                    }
                    for (final Listener listener : mListeners) {
                        try {
                            if (!listener.onActivityStarting(
                                    intent, packageName)) {
                                return false;
                            }
                        } catch (RuntimeException error) {
                            report("activity-start observer failed: "
                                    + usefulMessage(error));
                        }
                    }
                    return true;
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
            final Listener... listeners) {
        mService = service;
        mErrorListener = errorListener;
        mProcessFailureListener = processFailureListener;
        mListeners = listeners == null ? new Listener[0] : listeners.clone();
    }

    synchronized void start() throws ReflectiveOperationException {
        if (mInstalled) {
            mEnabled = true;
            return;
        }
        installController();
        mInstalled = true;
        mEnabled = true;
    }

    @Override
    public synchronized void close() {
        mEnabled = false;
        // Android exposes no compare-and-clear operation for this global
        // slot. Clearing it here could remove a controller installed later by
        // another tool. Keep this Binder inert; ActivityManager releases it
        // safely when the shell service process exits.
    }

    private void installController()
            throws ReflectiveOperationException {
        mService.getClass().getMethod(
                "setActivityController",
                IActivityController.class,
                Boolean.TYPE)
                .invoke(mService, mController, Boolean.FALSE);
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

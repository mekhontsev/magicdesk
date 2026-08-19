package io.github.mekhontsev.magicdesk;

import android.app.IActivityController;
import android.content.Intent;
import android.util.Log;

/** Owns Android's single system-wide activity-controller slot. */
final class ShellActivityStartController implements AutoCloseable {
    interface Listener {
        boolean onActivityStarting(Intent intent, String packageName);
    }

    interface ErrorListener {
        void onError(String message);
    }

    private static final String TAG = "MagicDeskTasks";

    private final Object mService;
    private final Listener[] mListeners;
    private final ErrorListener mErrorListener;
    private final IActivityController mController =
            new IActivityController.Stub() {
                @Override
                public boolean activityStarting(
                        final Intent intent,
                        final String packageName) {
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
                    return true;
                }

                @Override
                public int appEarlyNotResponding(
                        final String processName,
                        final int pid,
                        final String annotation) {
                    return 0;
                }

                @Override
                public int appNotResponding(
                        final String processName,
                        final int pid,
                        final String processStats) {
                    return 0;
                }

                @Override
                public int systemNotResponding(final String message) {
                    return -1;
                }
            };

    private boolean mRegistered;

    ShellActivityStartController(
            final Object service,
            final ErrorListener errorListener,
            final Listener... listeners) {
        mService = service;
        mErrorListener = errorListener;
        mListeners = listeners == null ? new Listener[0] : listeners.clone();
    }

    void start() throws ReflectiveOperationException {
        if (mRegistered) {
            return;
        }
        setController(mController);
        mRegistered = true;
    }

    @Override
    public void close() {
        if (!mRegistered) {
            return;
        }
        mRegistered = false;
        try {
            setController(null);
        } catch (ReflectiveOperationException | RuntimeException error) {
            report("could not unregister activity-start observer: "
                    + usefulMessage(error));
        }
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

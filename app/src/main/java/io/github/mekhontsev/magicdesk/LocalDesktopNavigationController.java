package io.github.mekhontsev.magicdesk;

import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Serializes the phone-display navigation guard around local desktop sessions. */
final class LocalDesktopNavigationController {
    private static final String TAG = "MagicDeskLocalNavigation";
    private static final Handler MAIN_HANDLER =
            new Handler(Looper.getMainLooper());
    private static final IBinder OWNER_TOKEN = new Binder();
    private static final Object GENERATION_LOCK = new Object();
    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(
                        runnable, "MagicDeskLocalNavigation");
                thread.setDaemon(true);
                return thread;
            });
    private static long sGeneration;

    private LocalDesktopNavigationController() {
    }

    static void acquire(final AcquireCallback callback) {
        final long generation;
        synchronized (GENERATION_LOCK) {
            generation = ++sGeneration;
        }
        EXECUTOR.execute(() -> {
            try {
                DesktopStateStore.load();
                ShellAccess.startLocalDesktopNavigationGuard(OWNER_TOKEN);
                complete(
                        callback,
                        generation,
                        true,
                        "system navigation guarded");
            } catch (IOException error) {
                Log.w(TAG, "could not guard system navigation", error);
                complete(callback, generation, false, error.getMessage());
            }
        });
    }

    static boolean isCurrentGeneration(final long generation) {
        synchronized (GENERATION_LOCK) {
            return generation == sGeneration;
        }
    }

    static long currentGeneration() {
        synchronized (GENERATION_LOCK) {
            return sGeneration;
        }
    }

    static void releaseIfCurrent(
            final long generation,
        final ResultCallback callback) {
        EXECUTOR.execute(() -> {
            if (!isCurrentGeneration(generation)) {
                complete(callback, true, "newer local desktop retained");
                return;
            }
            releaseGuard(callback);
        });
    }

    static void cleanupClosedSession(
            final long generation,
            final CleanupCallback callback) {
        EXECUTOR.execute(() -> {
            if (!isCurrentGeneration(generation)) {
                complete(
                        callback,
                        false,
                        true,
                        "cleanup superseded by a newer local desktop");
                return;
            }
            final PhoneDesktopTaskRecovery.Result recovery =
                    PhoneDesktopTaskRecovery.recoverBlocking(
                            () -> isCurrentGeneration(generation));
            if (recovery.cancelled) {
                complete(
                        callback,
                        false,
                        true,
                        recovery.message);
                return;
            }
            if (!recovery.success) {
                complete(callback, true, false, recovery.message);
                return;
            }
            if (!isCurrentGeneration(generation)) {
                complete(
                        callback,
                        false,
                        true,
                        "cleanup superseded by a newer local desktop");
                return;
            }
            try {
                ShellAccess.stopLocalDesktopNavigationGuard(OWNER_TOKEN);
                complete(callback, true, true, recovery.message);
            } catch (IOException error) {
                Log.w(TAG, "could not restore system navigation", error);
                complete(callback, true, false, error.getMessage());
            }
        });
    }

    static void release(final ResultCallback callback) {
        synchronized (GENERATION_LOCK) {
            ++sGeneration;
        }
        EXECUTOR.execute(() -> {
            releaseGuard(callback);
        });
    }

    static boolean releaseBlocking() {
        synchronized (GENERATION_LOCK) {
            ++sGeneration;
        }
        try {
            final Future<Boolean> result = EXECUTOR.submit(() -> {
                try {
                    ShellAccess.stopLocalDesktopNavigationGuard(OWNER_TOKEN);
                    return Boolean.TRUE;
                } catch (IOException error) {
                    Log.w(TAG, "could not restore system navigation", error);
                    return Boolean.FALSE;
                }
            });
            return result.get().booleanValue();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "system navigation restore was interrupted", error);
            return false;
        } catch (ExecutionException error) {
            Log.w(TAG, "could not restore system navigation", error);
            return false;
        }
    }

    private static void releaseGuard(final ResultCallback callback) {
        try {
            ShellAccess.stopLocalDesktopNavigationGuard(OWNER_TOKEN);
            complete(callback, true, "system navigation restored");
        } catch (IOException error) {
            Log.w(TAG, "could not restore system navigation", error);
            complete(callback, false, error.getMessage());
        }
    }

    private static void complete(
            final AcquireCallback callback,
            final long generation,
            final boolean success,
            final String message) {
        if (callback != null) {
            MAIN_HANDLER.post(() -> callback.onComplete(
                    generation, success, message));
        }
    }

    private static void complete(
            final CleanupCallback callback,
            final boolean completed,
            final boolean success,
            final String message) {
        if (callback != null) {
            MAIN_HANDLER.post(() -> callback.onComplete(
                    completed, success, message));
        }
    }

    private static void complete(
            final ResultCallback callback,
            final boolean success,
            final String message) {
        if (callback != null) {
            MAIN_HANDLER.post(() -> callback.onComplete(success, message));
        }
    }

    interface ResultCallback {
        void onComplete(boolean success, String message);
    }

    interface AcquireCallback {
        void onComplete(long generation, boolean success, String message);
    }

    interface CleanupCallback {
        void onComplete(boolean completed, boolean success, String message);
    }
}

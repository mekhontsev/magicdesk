package io.github.mekhontsev.magicdesk;

import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Prevents Nubia Quickstep from opening while phone-display desktop tasks exist. */
final class SystemNavigationGuard implements AutoCloseable {
    private static final String TAG = "MagicDeskSystemNavigation";
    private static final int DISABLE_HOME = 0x00200000;
    private static final int DISABLE_RECENT = 0x01000000;
    private static final int DISABLE_NAVIGATION = DISABLE_HOME | DISABLE_RECENT;
    private static final String STATUS_BAR_SERVICE = "statusbar";

    private final Object mLock = new Object();
    private final IBinder mSystemToken = new Binder();
    private IBinder mOwnerToken;
    private IBinder.DeathRecipient mOwnerDeath;
    private boolean mActive;

    void acquire(final IBinder ownerToken) {
        if (ownerToken == null) {
            throw new IllegalArgumentException("missing navigation guard owner token");
        }
        synchronized (mLock) {
            if (mActive && ownerToken.equals(mOwnerToken)) {
                try {
                    setDisabled(DISABLE_NAVIGATION);
                    return;
                } catch (ReflectiveOperationException | RuntimeException error) {
                    throw new IllegalStateException(
                            "cannot refresh system navigation guard: "
                                    + usefulMessage(error),
                            error);
                }
            }
            releaseLocked(null);
            final IBinder.DeathRecipient ownerDeath =
                    () -> releaseForOwner(ownerToken);
            try {
                ownerToken.linkToDeath(ownerDeath, 0);
                mOwnerToken = ownerToken;
                mOwnerDeath = ownerDeath;
                setDisabled(DISABLE_NAVIGATION);
                mActive = true;
            } catch (ReflectiveOperationException
                    | RemoteException
                    | RuntimeException error) {
                clearOwnerLocked();
                throw new IllegalStateException(
                        "cannot disable system Home and Recents: "
                                + usefulMessage(error),
                        error);
            }
        }
    }

    void release(final IBinder ownerToken) {
        synchronized (mLock) {
            releaseLocked(ownerToken);
        }
    }

    @Override
    public void close() {
        synchronized (mLock) {
            releaseLocked(null);
        }
    }

    private void releaseForOwner(final IBinder ownerToken) {
        synchronized (mLock) {
            try {
                releaseLocked(ownerToken);
            } catch (RuntimeException error) {
                Log.w(TAG, "owner died while restoring system navigation", error);
            }
        }
    }

    private void releaseLocked(final IBinder expectedOwner) {
        if (expectedOwner != null
                && (mOwnerToken == null
                        || !mOwnerToken.equals(expectedOwner))) {
            return;
        }
        RuntimeException failure = null;
        if (mActive) {
            try {
                setDisabled(0);
            } catch (ReflectiveOperationException | RuntimeException error) {
                failure = new IllegalStateException(
                        "cannot restore system Home and Recents: "
                                + usefulMessage(error),
                        error);
            }
        }
        mActive = false;
        clearOwnerLocked();
        if (failure != null) {
            throw failure;
        }
    }

    private void clearOwnerLocked() {
        final IBinder ownerToken = mOwnerToken;
        final IBinder.DeathRecipient ownerDeath = mOwnerDeath;
        mOwnerToken = null;
        mOwnerDeath = null;
        if (ownerToken != null && ownerDeath != null) {
            ownerToken.unlinkToDeath(ownerDeath, 0);
        }
    }

    private void setDisabled(final int flags)
            throws ReflectiveOperationException {
        final Class<?> serviceManager =
                Class.forName("android.os.ServiceManager");
        final IBinder binder = (IBinder) serviceManager
                .getMethod("getService", String.class)
                .invoke(null, STATUS_BAR_SERVICE);
        if (binder == null) {
            throw new IllegalStateException("status bar service is unavailable");
        }
        final Class<?> stub = Class.forName(
                "com.android.internal.statusbar.IStatusBarService$Stub");
        final Object service = stub.getMethod("asInterface", IBinder.class)
                .invoke(null, binder);
        if (service == null) {
            throw new IllegalStateException("status bar binder is unavailable");
        }
        final Class<?> serviceInterface = Class.forName(
                "com.android.internal.statusbar.IStatusBarService");
        final Method disable = serviceInterface.getMethod(
                "disable",
                Integer.TYPE,
                IBinder.class,
                String.class);
        try {
            disable.invoke(
                    service,
                    Integer.valueOf(flags),
                    mSystemToken,
                    BuildConfig.APPLICATION_ID);
        } catch (InvocationTargetException error) {
            final Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw error;
        }
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
}

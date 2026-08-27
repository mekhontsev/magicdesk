package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.BuildConfig;
import io.github.mekhontsev.magicdesk.PlatformPhoneUiDriver;

import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** Prevents Nubia Quickstep from opening for unsafe desktop task layouts. */
final class SystemNavigationGuard
        implements PlatformPhoneUiDriver.NavigationGuard {
    private static final String TAG = "MagicDeskSystemNavigation";
    private static final int DISABLE_HOME = 0x00200000;
    private static final int DISABLE_RECENT = 0x01000000;
    private static final String STATUS_BAR_SERVICE = "statusbar";

    private final Object mLock = new Object();
    private final IBinder mSystemToken = new Binder();
    private final Map<IBinder, OwnerRecord> mOwners = new HashMap<>();
    private int mAppliedFlags;

    @Override
    public void acquire(final IBinder ownerToken) {
        if (ownerToken == null) {
            throw new IllegalArgumentException("missing navigation guard owner token");
        }
        synchronized (mLock) {
            final OwnerRecord previous = mOwners.get(ownerToken);
            if (previous != null) {
                try {
                    applyFlagsLocked(computeFlagsLocked(), true);
                    return;
                } catch (ReflectiveOperationException | RuntimeException error) {
                    throw failure("cannot refresh system navigation guard", error);
                }
            }

            final IBinder.DeathRecipient ownerDeath =
                    () -> releaseForOwner(ownerToken);
            try {
                ownerToken.linkToDeath(ownerDeath, 0);
            } catch (RemoteException | RuntimeException error) {
                throw failure("cannot track navigation guard owner", error);
            }
            final OwnerRecord replacement = new OwnerRecord(ownerDeath);

            mOwners.put(ownerToken, replacement);
            try {
                applyFlagsLocked(computeFlagsLocked(), false);
            } catch (ReflectiveOperationException | RuntimeException error) {
                mOwners.remove(ownerToken);
                ownerToken.unlinkToDeath(replacement.ownerDeath, 0);
                throw failure("cannot disable system navigation", error);
            }
        }
    }

    @Override
    public void release(final IBinder ownerToken) {
        synchronized (mLock) {
            releaseLocked(ownerToken);
        }
    }

    @Override
    public void close() {
        synchronized (mLock) {
            RuntimeException failure = null;
            try {
                applyFlagsLocked(0, false);
            } catch (ReflectiveOperationException | RuntimeException error) {
                failure = failure("cannot restore system navigation", error);
            }
            for (final Map.Entry<IBinder, OwnerRecord> entry
                    : mOwners.entrySet()) {
                entry.getKey().unlinkToDeath(entry.getValue().ownerDeath, 0);
            }
            mOwners.clear();
            mAppliedFlags = 0;
            if (failure != null) {
                throw failure;
            }
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

    private void releaseLocked(final IBinder ownerToken) {
        if (ownerToken == null) {
            return;
        }
        final OwnerRecord removed = mOwners.remove(ownerToken);
        if (removed == null) {
            return;
        }
        RuntimeException failure = null;
        try {
            applyFlagsLocked(computeFlagsLocked(), false);
        } catch (ReflectiveOperationException | RuntimeException error) {
            failure = failure("cannot restore system navigation", error);
        }
        ownerToken.unlinkToDeath(removed.ownerDeath, 0);
        if (failure != null) {
            throw failure;
        }
    }

    private int computeFlagsLocked() {
        return mOwners.isEmpty() ? 0 : DISABLE_HOME | DISABLE_RECENT;
    }

    private void applyFlagsLocked(final int flags, final boolean force)
            throws ReflectiveOperationException {
        if (!force && flags == mAppliedFlags) {
            return;
        }
        setDisabled(flags);
        mAppliedFlags = flags;
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

    private static IllegalStateException failure(
            final String action, final Throwable error) {
        return new IllegalStateException(
                action + ": " + usefulMessage(error), error);
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

    private static final class OwnerRecord {
        final IBinder.DeathRecipient ownerDeath;

        OwnerRecord(final IBinder.DeathRecipient ownerDeath) {
            this.ownerDeath = ownerDeath;
        }
    }
}

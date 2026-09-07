package io.github.mekhontsev.magicdesk;

import android.os.IBinder;

import java.lang.reflect.Method;

/** Owns one desktop's temporary Android IME fallback, including restoration. */
final class DisplayImePolicyController implements AutoCloseable {
    private static final int FALLBACK_TO_DEFAULT_DISPLAY = 1;

    private static volatile Access sAccess;

    interface Api {
        int get(int displayId) throws ReflectiveOperationException;
        void set(int displayId, int policy) throws ReflectiveOperationException;
    }

    private final Api mApi;
    private int mDisplayId = -1;
    private int mPreviousPolicy;
    private boolean mApplied;

    DisplayImePolicyController() {
        this(new Api() {
            @Override
            public int get(final int displayId) throws ReflectiveOperationException {
                final Access access = access();
                return ((Integer) access.get.invoke(
                        access.windowManager, Integer.valueOf(displayId))).intValue();
            }

            @Override
            public void set(final int displayId, final int policy)
                    throws ReflectiveOperationException {
                final Access access = access();
                access.set.invoke(access.windowManager,
                        Integer.valueOf(displayId), Integer.valueOf(policy));
            }
        });
    }

    DisplayImePolicyController(final Api api) {
        mApi = api;
    }

    synchronized void configure(final int displayId)
            throws ReflectiveOperationException {
        final int target = displayId > 0 ? displayId : -1;
        if (target == mDisplayId && mApplied) {
            return;
        }
        if (target != mDisplayId) {
            restore();
        }
        if (target < 0) {
            return;
        }
        if (mDisplayId < 0) {
            mPreviousPolicy = mApi.get(target);
            // Retain the original value even if a write succeeds but its
            // acknowledgement fails. Teardown can still undo our change.
            mDisplayId = target;
        }
        if (mApi.get(target) != FALLBACK_TO_DEFAULT_DISPLAY) {
            mApi.set(target, FALLBACK_TO_DEFAULT_DISPLAY);
        }
        if (mApi.get(target) != FALLBACK_TO_DEFAULT_DISPLAY) {
            throw new IllegalStateException("display IME fallback was not applied");
        }
        mApplied = true;
    }

    private void restore() throws ReflectiveOperationException {
        // Do not overwrite a policy changed by another owner during the session.
        if (mDisplayId > 0 && mPreviousPolicy != FALLBACK_TO_DEFAULT_DISPLAY
                && mApi.get(mDisplayId) == FALLBACK_TO_DEFAULT_DISPLAY) {
            mApi.set(mDisplayId, mPreviousPolicy);
        }
        mDisplayId = -1;
        mApplied = false;
    }

    @Override
    public synchronized void close() {
        try {
            restore();
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("cannot restore display IME policy", error);
        }
    }

    private static Access access() throws ReflectiveOperationException {
        Access access = sAccess;
        if (access != null) {
            return access;
        }
        synchronized (DisplayImePolicyController.class) {
            access = sAccess;
            if (access == null) {
                access = new Access();
                sAccess = access;
            }
        }
        return access;
    }

    private static final class Access {
        final Object windowManager;
        final Method get;
        final Method set;

        Access() throws ReflectiveOperationException {
            final IBinder binder = (IBinder) Class
                    .forName("android.os.ServiceManager")
                    .getMethod("getService", String.class)
                    .invoke(null, "window");
            if (binder == null) {
                throw new IllegalStateException(
                        "window service is unavailable");
            }
            final Class<?> interfaceType = Class.forName(
                    "android.view.IWindowManager");
            windowManager = Class
                    .forName("android.view.IWindowManager$Stub")
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, binder);
            if (windowManager == null) {
                throw new IllegalStateException(
                        "window manager interface is unavailable");
            }
            get = interfaceType.getMethod(
                    "getDisplayImePolicy", int.class);
            set = interfaceType.getMethod(
                    "setDisplayImePolicy", int.class, int.class);
        }
    }
}

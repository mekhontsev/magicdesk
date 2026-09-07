package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.ShizukuCapabilityProbe;

import android.os.IBinder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** Refreshes RedMagic's transient service-working state for an application UID. */
final class NubiaCpuFreezerWorkingState {
    private static final String SERVICE_NAME = "cfreezer";
    private static final String INTERFACE_NAME =
            "com.zte.performance.cfreezer.ICpuFreezerManager";
    private static final String REASON = "service";

    private NubiaCpuFreezerWorkingState() {
    }

    static boolean isAvailable() {
        try {
            requireAvailable();
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            return false;
        }
    }

    static void appendCapabilityProbe(final StringBuilder report) {
        try {
            requireAvailable();
            ShizukuCapabilityProbe.append(report, "vendor.cpu_freezer", "present",
                    SERVICE_NAME + " / " + INTERFACE_NAME
                            + "#noteCpuFreezerUidWorking; no UID state changed");
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalStateException error) {
            ShizukuCapabilityProbe.append(report, "vendor.cpu_freezer", "unavailable",
                    ShizukuCapabilityProbe.usefulMessage(error));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            ShizukuCapabilityProbe.append(report, "vendor.cpu_freezer", "error",
                    ShizukuCapabilityProbe.usefulMessage(error));
        }
    }

    private static void requireAvailable() throws ReflectiveOperationException {
        getService();
        findMethod();
    }

    static Session begin(final int uid) throws ReflectiveOperationException {
        if (uid < 10_000) {
            throw new IllegalArgumentException("invalid application UID " + uid);
        }
        final Object service = getService();
        final Method method = findMethod();
        final Session session = new Session(uid, service, method);
        session.refresh();
        return session;
    }

    private static Object getService() throws ReflectiveOperationException {
        final IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class)
                .invoke(null, SERVICE_NAME);
        if (binder == null) {
            throw new IllegalStateException(
                    "RedMagic CPU-freezer service is unavailable");
        }
        final Object service = Class.forName(INTERFACE_NAME + "$Stub")
                .getMethod("asInterface", IBinder.class)
                .invoke(null, binder);
        if (service == null) {
            throw new IllegalStateException("CPU-freezer interface is unavailable");
        }
        return service;
    }

    private static Method findMethod() throws ReflectiveOperationException {
        return Class.forName(INTERFACE_NAME).getMethod(
                "noteCpuFreezerUidWorking",
                Integer.TYPE,
                Boolean.TYPE,
                String.class);
    }

    static final class Session {
        private final int mUid;
        private final Object mService;
        private final Method mMethod;
        private final AtomicBoolean mActive = new AtomicBoolean(true);

        Session(final int uid, final Object service, final Method method) {
            mUid = uid;
            mService = service;
            mMethod = method;
        }

        void refresh() throws ReflectiveOperationException {
            if (!mActive.get()) {
                throw new IllegalStateException("CPU-freezer session is closed");
            }
            invoke(true);
        }

        boolean close() {
            if (!mActive.compareAndSet(true, false)) {
                return true;
            }
            try {
                invoke(false);
                return true;
            } catch (ReflectiveOperationException | RuntimeException error) {
                // Nubia also expires an unrefreshed working state internally.
                return false;
            }
        }

        private void invoke(final boolean working)
                throws ReflectiveOperationException {
            try {
                mMethod.invoke(mService, mUid, Boolean.valueOf(working), REASON);
            } catch (InvocationTargetException error) {
                final Throwable cause = error.getCause();
                if (cause instanceof ReflectiveOperationException) {
                    throw (ReflectiveOperationException) cause;
                }
                throw error;
            }
        }
    }
}

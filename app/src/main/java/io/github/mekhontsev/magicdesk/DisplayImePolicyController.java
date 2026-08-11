package io.github.mekhontsev.magicdesk;

import android.os.IBinder;

import java.lang.reflect.Method;

/** Keeps the software keyboard on the phone during an external desktop. */
final class DisplayImePolicyController {
    private static final int FALLBACK_TO_DEFAULT_DISPLAY = 1;

    private static volatile Access sAccess;

    private DisplayImePolicyController() {
    }

    static boolean routeToPhone(final int displayId)
            throws ReflectiveOperationException {
        if (displayId <= android.view.Display.DEFAULT_DISPLAY) {
            throw new IllegalArgumentException(
                    "display IME policy requires a secondary display");
        }
        final Access access = access();
        access.set.invoke(
                access.windowManager,
                Integer.valueOf(displayId),
                Integer.valueOf(FALLBACK_TO_DEFAULT_DISPLAY));
        return ((Integer) access.get.invoke(
                access.windowManager,
                Integer.valueOf(displayId))).intValue()
                == FALLBACK_TO_DEFAULT_DISPLAY;
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

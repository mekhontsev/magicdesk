package io.github.mekhontsev.magicdesk;

import android.os.Bundle;
import android.os.IBinder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Locks the device through WindowManager from MagicDesk's root app_process context. */
public final class DeviceLockCommand {
    private DeviceLockCommand() {
    }

    public static void main(final String[] args) {
        if (args.length != 0) {
            System.err.println("usage: DeviceLockCommand");
            System.exit(64);
            return;
        }

        try {
            final Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            final IBinder binder = (IBinder) serviceManagerClass
                    .getMethod("getService", String.class)
                    .invoke(null, "window");
            if (binder == null) {
                throw new IllegalStateException("window service is unavailable");
            }

            final Class<?> windowManagerInterface =
                    Class.forName("android.view.IWindowManager");
            final Object windowManager = Class
                    .forName("android.view.IWindowManager$Stub")
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, binder);
            final Method lockNow =
                    windowManagerInterface.getMethod("lockNow", Bundle.class);
            lockNow.invoke(windowManager, new Object[] {null});
            System.out.println("device-locked");
        } catch (InvocationTargetException e) {
            final Throwable cause = e.getCause() != null ? e.getCause() : e;
            System.err.println("device lock failed: " + cause);
            System.exit(1);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("device lock failed: " + e);
            System.exit(1);
        }
    }
}

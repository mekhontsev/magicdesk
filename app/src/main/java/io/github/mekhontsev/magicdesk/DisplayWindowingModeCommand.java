package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.os.IBinder;

import java.lang.reflect.Method;

/** Sets the default windowing mode before a secondary desktop host is launched. */
@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
public final class DisplayWindowingModeCommand {
    private static final int WINDOWING_MODE_FREEFORM = 5;

    private DisplayWindowingModeCommand() {
    }

    public static void main(final String[] args) {
        if (args.length != 1) {
            System.err.println(
                    "usage: DisplayWindowingModeCommand <display-id>");
            System.exit(64);
            return;
        }
        try {
            final int displayId = Integer.parseInt(args[0]);
            if (displayId <= 0) {
                throw new IllegalArgumentException("invalid display id");
            }
            setFreeform(displayId);
            System.out.println("display-freeform=" + displayId);
        } catch (ReflectiveOperationException | RuntimeException error) {
            System.err.println("display windowing mode failed: " + error);
            System.exit(1);
        }
    }

    private static void setFreeform(final int displayId)
            throws ReflectiveOperationException {
        final IBinder binder = (IBinder) Class
                .forName("android.os.ServiceManager")
                .getMethod("getService", String.class)
                .invoke(null, "window");
        if (binder == null) {
            throw new IllegalStateException("window service is unavailable");
        }
        final Class<?> interfaceType = Class.forName("android.view.IWindowManager");
        final Object windowManager = Class
                .forName("android.view.IWindowManager$Stub")
                .getMethod("asInterface", IBinder.class)
                .invoke(null, binder);
        if (windowManager == null) {
            throw new IllegalStateException(
                    "window manager interface is unavailable");
        }
        final Method getWindowingMode = interfaceType.getMethod(
                "getWindowingMode", Integer.TYPE);
        final int current = ((Integer) getWindowingMode.invoke(
                windowManager, Integer.valueOf(displayId))).intValue();
        if (current == WINDOWING_MODE_FREEFORM) {
            return;
        }
        interfaceType.getMethod(
                "setWindowingMode", Integer.TYPE, Integer.TYPE)
                .invoke(windowManager,
                        Integer.valueOf(displayId),
                        Integer.valueOf(WINDOWING_MODE_FREEFORM));
    }
}

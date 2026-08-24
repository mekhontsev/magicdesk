package io.github.mekhontsev.magicdesk.displayfixes;

import android.os.Bundle;
import android.os.IBinder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Invokes Nubia's display refresh binder method from a root app_process. */
public final class NubiaDisplayRefreshCommand {
    private static final int CMD_REFRESH_HDMI_MODE = 10;

    private NubiaDisplayRefreshCommand() {
    }

    public static void main(final String[] args) {
        if (args.length != 0) {
            System.err.println("usage: NubiaDisplayRefreshCommand");
            System.exit(64);
            return;
        }
        try {
            final Class<?> serviceManager =
                    Class.forName("android.os.ServiceManager");
            final Method getService = serviceManager.getDeclaredMethod(
                    "getService", String.class);
            final IBinder binder = (IBinder) getService.invoke(null, "display");
            if (binder == null) {
                throw new IllegalStateException(
                        "display service is unavailable");
            }

            final Class<?> stub = Class.forName(
                    "android.hardware.display.IDisplayManager$Stub");
            final Object displayService = stub.getDeclaredMethod(
                    "asInterface", IBinder.class).invoke(null, binder);
            final Method setCmdToDisplay = displayService.getClass().getMethod(
                    "setCmdToDisplay",
                    int.class,
                    int.class,
                    int.class,
                    Bundle.class);
            setCmdToDisplay.invoke(
                    displayService,
                    CMD_REFRESH_HDMI_MODE,
                    -1,
                    -1,
                    null);
            System.out.println("display-refresh=ok");
        } catch (InvocationTargetException error) {
            final Throwable cause = error.getCause() == null
                    ? error : error.getCause();
            System.err.println("display refresh failed: " + cause);
            System.exit(1);
        } catch (ReflectiveOperationException | RuntimeException error) {
            System.err.println("display refresh failed: " + error);
            System.exit(1);
        }
    }
}

package io.github.mekhontsev.magicdesk.platform.nubia;

import android.os.Bundle;
import android.os.IBinder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Refreshes Nubia's physical HDMI mode from a shell-UID app_process. */
public final class NubiaDisplayRefreshCommand {
    private static final int CMD_REFRESH_HDMI_MODE = 10;

    private NubiaDisplayRefreshCommand() {
    }

    public static void main(final String[] args) {
        if (args.length != 2 || !"refresh".equals(args[0])) {
            System.err.println(
                    "usage: NubiaDisplayRefreshCommand refresh <display-id>");
            System.exit(64);
            return;
        }
        try {
            Integer.parseInt(args[1]);
        } catch (NumberFormatException error) {
            System.err.println("invalid display id: " + args[1]);
            System.exit(64);
            return;
        }
        invokeDisplayCommand();
    }

    private static void invokeDisplayCommand() {
        try {
            final Class<?> serviceManagerClass =
                    Class.forName("android.os.ServiceManager");
            final Method getService = serviceManagerClass.getDeclaredMethod(
                    "getService", String.class);
            final IBinder binder =
                    (IBinder) getService.invoke(null, "display");
            if (binder == null) {
                throw new IllegalStateException(
                        "display service is unavailable");
            }
            final Class<?> stubClass = Class.forName(
                    "android.hardware.display.IDisplayManager$Stub");
            final Object displayService = stubClass.getDeclaredMethod(
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
            System.out.println("display-command=refresh");
        } catch (InvocationTargetException error) {
            final Throwable cause = error.getCause() != null
                    ? error.getCause() : error;
            System.err.println("display command failed: " + cause);
            System.exit(1);
        } catch (ReflectiveOperationException | RuntimeException error) {
            System.err.println("display command failed: " + error);
            System.exit(1);
        }
    }
}

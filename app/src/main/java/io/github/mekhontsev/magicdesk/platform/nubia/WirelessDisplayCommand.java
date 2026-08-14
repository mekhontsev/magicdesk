package io.github.mekhontsev.magicdesk.platform.nubia;

import android.os.IBinder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Disconnects Android's active Wi-Fi display from a shell-UID app_process. */
public final class WirelessDisplayCommand {
    private WirelessDisplayCommand() {
    }

    public static void main(final String[] args) {
        if (args.length != 0) {
            System.err.println("usage: WirelessDisplayCommand");
            System.exit(64);
            return;
        }

        try {
            final Class<?> serviceManagerClass =
                    Class.forName("android.os.ServiceManager");
            final IBinder binder = (IBinder) serviceManagerClass
                    .getMethod("getService", String.class)
                    .invoke(null, "display");
            if (binder == null) {
                throw new IllegalStateException(
                        "display service is unavailable");
            }

            final Object displayManager = Class
                    .forName("android.hardware.display.IDisplayManager$Stub")
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, binder);
            final Method disconnect = displayManager.getClass()
                    .getMethod("disconnectWifiDisplay");
            disconnect.invoke(displayManager);
            System.out.println("wireless-display-disconnected");
        } catch (InvocationTargetException error) {
            final Throwable cause = error.getCause() != null
                    ? error.getCause() : error;
            System.err.println("wireless display disconnect failed: " + cause);
            System.exit(1);
        } catch (ReflectiveOperationException | RuntimeException error) {
            System.err.println("wireless display disconnect failed: " + error);
            System.exit(1);
        }
    }
}

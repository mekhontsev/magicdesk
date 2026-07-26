package io.github.mekhontsev.magicdesk;

import android.os.IBinder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Requests the vendor input stack to rebuild its mouse viewport. */
public final class MouseViewportCommand {
    private static final int MOUSE_CMD_CREATE_OR_UPDATE = 0;

    private MouseViewportCommand() {
    }

    public static void main(final String[] args) {
        if (args.length != 0) {
            System.err.println("usage: MouseViewportCommand");
            System.exit(64);
            return;
        }

        try {
            final Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            final IBinder binder = (IBinder) serviceManagerClass
                    .getMethod("getService", String.class)
                    .invoke(null, "input");
            if (binder == null) {
                throw new IllegalStateException("input service is unavailable");
            }
            final Class<?> inputManagerInterface =
                    Class.forName("android.hardware.input.IInputManager");
            final Object inputManager = Class
                    .forName("android.hardware.input.IInputManager$Stub")
                    .getMethod("asInterface", IBinder.class)
                    .invoke(null, binder);
            final Method sendMouseCmd =
                    inputManagerInterface.getMethod("sendMouseCmd", int.class);
            sendMouseCmd.invoke(inputManager,
                    Integer.valueOf(MOUSE_CMD_CREATE_OR_UPDATE));
            System.out.println("mouse-viewport=updated");
        } catch (InvocationTargetException e) {
            final Throwable cause = e.getCause() != null ? e.getCause() : e;
            System.err.println("mouse viewport update failed: " + cause);
            System.exit(1);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("mouse viewport update failed: " + e);
            System.exit(1);
        }
    }
}

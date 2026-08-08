package io.github.mekhontsev.magicdesk;

import android.os.Bundle;
import android.os.IBinder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Invokes Nubia's vendor display command from a shell-UID app_process. */
public final class ConsoleDisplayCommand {
    private static final int CMD_MIRROR = 0;
    private static final int CMD_EXPAND = 1;
    private static final int CMD_REFRESH_HDMI_MODE = 10;

    private ConsoleDisplayCommand() {
    }

    public static void main(final String[] args) {
        if (args.length != 2
                || (!"expand".equals(args[0])
                && !"mirror".equals(args[0])
                && !"refresh".equals(args[0]))) {
            usage();
            return;
        }

        final int displayId;
        try {
            displayId = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            System.err.println("invalid display id: " + args[1]);
            System.exit(64);
            return;
        }
        final boolean expand = "expand".equals(args[0]);
        final boolean refresh = "refresh".equals(args[0]);
        if (expand && displayId <= 0) {
            System.err.println("expand requires a physical display id");
            System.exit(64);
            return;
        }

        final int command = expand ? CMD_EXPAND
                : refresh ? CMD_REFRESH_HDMI_MODE
                : CMD_MIRROR;
        invokeDisplayCommand(
                command,
                expand ? displayId : refresh ? -1 : 0,
                refresh ? -1 : 0);
    }

    private static void invokeDisplayCommand(
            final int command,
            final int displayId,
            final int value) {
        try {
            final Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            final Method getService = serviceManagerClass.getDeclaredMethod(
                    "getService", String.class);
            final IBinder binder = (IBinder) getService.invoke(null, "display");
            if (binder == null) {
                throw new IllegalStateException("display service is unavailable");
            }

            final Class<?> stubClass = Class.forName(
                    "android.hardware.display.IDisplayManager$Stub");
            final Object displayService = stubClass.getDeclaredMethod(
                    "asInterface", IBinder.class).invoke(null, binder);
            final Method setCmdToDisplay = displayService.getClass().getMethod(
                    "setCmdToDisplay", int.class, int.class, int.class, Bundle.class);
            setCmdToDisplay.invoke(
                    displayService, command, displayId, value, null);
            final String commandName = command == CMD_EXPAND ? "expand"
                    : command == CMD_REFRESH_HDMI_MODE ? "refresh" : "mirror";
            System.out.println("display-command=" + commandName
                    + " display=" + displayId);
        } catch (InvocationTargetException e) {
            final Throwable cause = e.getCause() != null ? e.getCause() : e;
            System.err.println("display command failed: " + cause);
            System.exit(1);
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("display command failed: " + e);
            System.exit(1);
        }
    }

    private static void usage() {
        System.err.println(
                "usage: ConsoleDisplayCommand "
                        + "<expand|mirror|refresh> <display-id>");
        System.exit(64);
    }
}

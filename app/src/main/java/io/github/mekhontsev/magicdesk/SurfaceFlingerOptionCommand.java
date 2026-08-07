package io.github.mekhontsev.magicdesk;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Applies the narrow SurfaceFlinger policy needed by external captions. */
public final class SurfaceFlingerOptionCommand {
    private static final int WIRELESS_PRIVACY_MODE_OPTION = 1100;
    private static final int WIRED_PRIVACY_MODE_OPTION = 1102;

    private SurfaceFlingerOptionCommand() {
    }

    public static void main(final String[] args) {
        if (args.length != 3 || !"set".equals(args[0])) {
            usage();
            return;
        }

        final int option;
        final int value;
        try {
            option = Integer.parseInt(args[1]);
            value = Integer.parseInt(args[2]);
        } catch (NumberFormatException error) {
            usage();
            return;
        }
        if ((option != WIRELESS_PRIVACY_MODE_OPTION
                && option != WIRED_PRIVACY_MODE_OPTION)
                || (value != 0 && value != 1)) {
            usage();
            return;
        }
        setOption(option, value);
    }

    private static void setOption(final int option, final int value) {
        try {
            final Class<?> surfaceControl =
                    Class.forName("android.view.SurfaceControl");
            final Method setter = surfaceControl.getDeclaredMethod(
                    "setSFOption", int.class, int.class);
            setter.invoke(null, option, value);
            System.out.println("external-task-captions="
                    + (value == 0 ? "enabled" : "restored")
                    + " sf-option=" + option
                    + " value=" + value);
        } catch (InvocationTargetException e) {
            final Throwable cause = e.getCause() != null ? e.getCause() : e;
            System.err.println("SurfaceFlinger option failed: " + cause);
            System.exit(1);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            System.err.println("SurfaceFlinger option failed: " + e);
            System.exit(1);
        }
    }

    private static void usage() {
        System.err.println(
                "usage: SurfaceFlingerOptionCommand "
                        + "set <1100|1102> <0|1>");
        System.exit(64);
    }
}

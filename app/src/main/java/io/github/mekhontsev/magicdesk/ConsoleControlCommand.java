package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.pm.PackageManager;

import java.lang.reflect.Method;

public final class ConsoleControlCommand {
    private static final ComponentName NUBIA_MIRROR_INPUT_SERVICE = new ComponentName(
            "cn.nubia.keymapcenter",
            "cn.nubia.keymapcenter.mirror.MirrorInputService");

    private ConsoleControlCommand() {
    }

    public static void main(final String[] args) {
        if (args.length != 2) {
            printUsage();
            System.exit(64);
            return;
        }

        final boolean enabled;
        if ("true".equals(args[1])) {
            enabled = true;
        } else if ("false".equals(args[1])) {
            enabled = false;
        } else {
            System.err.println("invalid boolean state: " + args[1]);
            System.exit(64);
            return;
        }

        try {
            if ("mirror-input-service".equals(args[0])) {
                setMirrorInputServiceEnabled(enabled);
                System.out.println("mirror-input-service=" + enabled);
                return;
            }
            if (!"phone-screen".equals(args[0])) {
                printUsage();
                System.exit(64);
                return;
            }
            final Class<?> trigger = Class.forName(
                    "com.redmagic.os.RedMagicAppManager$Trigger");
            trigger.getMethod("openScreenOffTP", boolean.class)
                    .invoke(null, enabled);
            System.out.println("phone-screen=" + enabled);
        } catch (ReflectiveOperationException | RuntimeException e) {
            final Throwable cause = e.getCause() == null ? e : e.getCause();
            System.err.println(args[0] + " failed: " + cause);
            System.exit(1);
        }
    }

    private static void setMirrorInputServiceEnabled(final boolean enabled)
            throws ReflectiveOperationException {
        final Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
        final Object packageManager = activityThreadClass
                .getMethod("getPackageManager").invoke(null);
        final Class<?> packageManagerInterface =
                Class.forName("android.content.pm.IPackageManager");
        final int state = enabled
                ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        try {
            final Method method = packageManagerInterface.getMethod(
                    "setComponentEnabledSetting",
                    ComponentName.class,
                    int.class,
                    int.class,
                    int.class,
                    String.class);
            method.invoke(packageManager,
                    NUBIA_MIRROR_INPUT_SERVICE,
                    state,
                    PackageManager.DONT_KILL_APP,
                    0,
                    null);
        } catch (NoSuchMethodException e) {
            final Method method = packageManagerInterface.getMethod(
                    "setComponentEnabledSetting",
                    ComponentName.class,
                    int.class,
                    int.class,
                    int.class);
            method.invoke(packageManager,
                    NUBIA_MIRROR_INPUT_SERVICE,
                    state,
                    PackageManager.DONT_KILL_APP,
                    0);
        }
    }

    private static void printUsage() {
        System.err.println("usage: ConsoleControlCommand "
                + "<phone-screen|mirror-input-service> <true|false>");
    }
}

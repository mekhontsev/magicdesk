package io.github.mekhontsev.magicdesk;

public final class AppProcessCommand {
    private static final String PACKAGE_NAME =
            "io.github.mekhontsev.magicdesk";
    private static final String APK_ASSIGNMENT =
            "APK=$(/system/bin/pm path " + PACKAGE_NAME
                    + " | /system/bin/cut -d: -f2-"
                    + " | /system/bin/head -n 1); ";

    private AppProcessCommand() {
    }

    public static String run(final String mainClass) {
        return run(mainClass, "");
    }

    public static String run(final String mainClass, final String arguments) {
        return APK_ASSIGNMENT + "CLASSPATH=\"$APK\" "
                + invocation(mainClass, arguments);
    }

    public static String exec(final String mainClass, final String arguments) {
        return environment() + "exec " + invocation(mainClass, arguments);
    }

    static String exec(final String mainClass) {
        return exec(mainClass, "");
    }

    static String environment() {
        return APK_ASSIGNMENT + "export CLASSPATH=\"$APK\"; ";
    }

    static String invocation(
            final String mainClass,
            final String arguments) {
        final String suffix = arguments == null || arguments.isEmpty()
                ? "" : " " + arguments;
        return "/system/bin/app_process / " + mainClass + suffix;
    }
}

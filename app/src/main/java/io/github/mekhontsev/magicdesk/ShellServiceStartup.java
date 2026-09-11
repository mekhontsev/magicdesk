package io.github.mekhontsev.magicdesk;

import android.content.Context;

/** Fixed executable/entry point shared by both privileged bootstrap transports. */
final class ShellServiceStartup {
    private ShellServiceStartup() { }

    static void verifyIdentity(int uid) {
        if (!ShellAccess.isSupportedServiceUid(uid) || android.system.Os.getuid() != uid) {
            throw new SecurityException("unexpected service UID");
        }
        if (uid != 2000) return;
        for (String field : java.util.List.of("Uid", "Gid")) {
            final String[] ids = ShellCapabilityProbe.readStatusValue(field).trim().split("\\s+");
            if (ids.length != 4 || !java.util.Arrays.stream(ids).allMatch("2000"::equals)) {
                throw new SecurityException("service did not establish shell " + field);
            }
        }
        if (!"u:r:shell:s0".equals(ShellCapabilityProbe.readFirstLine("/proc/self/attr/current"))) {
            throw new SecurityException("service did not enter the shell SELinux domain");
        }
        for (String field : java.util.List.of("CapEff", "CapPrm", "CapInh", "CapAmb")) {
            if (!ShellCapabilityProbe.readStatusValue(field).matches("0+")) {
                throw new SecurityException("service retained " + field);
            }
        }
    }

    static String[] command(Context context, ShellServiceLauncher.Service service,
            String token, int userId, int uid) {
        if (userId != FrameworkUserApi.userId(android.os.UserHandle.getUserHandleForUid(context.getApplicationInfo().uid))
                || (uid != 0 && uid != 2000) || token == null
                || !token.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) {
            throw new IllegalArgumentException("invalid service startup identity");
        }
        return new String[]{context.getApplicationInfo().nativeLibraryDir + "/libmagicdesk_service_launcher.so",
                context.getApplicationInfo().sourceDir, service.name(), token, Integer.toString(userId),
                Integer.toString(uid), "--nice-name=" + context.getPackageName() + ":privileged_" + service.processName};
    }
}

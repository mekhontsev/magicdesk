package io.github.mekhontsev.magicdesk;

import android.content.Context;

/** Captured once with the backend; never changes the identity of a live service. */
final class ShellPrivilegePolicy {
    private ShellPrivilegePolicy() { }
    static boolean forceShell() { return Active.FORCE_SHELL; }
    static boolean configured(Context context) {
        return context.getSharedPreferences("privileged_service", Context.MODE_PRIVATE)
                .getBoolean("force_shell", false);
    }
    static boolean save(Context context, boolean forceShell) {
        return context.getSharedPreferences("privileged_service", Context.MODE_PRIVATE)
                .edit().putBoolean("force_shell", forceShell).commit();
    }
    static int targetUid(int grantedUid) {
        return forceShell() && grantedUid == 0 ? 2000 : grantedUid;
    }
    static void verifyServiceUid(int uid) {
        if (!ShellAccess.isSupportedServiceUid(uid) || (forceShell() && uid != 2000)) {
            throw new SecurityException("Service UID does not satisfy the selected privilege policy: " + uid);
        }
    }
    private static final class Active {
        static final boolean FORCE_SHELL = configured(MagicDeskApplication.applicationContext());
    }
}

package io.github.mekhontsev.magicdesk;

import java.lang.reflect.Method;

/** Cancels package restarts through ActivityManager from the shell process. */
final class ShellPackageStopController {
    private static final int USER_SYSTEM = 0;

    private final boolean mEnabled;

    ShellPackageStopController(final boolean enabled) {
        mEnabled = enabled;
    }

    void forceStopPackage(final String packageName)
            throws ReflectiveOperationException {
        if (!mEnabled || packageName == null || packageName.isEmpty()) {
            return;
        }
        final Method getService = Class.forName("android.app.ActivityManager")
                .getDeclaredMethod("getService");
        getService.setAccessible(true);
        final Object service = getService.invoke(null);
        if (service == null) {
            throw new IllegalStateException(
                    "activity manager service is unavailable");
        }
        service.getClass().getMethod(
                "forceStopPackage", String.class, int.class)
                .invoke(service, packageName, USER_SYSTEM);
    }
}

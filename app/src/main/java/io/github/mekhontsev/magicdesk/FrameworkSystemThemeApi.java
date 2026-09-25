package io.github.mekhontsev.magicdesk;

import android.os.IBinder;

import java.lang.reflect.Method;

/** UiModeManager policy including the hidden schedule/bedtime discriminator. */
final class FrameworkSystemThemeApi {
    private final Object mService;
    private final Method mGet;
    private final Method mGetCustom;
    private final Method mSet;
    private final Method mSetCustom;
    private final Method mCurrentUser;

    FrameworkSystemThemeApi() throws ReflectiveOperationException {
        final IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "uimode");
        mService = Class.forName("android.app.IUiModeManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        if (mService == null) throw new IllegalStateException("UiModeManager is unavailable");
        final Class<?> api = Class.forName("android.app.IUiModeManager");
        mGet = api.getMethod("getNightMode");
        mGetCustom = api.getMethod("getNightModeCustomType");
        mSet = api.getMethod("setNightMode", int.class);
        mSetCustom = api.getMethod("setNightModeCustomType", int.class);
        mCurrentUser = android.app.ActivityManager.class.getMethod("getCurrentUser");
    }

    SystemNightMode read(final int userId) throws ReflectiveOperationException {
        requireUser(userId);
        final int mode = (Integer) mGet.invoke(mService);
        return SystemNightMode.fromFramework(mode,
                mode == 3 ? (Integer) mGetCustom.invoke(mService) : -1);
    }

    void write(final int userId, final SystemNightMode mode) throws ReflectiveOperationException {
        requireUser(userId);
        if (mode.mode == 3) mSetCustom.invoke(mService, mode.customType);
        else mSet.invoke(mService, mode.mode);
    }

    private void requireUser(final int userId) throws ReflectiveOperationException {
        // UiModeManager's mutation API has no target-user argument. Do not
        // accidentally persist a profile's override for the shell's user.
        if (userId != android.os.Process.myUid() / 100_000
                || userId != (Integer) mCurrentUser.invoke(null)) {
            throw new IllegalStateException("System theme requires the current privileged-service user");
        }
    }
}

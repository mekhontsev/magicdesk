package io.github.mekhontsev.magicdesk;

import android.app.ActivityManager;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.UserHandle;

/** Profile-scoped system API boundary; never substitute user 0 on failure. */
final class FrameworkUserApi {
    private FrameworkUserApi() {
    }

    static int userId(final UserHandle user) {
        if (user == null) {
            throw new IllegalArgumentException("user handle is required");
        }
        try {
            return ((Integer) UserHandle.class.getMethod("getIdentifier")
                    .invoke(user)).intValue();
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("cannot resolve Android user id", error);
        }
    }

    static android.content.Context contextForUser(final android.content.Context context, final int userId) {
        if (userId < 0) throw new IllegalArgumentException("user id is required");
        try {
            final UserHandle user = (UserHandle) UserHandle.class.getMethod("of", int.class).invoke(null, userId);
            return (android.content.Context) android.content.Context.class
                    .getMethod("createContextAsUser", UserHandle.class, int.class).invoke(context, user, 0);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("cannot create profile-scoped context", error);
        }
    }

    static void startShellActivity(final Intent intent, final int userId) {
        if (userId < 0) throw new IllegalArgumentException("user id is required");
        try {
            // A privileged worker is not an AMS-registered application process. Context.startActivity
            // carries its synthetic app identity; use the real shell caller and an explicit user.
            final Object service = HiddenTaskApi.getService();
            final int result = (Integer) service.getClass().getMethod("startActivityAsUser",
                    Class.forName("android.app.IApplicationThread"), String.class, String.class,
                    Intent.class, String.class, IBinder.class, String.class, int.class, int.class,
                    Class.forName("android.app.ProfilerInfo"), Bundle.class, int.class)
                    .invoke(service, null, "com.android.shell", null, intent, null, null, null,
                            -1, 0, null, null, userId);
            final boolean successful = (Boolean) ActivityManager.class
                    .getMethod("isStartResultSuccessful", int.class).invoke(null, result);
            if (!successful) throw new IllegalStateException("startActivityAsUser returned " + result);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("cannot start profile-scoped shell Activity", error);
        }
    }
}

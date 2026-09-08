package io.github.mekhontsev.magicdesk;

import android.os.UserHandle;

/** UserHandle's system API boundary; never substitute user 0 on failure. */
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
}

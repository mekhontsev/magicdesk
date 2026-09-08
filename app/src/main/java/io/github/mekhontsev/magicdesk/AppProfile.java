package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;

/** Resolved Android profile: user id for tasks, non-recycled serial for storage. */
final class AppProfile {
    static final int UNKNOWN_USER_ID = -1;

    final int userId;
    final long serialNumber;

    AppProfile(final int userId, final long serialNumber) {
        if (userId < 0 || serialNumber < 0) {
            throw new IllegalArgumentException("unresolved application profile");
        }
        this.userId = userId;
        this.serialNumber = serialNumber;
    }

    static AppProfile current(final Context context) {
        final UserHandle user = Process.myUserHandle();
        final UserManager manager = context.getSystemService(UserManager.class);
        if (manager == null) {
            throw new IllegalStateException("user service unavailable");
        }
        return new AppProfile(FrameworkUserApi.userId(user),
                manager.getSerialNumberForUser(user));
    }

    AppIdentity application(final String packageName) {
        return new AppIdentity(serialNumber, packageName);
    }

    boolean owns(final int taskUserId) {
        return taskUserId >= 0 && taskUserId == userId;
    }
}

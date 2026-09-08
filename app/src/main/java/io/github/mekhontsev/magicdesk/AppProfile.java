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

    private static AppProfile sCurrent;

    static synchronized AppProfile current(final Context context) {
        if (sCurrent != null) {
            return sCurrent;
        }
        final UserHandle user = Process.myUserHandle();
        final UserManager manager = context.getSystemService(UserManager.class);
        if (manager == null) {
            throw new IllegalStateException("user service unavailable");
        }
        sCurrent = new AppProfile(FrameworkUserApi.userId(user),
                manager.getSerialNumberForUser(user));
        return sCurrent;
    }

    AppIdentity application(final String packageName) {
        return new AppIdentity(serialNumber, packageName);
    }

    boolean owns(final int taskUserId) {
        return taskUserId >= 0 && taskUserId == userId;
    }

    AppIdentity applicationForUser(final int taskUserId, final String packageName) {
        return owns(taskUserId) && PackageNameValidator.isSafe(packageName)
                ? application(packageName) : null;
    }

    AppIdentity application(final TaskRepository.TaskEntry task) {
        return task != null && owns(task.userId)
                && PackageNameValidator.isSafe(task.packageName)
                ? application(task.packageName) : null;
    }

    AppReference reference(final AppLaunchTarget target) {
        return target == null ? null : AppReference.forTarget(application(target.packageName), target);
    }

    AppReference reference(final TaskRepository.TaskEntry task) {
        return AppReference.forTask(application(task), task);
    }

    AppReference reference(final FrameworkTaskSnapshot task) {
        if (task == null || !owns(task.userId)
                || !PackageNameValidator.isSafe(task.packageName)
                || (task.topPackage != null && !task.packageName.equals(task.topPackage))) {
            return null;
        }
        final BuiltInDesktopAppCatalog.Entry builtIn = task.rootComponent == null
                || !BuildConfig.APPLICATION_ID.equals(task.packageName)
                || !task.packageName.equals(task.rootComponent.getPackageName()) ? null
                : BuiltInDesktopAppCatalog.findComponent(task.rootComponent.getClassName());
        return reference(builtIn == null ? AppLaunchTarget.packageDefault(task.packageName)
                : builtIn.launchTarget);
    }

    static AppProfile requireCurrent(final Context context, final AppIdentity application) {
        final AppProfile profile = current(context);
        application.requireProfile(profile);
        return profile;
    }
}

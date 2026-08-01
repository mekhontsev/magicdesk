package io.github.mekhontsev.magicdesk;

import android.app.NativeActivity;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

/** Selects transitions that do not force an Activity relaunch. */
final class FullscreenTransitionPolicy {
    private FullscreenTransitionPolicy() {
    }

    static boolean shouldPreserveClient(
            final Context context,
            final TaskRepository.TaskEntry task) {
        if (task == null) {
            return false;
        }
        final ComponentName component = task.topActivityName == null
                ? null : ComponentName.unflattenFromString(task.topActivityName);
        if (component != null) {
            return isNativeActivity(context, component);
        }
        return packageContainsNativeActivity(context, task.packageName);
    }

    static boolean shouldPreserveClient(
            final Context context,
            final String packageName) {
        return packageContainsNativeActivity(context, packageName);
    }

    private static boolean isNativeActivity(
            final Context context,
            final ComponentName component) {
        try {
            final ActivityInfo activity = context.getPackageManager().getActivityInfo(
                    component,
                    PackageManager.ComponentInfoFlags.of(
                            PackageManager.GET_META_DATA));
            return hasNativeLibrary(activity);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static boolean packageContainsNativeActivity(
            final Context context,
            final String packageName) {
        if (context == null || !PackageNameValidator.isSafe(packageName)) {
            return false;
        }
        try {
            final PackageInfo packageInfo = context.getPackageManager().getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(
                            PackageManager.GET_ACTIVITIES
                                    | PackageManager.GET_META_DATA));
            if (packageInfo.activities == null) {
                return false;
            }
            for (final ActivityInfo activity : packageInfo.activities) {
                if (hasNativeLibrary(activity)) {
                    return true;
                }
            }
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        return false;
    }

    private static boolean hasNativeLibrary(final ActivityInfo activity) {
        // A density pulse relaunches the Activity. NativeActivity teardown can block
        // indefinitely while its native run loop is still active.
        return activity != null
                && activity.metaData != null
                && activity.metaData.containsKey(
                        NativeActivity.META_DATA_LIB_NAME);
    }
}

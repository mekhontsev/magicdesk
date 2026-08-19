package io.github.mekhontsev.magicdesk;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;

/** Identifies transient task remnants whose original activity is gone. */
final class OrphanedTransientTaskPolicy {
    private OrphanedTransientTaskPolicy() {
    }

    static boolean shouldRemove(
            final ActivityManager.RunningTaskInfo task,
            final ActivityInfo topActivityInfo) {
        if (task == null || task.baseIntent == null || topActivityInfo == null) {
            return false;
        }
        return shouldRemove(
                packageName(task.baseIntent.getComponent()),
                packageName(task.baseActivity),
                packageName(task.topActivity),
                topActivityInfo.packageName,
                task.numActivities,
                (topActivityInfo.flags & ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS) != 0);
    }

    static boolean shouldRemove(
            final String originalPackage,
            final String basePackage,
            final String topPackage,
            final String topActivityInfoPackage,
            final int activityCount,
            final boolean excludedFromRecents) {
        return excludedFromRecents
                && activityCount == 1
                && originalPackage != null
                && basePackage != null
                && basePackage.equals(topPackage)
                && basePackage.equals(topActivityInfoPackage)
                && !basePackage.equals(originalPackage);
    }

    private static String packageName(final ComponentName component) {
        return component == null ? null : component.getPackageName();
    }
}

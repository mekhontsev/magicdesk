package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;

import java.io.IOException;

/** UI launch policy: local Android tasks or exact privileged placement. */
final class InteractiveActivityLaunch {
    private InteractiveActivityLaunch() { }

    enum OwnTaskResult { SHOWN, MISSING, NEEDS_PLACEMENT }

    static boolean canLaunchLocally(Context context, int displayId) {
        return context instanceof Activity activity && !activity.isFinishing() && !activity.isDestroyed()
                && displayId >= 0 && activity.getDisplay() != null
                && !DesktopRuntimeBridge.hasWorkspaces()
                && (!ShellAccess.isReady() || displayId == 0 && activity.getDisplay().getDisplayId() == 0);
    }

    static void requireDestination(Context context, int displayId, String uniqueId) throws IOException {
        // Preserve the phone-only path even if the display catalog is unavailable.
        if (uniqueId == null && displayId == 0 && context instanceof Activity activity
                && activity.getDisplay() != null && activity.getDisplay().getDisplayId() == 0
                && canLaunchLocally(context, displayId)) return;
        DesktopDisplayCatalog.require(displayId, uniqueId);
    }

    static void launch(Context context, Intent intent,
            AndroidLaunchSpec.Delivery delivery, int displayId) throws IOException {
        if (canLaunchLocally(context, displayId)) {
            requirePublicLaunch(context, intent, displayId);
            final ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(displayId);
            context.startActivity(intent, options.toBundle());
        } else {
            OrdinaryActivityLaunch.launch(context, intent, delivery, displayId);
        }
    }

    private static void requirePublicLaunch(Context context, Intent intent, int displayId) throws IOException {
        if (displayId != 0) DesktopDisplayCatalog.require(displayId, null);
        if (displayId != 0 && !context.getPackageManager().hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS)) {
            throw new IOException("Android does not support Activity launches on secondary displays");
        }
        final ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null || !manager.isActivityStartAllowedOnDisplay(context, displayId, intent)) {
            throw new IOException("Android does not allow this application on display " + displayId);
        }
    }

    static OwnTaskResult showOwnTask(Context context, int taskId, int displayId) {
        if (!canLaunchLocally(context, displayId)) return OwnTaskResult.NEEDS_PLACEMENT;
        final ActivityManager manager = context.getSystemService(ActivityManager.class);
        if (manager == null) return OwnTaskResult.NEEDS_PLACEMENT;
        for (final ActivityManager.AppTask task : manager.getAppTasks()) {
            final ActivityManager.RecentTaskInfo info = task.getTaskInfo();
            if (info.taskId != taskId) continue;
            if (!BuiltInWindowRegistry.isTaskOnDisplay(taskId, displayId)) return OwnTaskResult.NEEDS_PLACEMENT;
            task.moveToFront();
            return OwnTaskResult.SHOWN;
        }
        return OwnTaskResult.MISSING;
    }
}

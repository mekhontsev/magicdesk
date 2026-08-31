package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;

/** One launcher action published by an installed application. */
final class AppShortcutAction {
    final String id;
    final String label;
    final Drawable icon;
    final String source;
    final String packageName;
    final UserHandle user;

    private final AppLaunchTarget mPublishedActivity;
    private final String mPublishedAction;

    AppShortcutAction(
            final String id,
            final String label,
            final Drawable icon,
            final String packageName,
            final UserHandle user,
            final ComponentName publishedActivity,
            final String publishedAction,
            final String source) {
        if (id == null || id.isEmpty()
                || label == null || label.isEmpty()
                || packageName == null || packageName.isEmpty()
                || user == null
                || (publishedActivity != null
                        && !packageName.equals(
                                publishedActivity.getPackageName()))) {
            throw new IllegalArgumentException("invalid app shortcut action");
        }
        this.id = id;
        this.label = label;
        this.icon = icon;
        this.packageName = packageName;
        this.user = user;
        this.source = source == null || source.isEmpty()
                ? "unknown" : source;
        mPublishedActivity = publishedActivity == null
                ? null : AppLaunchTarget.explicit(
                        publishedActivity.getPackageName(),
                        publishedActivity.getClassName(),
                        publishedAction);
        mPublishedAction = publishedAction == null ? "" : publishedAction;
    }

    AppLaunchTarget taskTarget() {
        // A published shortcut may redirect to another Activity in the same
        // app, so its metadata component is not a task identity.
        return AppLaunchTarget.packageDefault(packageName);
    }

    String actionName() {
        return mPublishedAction;
    }

    String componentName() {
        return mPublishedActivity == null ? "" : new ComponentName(
                mPublishedActivity.packageName,
                mPublishedActivity.activityClassName).flattenToShortString();
    }
}

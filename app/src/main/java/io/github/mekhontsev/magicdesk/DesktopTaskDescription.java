package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ActivityManager;

final class DesktopTaskDescription {
    private DesktopTaskDescription() {
    }

    static void apply(
            final Activity activity,
            final int labelResId,
            final int iconResId) {
        final String label = activity.getString(labelResId);
        activity.setTaskDescription(
                new ActivityManager.TaskDescription.Builder()
                        .setLabel(label)
                        .setIcon(iconResId)
                        .build());
    }
}

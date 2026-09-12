package io.github.mekhontsev.magicdesk;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

/** Ordinary Activity launch primitives; no organizer, HOME, or desktop policy. */
final class FrameworkActivityLaunchApi {
    private FrameworkActivityLaunchApi() { }

    private static ActivityOptions options(final int displayId, final boolean fullscreen)
            throws ReflectiveOperationException {
        if (displayId < 0) { throw new IllegalArgumentException("invalid display"); }
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(displayId);
        if (fullscreen) {
            ActivityOptions.class.getMethod("setLaunchWindowingMode", Integer.TYPE)
                    .invoke(options, 1);
        }
        return options;
    }

    static void launch(final Object service, final Intent source, final int displayId,
            final boolean fullscreen) throws ReflectiveOperationException {
        if (source == null || source.getComponent() == null) {
            throw new IllegalArgumentException("an explicit Activity is required");
        }
        final Intent intent = new Intent(source).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final ActivityOptions options = options(displayId, fullscreen);
        final int result = (Integer) service.getClass().getMethod("startActivity",
                Class.forName("android.app.IApplicationThread"), String.class, String.class,
                Intent.class, String.class, IBinder.class, String.class, Integer.TYPE,
                Integer.TYPE, Class.forName("android.app.ProfilerInfo"), Bundle.class)
                .invoke(service, null, "com.android.shell", null, intent, null, null,
                        null, -1, 0, null, options.toBundle());
        if (result < 0) { throw new IllegalStateException("startActivity returned " + result); }
    }

    static void send(final Context context, final PendingIntent intent, final int displayId)
            throws ReflectiveOperationException, PendingIntent.CanceledException {
        if (intent == null || !intent.isActivity()) {
            throw new IllegalArgumentException("an Activity PendingIntent is required");
        }
        final ActivityOptions options = options(displayId, true);
        AndroidPendingIntentOptions.allowSenderStart(options, false);
        intent.send(context, 0, null, null, null, null, options.toBundle());
    }

    static void moveTask(final Object service, final int taskId, final int sourceDisplayId,
            final int targetDisplayId, final int userId) throws ReflectiveOperationException {
        final Object task = HiddenTaskApi.requireTask(service, sourceDisplayId, taskId);
        if (HiddenTaskApi.getTaskUserId(task) != userId) {
            throw new IllegalArgumentException("task profile changed");
        }
        if (HiddenTaskApi.getTaskActivityType(task) != FrameworkTaskSnapshot.ACTIVITY_TYPE_STANDARD) {
            throw new IllegalArgumentException("only application tasks can be transferred");
        }
        // Existing-task launch preserves the Activity instance and lets WM own
        // the display transition. It does not acquire a Desktop organizer.
        final int result = (Integer) service.getClass().getMethod("startActivityFromRecents",
                int.class, Bundle.class).invoke(service, taskId, options(targetDisplayId, true).toBundle());
        if (result < 0) { throw new IllegalStateException("startActivityFromRecents returned " + result); }
    }
}

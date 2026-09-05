package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** Live built-in windows for local UI services and full MagicDesk exit. */
final class BuiltInWindowRegistry {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<WeakReference<Activity>> WINDOWS =
            new ArrayList<>();

    private BuiltInWindowRegistry() {
    }

    static void register(final Activity activity) {
        if (activity == null) {
            return;
        }
        synchronized (WINDOWS) {
            removeLocked(activity);
            WINDOWS.add(new WeakReference<>(activity));
        }
    }

    static void unregister(final Activity activity) {
        synchronized (WINDOWS) {
            removeLocked(activity);
        }
    }

    static View focusedTextEditor(final int displayId, final int taskId) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return null;
        }
        synchronized (WINDOWS) {
            for (final WeakReference<Activity> reference : WINDOWS) {
                final Activity activity = reference.get();
                if (activity == null || activity.isFinishing() || activity.isDestroyed()
                        || activity.getDisplay() == null
                        || activity.getDisplay().getDisplayId() != displayId
                        || activity.getTaskId() != taskId) {
                    continue;
                }
                final View focused = activity.getCurrentFocus();
                if (focused != null && focused.onCheckIsTextEditor()) {
                    return focused;
                }
            }
        }
        return null;
    }

    static void finishAll(final Runnable completion) {
        final Runnable finish = () -> {
            final List<Activity> activities = new ArrayList<>();
            synchronized (WINDOWS) {
                for (final WeakReference<Activity> reference : WINDOWS) {
                    final Activity activity = reference.get();
                    if (activity != null && !activity.isDestroyed()) {
                        activities.add(activity);
                    }
                }
                WINDOWS.clear();
            }
            for (final Activity activity : activities) {
                if (!activity.isFinishing()) {
                    activity.finishAndRemoveTask();
                }
            }
            if (completion != null) {
                completion.run();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            finish.run();
        } else {
            MAIN.post(finish);
        }
    }

    private static void removeLocked(final Activity activity) {
        WINDOWS.removeIf(reference -> {
            final Activity registered = reference.get();
            return registered == null || registered == activity;
        });
    }
}

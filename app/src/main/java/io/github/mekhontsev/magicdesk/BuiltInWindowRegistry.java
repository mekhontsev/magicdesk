package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/** Live built-in windows for local UI services and full MagicDesk exit. */
final class BuiltInWindowRegistry {
    record Presentation(String title, Bitmap icon) { }
    interface PresentationSource {
        Presentation taskPresentation();
    }
    interface ApplicationSource {
        AppReference windowApplication();
    }
    record ImmersiveRequest(long version, boolean requested, boolean foreground) { }
    interface ImmersiveSource {
        ImmersiveRequest immersiveRequest();
        void onImmersiveRejected();
    }
    record ForceCloseAction(int label, String warning) { }
    interface CloseHandler {
        void requestClose(boolean force);
        ForceCloseAction forceCloseAction();
    }
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

    static Presentation presentation(final TaskRepository.TaskEntry task) {
        if (task == null) return null;
        synchronized (WINDOWS) {
            for (final WeakReference<Activity> reference : WINDOWS) {
                final Activity activity = reference.get();
                if (activity != null && !activity.isDestroyed() && !activity.isFinishing()
                        && activity.getTaskId() == task.taskId && activity.getPackageName().equals(task.packageName)
                        && AppProfile.current(activity).owns(task.userId)
                        && activity instanceof PresentationSource source) return source.taskPresentation();
            }
        }
        return null;
    }

    static AppReference resolveWindowApplication(int taskId, int userId, AppReference fallback) {
        if (fallback == null) return null;
        if (fallback.builtIn == null) return fallback.windowStateKey();
        synchronized (WINDOWS) {
            for (WeakReference<Activity> reference : WINDOWS) {
                Activity activity = reference.get();
                if (activity != null && !activity.isDestroyed() && activity.getTaskId() == taskId
                        && activity.getPackageName().equals(fallback.application.packageName)
                        && AppProfile.current(activity).owns(userId) && activity instanceof ApplicationSource source) {
                    AppReference result = source.windowApplication();
                    return result != null && result.application.equals(fallback.application)
                            && result.builtIn == fallback.builtIn ? result.windowStateKey() : null;
                }
            }
        }
        return fallback.windowStateKey();
    }

    static ImmersiveRequest immersiveRequest(int taskId) {
        synchronized (WINDOWS) {
            for (WeakReference<Activity> reference : WINDOWS) {
                Activity activity = reference.get();
                if (activity != null && !activity.isDestroyed() && !activity.isFinishing()
                        && activity.getTaskId() == taskId && activity instanceof ImmersiveSource source)
                    return source.immersiveRequest();
            }
        }
        return null;
    }

    static void rejectImmersive(int taskId, long version) {
        MAIN.post(() -> {
            ImmersiveSource recipient = null;
            synchronized (WINDOWS) {
                for (WeakReference<Activity> reference : WINDOWS) {
                    Activity activity = reference.get();
                    if (activity != null && !activity.isDestroyed() && !activity.isFinishing()
                            && activity.getTaskId() == taskId && activity instanceof ImmersiveSource source) {
                        ImmersiveRequest request = source.immersiveRequest();
                        if (request != null && request.version() == version) recipient = source;
                    }
                }
            }
            if (recipient != null) recipient.onImmersiveRejected();
        });
    }

    static AppItem present(final Context context, final AppItem app, final TaskRepository.TaskEntry task) {
        final Presentation presentation = presentation(task);
        if (app == null) return null;
        AppReference identity = task == null ? app.reference : resolveWindowApplication(task.taskId, task.userId, app.reference);
        if (presentation == null) return app.withReference(identity);
        return new AppItem(app.profile, presentation.title(), app.packageName, app.canFloat,
                app.fullscreenReason, presentation.icon() == null ? app.icon
                        : new BitmapDrawable(context.getResources(), presentation.icon()), app.launchTarget).withReference(identity);
    }

    static boolean needsSeparateTask(final AppLaunchTarget target, final int displayId) {
        boolean otherDisplay = false;
        synchronized (WINDOWS) {
            for (final WeakReference<Activity> reference : WINDOWS) {
                final Activity activity = reference.get();
                if (activity == null || activity.isDestroyed() || activity.isFinishing()
                        || !activity.getClass().getName().equals(target.activityClassName)) { continue; }
                final int existingDisplay = activity.getDisplay() == null
                        ? 0 : activity.getDisplay().getDisplayId();
                if (existingDisplay == displayId) { return false; }
                otherDisplay = true;
            }
        }
        return otherDisplay;
    }

    static boolean isTaskOnDisplay(final int taskId, final int displayId) {
        synchronized (WINDOWS) {
            for (final WeakReference<Activity> reference : WINDOWS) {
                final Activity activity = reference.get();
                if (activity != null && !activity.isDestroyed() && !activity.isFinishing()
                        && activity.getTaskId() == taskId && activity.getDisplay() != null
                        && activity.getDisplay().getDisplayId() == displayId) return true;
            }
        }
        return false;
    }

    private static Activity closeHost(TaskRepository.TaskEntry task) {
        if (task == null) return null;
        synchronized (WINDOWS) {
            for (WeakReference<Activity> reference : WINDOWS) {
                Activity activity = reference.get();
                if (activity != null && !activity.isDestroyed() && !activity.isFinishing()
                        && activity.getTaskId() == task.taskId && activity.getPackageName().equals(task.packageName)
                        && AppProfile.current(activity).owns(task.userId) && activity instanceof CloseHandler)
                    return activity;
            }
        }
        return null;
    }

    static ForceCloseAction forceCloseAction(TaskRepository.TaskEntry task) {
        Activity host = closeHost(task);
        return host == null ? null : ((CloseHandler) host).forceCloseAction();
    }

    static boolean requestClose(TaskRepository.TaskEntry task, boolean force, TaskRepository.ActionCallback callback) {
        Activity host = closeHost(task);
        if (host == null) return false;
        MAIN.post(() -> {
            TaskRepository.ActionResult result;
            try {
                if (closeHost(task) != host) throw new IllegalStateException("Window has closed");
                ((CloseHandler) host).requestClose(force);
                result = new TaskRepository.ActionResult(true, "close requested");
            } catch (RuntimeException error) {
                result = new TaskRepository.ActionResult(false, ShellAccess.usefulMessage(error));
            }
            if (callback != null) callback.onComplete(result);
        });
        return true;
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

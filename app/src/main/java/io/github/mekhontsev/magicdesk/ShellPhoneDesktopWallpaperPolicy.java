package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Intent;
import android.util.Log;
import android.view.Display;

/** Keeps MagicDesk HOME visible below phone-desktop application windows. */
final class ShellPhoneDesktopWallpaperPolicy implements
        ShellActivityStartController.Listener {
    interface ErrorListener {
        void onError(String message);
    }

    private static final String TAG = "MagicDeskTasks";
    private static final String SYSTEM_DESKTOP_WALLPAPER =
            "com.android.systemui/"
                    + "com.android.wm.shell.desktopmode.DesktopWallpaperActivity";

    private final Object mTaskService;
    private final ErrorListener mErrorListener;

    private boolean mEnabled;

    ShellPhoneDesktopWallpaperPolicy(
            final Object taskService,
            final ErrorListener errorListener) {
        mTaskService = taskService;
        mErrorListener = errorListener;
    }

    synchronized void configure(final int desktopDisplayId) {
        mEnabled = desktopDisplayId == Display.DEFAULT_DISPLAY;
        if (mEnabled) {
            removeExistingWallpaperTask();
        }
    }

    @Override
    public synchronized boolean onActivityStarting(
            final Intent intent,
            final String packageName) {
        final ComponentName component = intent == null
                ? null : intent.getComponent();
        if (!shouldBlock(mEnabled, flatten(component))) {
            return true;
        }
        Log.i(TAG, "blocked system desktop wallpaper over MagicDesk HOME");
        return false;
    }

    static boolean shouldBlock(
            final boolean enabled,
            final String component) {
        return enabled && SYSTEM_DESKTOP_WALLPAPER.equals(component);
    }

    private void removeExistingWallpaperTask() {
        try {
            for (final Object task : HiddenTaskApi.getTasks(
                    mTaskService, Display.DEFAULT_DISPLAY)) {
                if (!isWallpaperTask(task)) {
                    continue;
                }
                final int taskId = HiddenTaskApi.getTaskId(task);
                if (!HiddenTaskApi.removeTask(mTaskService, taskId)) {
                    throw new IllegalStateException(
                            "could not remove system desktop wallpaper task "
                                    + taskId);
                }
                Log.i(TAG, "removed existing system desktop wallpaper task="
                        + taskId);
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            report("could not normalize phone desktop wallpaper: "
                    + usefulMessage(error));
        }
    }

    private static boolean isWallpaperTask(final Object task)
            throws ReflectiveOperationException {
        final Intent baseIntent = HiddenTaskApi.getTaskBaseIntent(task);
        return SYSTEM_DESKTOP_WALLPAPER.equals(flatten(
                        HiddenTaskApi.getTaskTopActivity(task)))
                || SYSTEM_DESKTOP_WALLPAPER.equals(flatten(
                        HiddenTaskApi.getTaskBaseActivity(task)))
                || SYSTEM_DESKTOP_WALLPAPER.equals(flatten(baseIntent == null
                        ? null : baseIntent.getComponent()));
    }

    private static String flatten(final ComponentName component) {
        return component == null ? null : component.flattenToString();
    }

    private void report(final String message) {
        Log.w(TAG, message);
        if (mErrorListener != null) {
            mErrorListener.onError(message);
        }
    }

    private static String usefulMessage(final Throwable error) {
        final String message = error == null ? null : error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error == null ? "unknown error"
                        : error.getClass().getSimpleName()
                : message;
    }
}

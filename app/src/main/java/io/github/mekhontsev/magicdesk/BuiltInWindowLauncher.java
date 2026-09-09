package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.util.List;

/** Selects ordinary Activity placement or the existing managed desktop path. */
final class BuiltInWindowLauncher {
    interface Callback {
        void onComplete(Throwable error);
    }

    private BuiltInWindowLauncher() {
    }

    static void launch(
            final Activity activity,
            final Intent intent,
            final AppLaunchTarget target,
            final Callback callback) {
        final int displayId = activity.getDisplay() == null
                ? 0 : activity.getDisplay().getDisplayId();
        launch(activity, intent, target, ToolLaunchTarget.resolve("auto", displayId,
                MagicDeskRuntime.activeDesktopDisplayId()), null, callback);
    }

    static void launch(final Context context, final Intent source,
            final AppLaunchTarget target, final ToolLaunchTarget placement,
            final String uniqueId, final Callback callback) {
        final Intent intent = new Intent(source);
        final int displayId = placement.displayId;
        TaskCommandQueue.execute(() -> {
            try {
                // Check again on the command queue: a session or display may have
                // changed between a UI selection and execution.
                if (placement.desktop != (displayId == MagicDeskRuntime.activeDesktopDisplayId())) {
                    throw new IOException("display ownership changed; select the launch destination again");
                }
                if (displayId != 0 || uniqueId != null) {
                    DesktopDisplayCatalog.require(displayId, uniqueId);
                }
                if (!placement.desktop) {
                    final BuiltInDesktopAppCatalog.Entry entry =
                            BuiltInDesktopAppCatalog.find(target);
                    if (entry == null) { throw new IllegalArgumentException("unknown built-in application"); }
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    if (entry.multipleWindows) {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                    } else if (BuiltInWindowRegistry.needsSeparateTask(target, displayId)) {
                        // An ordinary tool launch must not steal an instance from
                        // another display, especially one owned by Desktop.
                        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                    }
                    if (context instanceof Activity sourceActivity && displayId == 0
                            && sourceActivity.getDisplay() != null
                            && sourceActivity.getDisplay().getDisplayId() == 0) {
                        // Ordinary phone tasks inherit Android's normal fullscreen
                        // workspace. Forced placement on another display is shell-owned.
                        final ActivityOptions options = ActivityOptions.makeBasic();
                        options.setLaunchDisplayId(displayId);
                        new Handler(Looper.getMainLooper()).post(() -> {
                            final Activity activity = (Activity) context;
                            if (activity.isFinishing() || activity.isDestroyed()) { return; }
                            try {
                                context.startActivity(intent, options.toBundle());
                                complete(context, callback, null);
                            } catch (RuntimeException error) {
                                complete(context, callback, error);
                            }
                        });
                    } else {
                        ShellAccess.launchActivityOnDisplay(intent, displayId, true);
                        complete(context, callback, null);
                    }
                    return;
                }
                List<TaskRepository.TaskEntry> visibleTasks =
                        MagicDeskRuntime.getVisibleFreeformTasks(displayId);
                if (visibleTasks == null || visibleTasks.isEmpty()) {
                    visibleTasks = DesktopTaskController
                            .selectVisibleFreeformTasks(
                                    TaskRepository.loadNow(displayId));
                }
                final WindowedAppLauncher.LaunchResult launch =
                        WindowedAppLauncher.launchBuiltInWindow(
                                intent,
                                target,
                                displayId,
                                taskIds(visibleTasks),
                                () -> DesktopRuntimeBridge.syncTaskbarWithSnapshot(
                                        displayId,
                                        TaskRepository.loadNow(displayId)));
                launch.whenReady(result -> complete(context, callback,
                        result.success ? null : new IOException(result.message)));
            } catch (IOException | RuntimeException error) {
                complete(context, callback, error);
            }
        });
    }

    private static int[] taskIds(
            final List<TaskRepository.TaskEntry> tasks) {
        final int[] ids = new int[tasks == null ? 0 : tasks.size()];
        for (int index = 0; index < ids.length; index++) {
            ids[index] = tasks.get(index).taskId;
        }
        return ids;
    }

    private static void complete(
            final Context context,
            final Callback callback,
            final Throwable error) {
        if (callback != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (!(context instanceof Activity activity)
                        || !activity.isFinishing() && !activity.isDestroyed()) {
                    callback.onComplete(error);
                }
            });
        }
    }
}

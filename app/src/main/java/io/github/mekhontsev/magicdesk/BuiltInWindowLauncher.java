package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;

import java.io.IOException;
import java.util.List;

/** Launches one MagicDesk-owned window through the normal desktop path. */
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
        if (!ShellAccess.isReady()) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                        | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                final ActivityOptions options = ActivityOptions.makeBasic();
                options.setLaunchDisplayId(displayId);
                activity.startActivity(intent, options.toBundle());
                complete(activity, callback, null);
            } catch (RuntimeException error) {
                complete(activity, callback, error);
            }
            return;
        }
        TaskCommandQueue.execute(() -> {
            try {
                List<TaskRepository.TaskEntry> visibleTasks =
                        MagicDeskRuntime.getVisibleFreeformTasks(displayId);
                if (visibleTasks == null || visibleTasks.isEmpty()) {
                    visibleTasks = DesktopTaskController
                            .selectVisibleFreeformTasks(
                                    TaskRepository.loadNow(displayId));
                }
                WindowedAppLauncher.launchBuiltInWindow(
                        intent,
                        target,
                        displayId,
                        taskIds(visibleTasks),
                        () -> DesktopRuntimeBridge.syncTaskbarWithSnapshot(
                                displayId,
                                TaskRepository.loadNow(displayId)));
                complete(activity, callback, null);
            } catch (IOException | RuntimeException error) {
                complete(activity, callback, error);
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
            final Activity activity,
            final Callback callback,
            final Throwable error) {
        if (callback != null) {
            activity.runOnUiThread(() -> {
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    callback.onComplete(error);
                }
            });
        }
    }
}

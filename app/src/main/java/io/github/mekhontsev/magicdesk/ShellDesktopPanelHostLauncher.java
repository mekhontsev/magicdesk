package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;

import java.util.ArrayList;
import java.util.List;

/** Prepares the transient application task that owns desktop panel windows. */
final class ShellDesktopPanelHostLauncher {
    private final Object mService;

    ShellDesktopPanelHostLauncher(final Object service) {
        mService = service;
    }

    int launch(final int displayId) throws ReflectiveOperationException {
        if (displayId < 0) {
            throw new IllegalArgumentException(
                    "desktop panels require a display");
        }
        removeStaleTasks(findPanelTasks(displayId));
        final int taskId = TaskDisplayAreaLaunchCommand.launchFullscreenTask(
                mService,
                displayId,
                DesktopPanelActivity.createIntent(displayId),
                BuildConfig.APPLICATION_ID,
                null);
        final Object task = HiddenTaskApi.requireTask(
                mService, displayId, taskId);
        if (!DesktopPanelActivity.isPanelComponent(
                HiddenTaskApi.getTaskComponent(task))) {
            TaskControlCommand.removeTask(mService, taskId);
            throw new IllegalStateException(
                    "desktop panel host resolved to the wrong activity");
        }
        TaskFullscreenTransitionCommand.awaitFullscreen(
                mService, displayId, taskId);
        return taskId;
    }

    private List<Integer> findPanelTasks(final int displayId)
            throws ReflectiveOperationException {
        final List<Integer> taskIds = new ArrayList<>();
        for (final Object task : HiddenTaskApi.getTasks(mService, displayId)) {
            final ComponentName top = HiddenTaskApi.getTaskTopActivity(task);
            final ComponentName base = HiddenTaskApi.getTaskBaseActivity(task);
            if (DesktopPanelActivity.isPanelComponent(top)
                    || DesktopPanelActivity.isPanelComponent(base)) {
                taskIds.add(Integer.valueOf(HiddenTaskApi.getTaskId(task)));
            }
        }
        return taskIds;
    }

    private void removeStaleTasks(final List<Integer> taskIds)
            throws ReflectiveOperationException {
        for (final Integer taskId : taskIds) {
            if (!TaskControlCommand.removeTask(
                    mService, taskId.intValue())) {
                throw new IllegalStateException(
                        "cannot remove stale desktop panel task=" + taskId);
            }
        }
    }
}

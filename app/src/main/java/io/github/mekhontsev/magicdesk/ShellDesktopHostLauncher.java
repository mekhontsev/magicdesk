package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Intent;

import java.util.ArrayList;
import java.util.List;

/** Launches the MagicDesk HOME host directly in a display root workspace. */
final class ShellDesktopHostLauncher {
    private static final String HOST_PACKAGE = BuildConfig.APPLICATION_ID;
    private static final String HOST_CLASS = HOST_PACKAGE + ".DesktopActivity";
    private static final int ACTIVITY_TYPE_HOME = 2;

    private final Object mService;
    private final ShellDesktopTaskOwnership mOwnership;

    ShellDesktopHostLauncher(
            final Object service,
            final ShellDesktopTaskOwnership ownership) {
        mService = service;
        mOwnership = ownership;
    }

    int launch(
            final int displayId,
            final String intentUri)
            throws ReflectiveOperationException {
        if (displayId < 0) {
            throw new IllegalArgumentException(
                    "desktop HOME requires a root workspace");
        }
        final Intent intent = TaskDisplayAreaLaunchCommand.createAppIntent(
                intentUri);
        if (!isDesktopHostComponent(intent.getComponent())) {
            throw new IllegalArgumentException(
                    "invalid desktop host component");
        }
        removeStaleHostTasks(findDesktopHostTaskIds(displayId));
        final int taskId = TaskDisplayAreaLaunchCommand.launchFullscreenTask(
                mService,
                displayId,
                intent,
                HOST_PACKAGE,
                null,
                ACTIVITY_TYPE_HOME);
        final Object task = HiddenTaskApi.requireTask(
                mService, displayId, taskId);
        if (HiddenTaskApi.getTaskActivityType(task) != ACTIVITY_TYPE_HOME) {
            TaskControlCommand.removeTask(mService, taskId);
            throw new IllegalStateException(
                    "desktop host did not become a HOME task");
        }
        mOwnership.markDesktopHost(taskId);
        return taskId;
    }

    private List<Integer> findDesktopHostTaskIds(final int displayId)
            throws ReflectiveOperationException {
        final List<Integer> taskIds = new ArrayList<>();
        for (final Object task : HiddenTaskApi.getTasks(mService, displayId)) {
            if (isDesktopHostComponent(
                    HiddenTaskApi.getTaskTopActivity(task))
                    || isDesktopHostComponent(
                            HiddenTaskApi.getTaskBaseActivity(task))) {
                taskIds.add(Integer.valueOf(HiddenTaskApi.getTaskId(task)));
            }
        }
        return taskIds;
    }

    private void removeStaleHostTasks(final List<Integer> taskIds)
            throws ReflectiveOperationException {
        for (final Integer taskId : taskIds) {
            if (!TaskControlCommand.removeTask(
                    mService, taskId.intValue())) {
                throw new IllegalStateException(
                        "cannot remove stale desktop host task=" + taskId);
            }
        }
    }

    private static boolean isDesktopHostComponent(
            final ComponentName component) {
        return component != null
                && HOST_PACKAGE.equals(component.getPackageName())
                && HOST_CLASS.equals(component.getClassName());
    }
}

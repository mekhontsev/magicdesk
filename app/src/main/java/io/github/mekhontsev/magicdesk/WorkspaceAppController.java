package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;

import java.io.IOException;
import java.util.Collections;

final class WorkspaceAppController {
    private final DesktopShellActivity mActivity;
    private final DesktopContentStore mContent;
    private boolean mRestoreAttempted;

    WorkspaceAppController(
            final DesktopShellActivity activity,
            final DesktopContentStore content) {
        mActivity = activity;
        mContent = content;
    }

    void resetProfileState() {
        mRestoreAttempted = false;
    }

    void syncSnapshot(final TaskRepository.Snapshot snapshot) {
        if (mRestoreAttempted) {
            return;
        }
        mRestoreAttempted = true;
        restore(snapshot, false);
    }

    String getWorkspacePackage() {
        final AppLaunchTarget target = mContent.workspaceTarget();
        return target == null ? null : target.packageName;
    }

    boolean isWorkspaceApp(final String packageName) {
        final String workspacePackage = getWorkspacePackage();
        return packageName != null && packageName.equals(workspacePackage);
    }

    void setWorkspaceApp(
            final AppItem app,
            final TaskRepository.TaskEntry task,
            final boolean keep) {
        if (!keep) {
            if (!mContent.setWorkspaceTarget(null)) {
                return;
            }
            mActivity.renderDesktopIcons(mActivity.getLauncherApps());
            mActivity.renderTaskbarPins(mActivity.getLauncherApps());
            mActivity.setStatus(mActivity.getString(
                    R.string.status_workspace_app_removed,
                    app.label));
            return;
        }

        if (!mContent.setWorkspaceTarget(app.launchTarget)) {
            return;
        }
        rememberBounds(task);
        mActivity.renderDesktopIcons(mActivity.getLauncherApps());
        mActivity.renderTaskbarPins(mActivity.getLauncherApps());
        mActivity.setStatus(mActivity.getString(
                R.string.status_workspace_app_kept,
                app.label));
        if (task == null || !task.isFreeform()) {
            mActivity.launchFloating(app);
        }
    }

    void restore(
            final TaskRepository.Snapshot snapshot,
            final boolean bringToFront) {
        final AppLaunchTarget target = mContent.workspaceTarget();
        if (target == null) {
            return;
        }
        final AppItem app = mActivity.findOrLoadApp(
                mActivity.getLauncherApps(), target);
        if (app == null) {
            return;
        }
        final TaskRepository.TaskEntry task = findTask(snapshot);
        if (task == null || !task.isFreeform()) {
            mActivity.launchFloating(app);
            return;
        }

        final Rect desiredBounds = resolveBounds(app.packageName);
        if (desiredBounds != null && !desiredBounds.equals(task.bounds)) {
            TaskRepository.resizeTaskBounds(
                    task,
                    desiredBounds,
                    result -> mActivity.runOnUiThread(() -> {
                        if (bringToFront) {
                            mActivity.focusTask(app, task);
                        }
                        mActivity.refreshTaskSnapshot();
                    }));
            return;
        }
        rememberBounds(task);
        if (bringToFront && !task.visible) {
            mActivity.focusTask(app, task);
        }
    }

    private TaskRepository.TaskEntry findTask(
            final TaskRepository.Snapshot snapshot) {
        final String workspacePackage = getWorkspacePackage();
        if (workspacePackage == null || snapshot == null) {
            return null;
        }
        for (final TaskRepository.TaskEntry task : snapshot.tasks) {
            if (mActivity.isTaskbarTask(task)
                    && workspacePackage.equals(task.packageName)) {
                return task;
            }
        }
        return null;
    }

    private Rect resolveBounds(final String packageName) {
        final AppWindowState state = AppWindowStateStore.load(packageName);
        if (state == null || state.windowBounds == null) {
            return null;
        }
        try {
            return state.windowBounds.resolve(
                    FloatingWindowController.getWorkAreaBounds(
                            mActivity.getCurrentDisplayId()));
        } catch (IOException error) {
            return null;
        }
    }

    private void rememberBounds(final TaskRepository.TaskEntry task) {
        if (task == null || !task.isBoundedFreeform()) {
            return;
        }
        try {
            final RelativeWindowBounds bounds = RelativeWindowBounds.from(
                    task.bounds,
                    FloatingWindowController.getWorkAreaBounds(
                            mActivity.getCurrentDisplayId()));
            if (bounds != null) {
                AppWindowStateStore.rememberWindowBounds(
                        Collections.singletonMap(task.packageName, bounds));
            }
        } catch (IOException ignored) {
            // The runtime task observer will retry once work-area data settles.
        }
    }
}

package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;

final class WorkspaceAppController {
    private final DesktopShellActivity mActivity;
    private final DesktopContentStore mContent;
    private boolean mRestoreAttempted;
    private boolean mBoundsRestorePending;

    WorkspaceAppController(
            final DesktopShellActivity activity,
            final DesktopContentStore content) {
        mActivity = activity;
        mContent = content;
    }

    void resetProfileState() {
        mRestoreAttempted = false;
        mBoundsRestorePending = false;
    }

    void syncSnapshot(final TaskRepository.Snapshot snapshot) {
        if (!mRestoreAttempted) {
            mRestoreAttempted = true;
            restore(snapshot, false);
            return;
        }
        updateBounds(snapshot);
    }

    String getWorkspacePackage() {
        final AppLaunchTarget target = mContent.get().workspaceTarget;
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
        final DisplayProfileStore.Profile profile =
                mActivity.getDisplayProfile();
        if (!keep) {
            mContent.get().workspaceTarget = null;
            mContent.save();
            profile.workspaceBounds.setEmpty();
            profile.workspaceBoundsTarget = null;
            mBoundsRestorePending = false;
            mActivity.saveDisplayProfile();
            mActivity.renderDesktopIcons(mActivity.getLauncherApps());
            mActivity.renderTaskbarPins(mActivity.getLauncherApps());
            mActivity.setStatus(mActivity.getString(
                    R.string.status_workspace_app_removed,
                    app.label));
            return;
        }

        mContent.get().workspaceTarget = app.launchTarget;
        mContent.save();
        profile.workspaceBounds.setEmpty();
        profile.workspaceBoundsTarget = app.launchTarget.stableKey();
        if (task != null
                && task.isFreeform()
                && !task.bounds.isEmpty()) {
            profile.workspaceBounds.set(task.bounds);
        }
        mActivity.saveDisplayProfile();
        mActivity.renderDesktopIcons(mActivity.getLauncherApps());
        mActivity.renderTaskbarPins(mActivity.getLauncherApps());
        mActivity.setStatus(mActivity.getString(
                R.string.status_workspace_app_kept,
                app.label));
        if (task == null || !task.isFreeform()) {
            mBoundsRestorePending =
                    !profile.workspaceBounds.isEmpty();
            mActivity.launchFloating(app);
        }
    }

    void restore(
            final TaskRepository.Snapshot snapshot,
            final boolean bringToFront) {
        final DisplayProfileStore.Profile profile =
                mActivity.getDisplayProfile();
        final AppLaunchTarget target = mContent.get().workspaceTarget;
        if (target == null) {
            return;
        }
        ensureBoundsBelongToTarget(profile, target);
        final AppItem app = mActivity.findOrLoadApp(
                mActivity.getLauncherApps(),
                target);
        if (app == null) {
            return;
        }
        final TaskRepository.TaskEntry task =
                findTask(snapshot);
        if (task == null || !task.isFreeform()) {
            mBoundsRestorePending =
                    !profile.workspaceBounds.isEmpty();
            mActivity.launchFloating(app);
            return;
        }
        if (!profile.workspaceBounds.isEmpty()
                && !profile.workspaceBounds.equals(task.bounds)) {
            final Rect bounds = new Rect(profile.workspaceBounds);
            TaskRepository.resizeTaskBounds(
                    task,
                    bounds,
                    result -> mActivity.runOnUiThread(() -> {
                        if (bringToFront) {
                            mActivity.focusTask(app, task);
                        }
                        mActivity.refreshTaskSnapshot();
                    }));
            return;
        }
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

    private void updateBounds(
            final TaskRepository.Snapshot snapshot) {
        final DisplayProfileStore.Profile profile =
                mActivity.getDisplayProfile();
        final AppLaunchTarget target = mContent.get().workspaceTarget;
        if (target == null) {
            return;
        }
        ensureBoundsBelongToTarget(profile, target);
        final TaskRepository.TaskEntry task = findTask(snapshot);
        if (task == null
                || !task.isFreeform()
                || task.bounds.isEmpty()) {
            return;
        }
        if (mBoundsRestorePending
                && !profile.workspaceBounds.isEmpty()) {
            final Rect desiredBounds =
                    new Rect(profile.workspaceBounds);
            mBoundsRestorePending = false;
            if (!desiredBounds.equals(task.bounds)) {
                TaskRepository.resizeTaskBounds(
                        task,
                        desiredBounds,
                        result -> mActivity.runOnUiThread(
                                mActivity::refreshTaskSnapshot));
                return;
            }
        }
        if (!profile.workspaceBounds.equals(task.bounds)) {
            profile.workspaceBounds.set(task.bounds);
            mActivity.saveDisplayProfile();
        }
    }

    private void ensureBoundsBelongToTarget(
            final DisplayProfileStore.Profile profile,
            final AppLaunchTarget target) {
        final String targetKey = target.stableKey();
        if (targetKey.equals(profile.workspaceBoundsTarget)) {
            return;
        }
        profile.workspaceBounds.setEmpty();
        profile.workspaceBoundsTarget = targetKey;
        mBoundsRestorePending = false;
        mActivity.saveDisplayProfile();
    }
}

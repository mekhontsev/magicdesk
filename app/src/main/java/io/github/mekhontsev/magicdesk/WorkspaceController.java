package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class WorkspaceController {
    private final MainActivity mActivity;
    private boolean mRestoreAttempted;
    private boolean mBoundsRestorePending;

    WorkspaceController(final MainActivity activity) {
        mActivity = activity;
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

    Set<String> getPinnedPackages() {
        return new LinkedHashSet<>(
                mActivity.getWorkspaceProfile().taskbarPackages);
    }

    void togglePinned(final AppItem app) {
        final Set<String> pinned = getPinnedPackages();
        final boolean nowPinned;
        if (pinned.contains(app.packageName)) {
            pinned.remove(app.packageName);
            nowPinned = false;
        } else {
            pinned.add(app.packageName);
            nowPinned = true;
        }
        final WorkspaceProfileStore.Profile profile =
                mActivity.getWorkspaceProfile();
        profile.taskbarPackages.clear();
        profile.taskbarPackages.addAll(pinned);
        mActivity.saveWorkspaceProfile();
        DesktopPreferences.saveLegacyPinnedPackages(mActivity, pinned);
        mActivity.renderTaskbarPins(mActivity.getLauncherApps());
        mActivity.renderStartMenuContent();
        mActivity.setStatus(mActivity.getString(
                nowPinned
                        ? R.string.status_app_pinned
                        : R.string.status_app_unpinned,
                app.label));
    }

    boolean isDesktopShortcut(final String packageName) {
        return mActivity.getWorkspaceProfile()
                .desktopPackages.contains(packageName);
    }

    void toggleDesktopShortcut(final AppItem app) {
        final List<String> shortcuts =
                mActivity.getWorkspaceProfile().desktopPackages;
        final boolean added;
        if (shortcuts.remove(app.packageName)) {
            added = false;
        } else {
            shortcuts.add(app.packageName);
            added = true;
        }
        mActivity.saveWorkspaceProfile();
        mActivity.renderDesktopIcons(mActivity.getLauncherApps());
        mActivity.setStatus(mActivity.getString(
                added
                        ? R.string.status_desktop_shortcut_added
                        : R.string.status_desktop_shortcut_removed,
                app.label));
    }

    void setWorkspaceApp(
            final AppItem app,
            final TaskRepository.TaskEntry task,
            final boolean keep) {
        final WorkspaceProfileStore.Profile profile =
                mActivity.getWorkspaceProfile();
        if (!keep) {
            profile.workspacePackage = null;
            profile.workspaceBounds.setEmpty();
            mBoundsRestorePending = false;
            mActivity.saveWorkspaceProfile();
            mActivity.renderDesktopIcons(mActivity.getLauncherApps());
            mActivity.renderTaskbarPins(mActivity.getLauncherApps());
            mActivity.setStatus(mActivity.getString(
                    R.string.status_workspace_app_removed,
                    app.label));
            return;
        }

        profile.workspacePackage = app.packageName;
        profile.workspaceBounds.setEmpty();
        if (task != null
                && task.isFreeform()
                && !task.bounds.isEmpty()) {
            profile.workspaceBounds.set(task.bounds);
        }
        mActivity.saveWorkspaceProfile();
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
        final WorkspaceProfileStore.Profile profile =
                mActivity.getWorkspaceProfile();
        if (profile.workspacePackage == null
                || profile.workspacePackage.length() == 0) {
            return;
        }
        final AppItem app = mActivity.findOrLoadApp(
                mActivity.getLauncherApps(),
                profile.workspacePackage);
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
        final String workspacePackage =
                mActivity.getWorkspaceProfile().workspacePackage;
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
        final WorkspaceProfileStore.Profile profile =
                mActivity.getWorkspaceProfile();
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
            mActivity.saveWorkspaceProfile();
        }
    }
}

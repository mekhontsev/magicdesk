package io.github.mekhontsev.magicdesk;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class AppTaskController {
    private static final String TAG = "MagicDesk";

    private interface PreparedTaskAction {
        void run(int displayId, int taskId) throws IOException;
    }

    private interface TaskFocusCompletion {
        void run(boolean success);
    }

    private final DesktopShellActivity mActivity;
    private List<TaskRepository.TaskEntry> mInteractionVisibleTasks =
            Collections.emptyList();

    AppTaskController(final DesktopShellActivity activity) {
        mActivity = activity;
    }

    void clearInteractionStack() {
        mInteractionVisibleTasks = Collections.emptyList();
    }

    void captureInteractionStackForPanel() {
        if (!mActivity.hasVisiblePanel()) {
            mInteractionVisibleTasks = captureVisibleFreeformTasks();
        }
    }

    void launchDefault(final AppItem app) {
        launchDefault(app, null);
    }

    /** Runs {@code onPrepared} only after the target task is ready for use. */
    void launchDefault(final AppItem app, final Runnable onPrepared) {
        final AppWindowState saved = savedWindowState(app);
        Log.i(TAG, "launch default package=" + app.packageName
                + " canFloat=" + app.canFloat
                + " fullscreenReason=" + app.fullscreenReason
                + " display=" + mActivity.getCurrentDisplayId());
        if (saved != null
                && saved.shouldLaunchWindowed()
                && canControlWindowing()) {
            launchFloating(
                    app,
                    true,
                    saved.windowBounds,
                    WindowedAppLauncher.TaskReusePolicy.REUSE_EXISTING,
                    onPrepared);
        } else if (saved != null
                && saved.mode == AppWindowState.Mode.FULLSCREEN) {
            launchFullscreen(
                    app,
                    false,
                    app.label,
                    preparedTaskAction(onPrepared));
        } else if (canControlWindowing()
                && app.canFloat
                && AppItem.FULLSCREEN_REASON_NONE.equals(
                        app.fullscreenReason)) {
            launchFloating(
                    app,
                    false,
                    null,
                    WindowedAppLauncher.TaskReusePolicy.REUSE_EXISTING,
                    onPrepared);
        } else {
            launchFullscreen(
                    app,
                    false,
                    app.label,
                    preparedTaskAction(onPrepared));
        }
    }

    void launchForMode(
            final AppItem app,
            final DesktopLaunchMode mode,
            final Runnable onPrepared) {
        launchForMode(app, mode, null, onPrepared);
    }

    void launchForMode(
            final AppItem app,
            final DesktopLaunchMode mode,
            final RelativeWindowBounds preferredBounds,
            final Runnable onPrepared) {
        if (mode == DesktopLaunchMode.WINDOWED) {
            final AppWindowState saved = savedWindowState(app);
            launchFloating(
                    app,
                    true,
                    preferredBounds != null
                            ? preferredBounds
                            : saved == null ? null : saved.windowBounds,
                    WindowedAppLauncher.TaskReusePolicy.REUSE_EXISTING,
                    onPrepared);
        } else if (mode == DesktopLaunchMode.FULLSCREEN) {
            launchFullscreen(
                    app,
                    true,
                    app.label,
                    preparedTaskAction(onPrepared));
        } else {
            launchDefault(app, onPrepared);
        }
    }

    void launchShortcut(
            final AppItem app,
            final AppShortcutAction shortcut) {
        if (app == null || shortcut == null) {
            return;
        }
        final AppWindowState saved = savedWindowState(app);
        if (saved != null
                && saved.shouldLaunchWindowed()
                && canControlWindowing()) {
            launchShortcutWindowed(
                    app, shortcut, true, saved.windowBounds);
        } else if (saved != null
                && saved.mode == AppWindowState.Mode.FULLSCREEN) {
            launchShortcutFullscreen(app, shortcut);
        } else if (canControlWindowing()
                && app.canFloat
                && AppItem.FULLSCREEN_REASON_NONE.equals(
                        app.fullscreenReason)) {
            launchShortcutWindowed(app, shortcut, false, null);
        } else {
            launchShortcutFullscreen(app, shortcut);
        }
    }

    void launchDesktopShortcut(
            final AppItem app,
            final DesktopApplicationShortcut shortcut) {
        if (app == null || shortcut == null) {
            return;
        }
        if (shortcut.defaultLaunch) {
            if (shortcut.launchMode == DesktopLaunchMode.WINDOWED) {
                launchWindowed(app);
            } else if (shortcut.launchMode == DesktopLaunchMode.FULLSCREEN) {
                launchFullscreen(app);
            } else {
                mActivity.launchDefault(app);
            }
            return;
        }
        final Intent intent = shortcut.resolveIntent(
                mActivity.getPackageManager());
        if (intent == null) {
            mActivity.setErrorStatus(
                    "APP-LAUNCH-003",
                    mActivity.getString(
                            R.string.status_launch_failed,
                            shortcut.name),
                    "invalid desktop entry Intent",
                    null);
            return;
        }
        final AppShortcutAction action = new AppShortcutAction(
                "desktop:" + Integer.toHexString(
                        shortcut.intentUri.hashCode()),
                shortcut.name,
                app.icon,
                intent);
        if (shortcut.launchMode == DesktopLaunchMode.WINDOWED) {
            final AppWindowState saved = savedWindowState(app);
            launchShortcutWindowed(
                    app,
                    action,
                    true,
                    saved == null ? null : saved.windowBounds);
        } else if (shortcut.launchMode == DesktopLaunchMode.FULLSCREEN) {
            launchShortcutFullscreen(app, action);
        } else {
            launchShortcut(app, action);
        }
    }

    private void launchShortcutWindowed(
            final AppItem app,
            final AppShortcutAction shortcut,
            final boolean explicitWindowed,
            final RelativeWindowBounds preferredBounds) {
        if (!canControlWindowing()) {
            launchShortcutFullscreen(app, shortcut);
            return;
        }
        Log.i(TAG, "launch app shortcut package=" + app.packageName
                + " shortcut=" + shortcut.id
                + " display=" + mActivity.getCurrentDisplayId());
        final Intent appIntent = app.launchTarget.resolve(
                mActivity.getPackageManager());
        if (appIntent == null) {
            showMissingLauncher(app);
            return;
        }
        launchWindow(
                appIntent,
                app.launchTarget,
                shortcut.label,
                explicitWindowed,
                preferredBounds,
                WindowedAppLauncher.TaskReusePolicy.REUSE_EXISTING,
                (displayId, taskId) -> MagicDeskRuntime.launchTaskAction(
                        displayId,
                        taskId,
                        shortcut.launchIntent()));
    }

    private void launchShortcutFullscreen(
            final AppItem app,
            final AppShortcutAction shortcut) {
        Log.i(TAG, "launch fullscreen app shortcut package="
                + app.packageName
                + " shortcut=" + shortcut.id
                + " display=" + mActivity.getCurrentDisplayId());
        launchFullscreen(
                app,
                false,
                shortcut.label,
                (displayId, taskId) -> MagicDeskRuntime.launchTaskAction(
                        displayId,
                        taskId,
                        shortcut.launchIntent()));
    }

    void launchFloating(final AppItem app) {
        launchFloating(app, false);
    }

    void launchWindowed(final AppItem app) {
        launchFloating(app, true);
    }

    void launchNewWindow(final AppItem app) {
        if (!BuiltInDesktopAppCatalog.supportsMultipleWindows(
                app.launchTarget)) {
            launchFloating(app, true);
            return;
        }
        launchFloating(
                app,
                true,
                null,
                WindowedAppLauncher.TaskReusePolicy.CREATE_NEW);
    }

    void openAppInfo(final AppItem app) {
        if (app == null) {
            return;
        }
        final Intent intent = new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", app.packageName, null));
        final ResolveInfo resolved = mActivity.getPackageManager()
                .resolveActivity(intent, 0);
        if (resolved == null || resolved.activityInfo == null) {
            mActivity.setErrorStatus(
                    "APP-INFO-001",
                    mActivity.getString(
                            R.string.status_launch_failed,
                            mActivity.getString(R.string.action_app_info)));
            return;
        }
        final ComponentName component = new ComponentName(
                resolved.activityInfo.packageName,
                resolved.activityInfo.name);
        intent.setComponent(component);
        final AppLaunchTarget target = AppLaunchTarget.explicit(
                component.getPackageName(),
                component.getClassName(),
                intent.getAction());
        if (!canControlWindowing()) {
            final ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(mActivity.getCurrentDisplayId());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mActivity.startActivity(intent, options.toBundle());
            return;
        }
        launchWindow(
                intent,
                target,
                mActivity.getString(R.string.action_app_info),
                true,
                null,
                WindowedAppLauncher.TaskReusePolicy.CREATE_NEW,
                null);
    }

    void launchInternalWindow(
            final Intent launchIntent,
            final AppLaunchTarget launchTarget,
            final String label) {
        final int displayId = mActivity.getCurrentDisplayId();
        final boolean multipleWindows =
                BuiltInDesktopAppCatalog.supportsMultipleWindows(launchTarget);
        final AppWindowState saved =
                BuiltInDesktopAppCatalog.remembersWindowState(launchTarget)
                        ? AppWindowStateStore.load(
                                BuiltInDesktopAppCatalog.appIdentityKey(
                                        launchTarget))
                        : null;
        if (!canControlWindowing()) {
            final ActivityOptions options = ActivityOptions.makeBasic();
            options.setLaunchDisplayId(displayId);
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (multipleWindows) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                        | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            } else {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            }
            mActivity.startActivity(launchIntent, options.toBundle());
            return;
        }

        launchWindow(
                launchIntent,
                launchTarget,
                label,
                true,
                saved != null && saved.windowBounds != null
                        ? saved.windowBounds
                        : BuiltInDesktopAppCatalog.defaultWindowBounds(
                                launchTarget),
                multipleWindows
                        ? WindowedAppLauncher.TaskReusePolicy.CREATE_NEW
                        : WindowedAppLauncher.TaskReusePolicy.REUSE_EXISTING,
                null);
    }

    private void launchFloating(
            final AppItem app,
            final boolean explicitWindowed) {
        final AppWindowState saved = savedWindowState(app);
        launchFloating(
                app,
                explicitWindowed,
                saved == null ? null : saved.windowBounds,
                WindowedAppLauncher.TaskReusePolicy.REUSE_EXISTING);
    }

    private void launchFloating(
            final AppItem app,
            final boolean explicitWindowed,
            final RelativeWindowBounds preferredBounds,
            final WindowedAppLauncher.TaskReusePolicy reusePolicy) {
        launchFloating(
                app,
                explicitWindowed,
                preferredBounds,
                reusePolicy,
                null);
    }

    private void launchFloating(
            final AppItem app,
            final boolean explicitWindowed,
            final RelativeWindowBounds preferredBounds,
            final WindowedAppLauncher.TaskReusePolicy reusePolicy,
            final Runnable onPrepared) {
        if (!canControlWindowing()) {
            launchFullscreen(
                    app,
                    false,
                    app.label,
                    preparedTaskAction(onPrepared));
            return;
        }
        final AppWindowStateStore.PendingModeUpdate modeUpdate =
                explicitWindowed && remembersWindowState(app)
                        ? AppWindowStateStore.beginModeUpdate(
                                windowStateKey(app),
                                AppWindowState.Mode.WINDOWED)
                        : null;
        final TaskRepository.TaskEntry existingTask =
                mActivity.findFirstTask(app.launchTarget);
        if (reusePolicy == WindowedAppLauncher.TaskReusePolicy.REUSE_EXISTING
                && existingTask != null
                && existingTask.displayId == mActivity.getCurrentDisplayId()
                && existingTask.isFreeform()) {
            focusTaskWithResult(
                    app,
                    existingTask,
                    success -> {
                        if (success) {
                            TaskCommandQueue.execute(() ->
                                    AppWindowStateStore.commitModeUpdate(
                                            modeUpdate));
                            runIfPresent(onPrepared);
                        } else {
                            AppWindowStateStore.cancelModeUpdate(modeUpdate);
                        }
                    });
            return;
        }
        final Intent launchIntent = app.launchTarget.resolve(
                mActivity.getPackageManager());
        if (launchIntent == null) {
            AppWindowStateStore.cancelModeUpdate(modeUpdate);
            showMissingLauncher(app);
            return;
        }
        Log.i(TAG, "launch floating package=" + app.packageName
                + " display=" + mActivity.getCurrentDisplayId()
                + " explicitWindowed=" + explicitWindowed);
        launchWindow(
                launchIntent,
                app.launchTarget,
                app.label,
                explicitWindowed,
                preferredBounds,
                reusePolicy,
                (displayId, taskId) -> {
                    AppWindowStateStore.commitModeUpdate(modeUpdate);
                    runIfPresent(onPrepared);
                },
                () -> AppWindowStateStore.cancelModeUpdate(modeUpdate));
    }

    private static PreparedTaskAction preparedTaskAction(
            final Runnable onPrepared) {
        if (onPrepared == null) {
            return null;
        }
        return (displayId, taskId) -> onPrepared.run();
    }

    private void launchWindow(
            final Intent launchIntent,
            final AppLaunchTarget launchTarget,
            final String label,
            final boolean explicitWindowed,
            final RelativeWindowBounds preferredBounds,
            final WindowedAppLauncher.TaskReusePolicy reusePolicy,
            final PreparedTaskAction afterLaunch) {
        launchWindow(
                launchIntent,
                launchTarget,
                label,
                explicitWindowed,
                preferredBounds,
                reusePolicy,
                afterLaunch,
                null);
    }

    private void launchWindow(
            final Intent launchIntent,
            final AppLaunchTarget launchTarget,
            final String label,
            final boolean explicitWindowed,
            final RelativeWindowBounds preferredBounds,
            final WindowedAppLauncher.TaskReusePolicy reusePolicy,
            final PreparedTaskAction afterLaunch,
            final Runnable onFailure) {
        mActivity.setTaskbarVisible(true);
        mActivity.setStatus(mActivity.getString(
                R.string.status_launching_window, label));
        final List<TaskRepository.TaskEntry> visibleTasks =
                takeInteractionVisibleTasks();
        final int displayId = mActivity.getCurrentDisplayId();
        TaskCommandQueue.execute(() -> {
            try {
                final int taskId = WindowedAppLauncher.launch(
                        launchIntent,
                        launchTarget,
                        displayId,
                        getTaskIds(visibleTasks),
                        explicitWindowed,
                        preferredBounds,
                        reusePolicy,
                        () -> publishConfirmedLaunchSnapshot(displayId));
                if (afterLaunch != null) {
                    afterLaunch.run(displayId, taskId);
                }
                mActivity.runOnUiThread(() -> {
                    if (mActivity.isActivityUnavailable()) {
                        return;
                    }
                    mActivity.setStatus(mActivity.getString(
                            R.string.status_switch_done, label));
                    mActivity.refreshTaskSnapshot();
                });
            } catch (IOException | RuntimeException error) {
                runIfPresent(onFailure);
                MagicDeskRuntime.focusStack(
                        visibleTasks, null, null);
                mActivity.runOnUiThread(() -> {
                    if (!mActivity.isActivityUnavailable()) {
                        mActivity.showLaunchFailure(error);
                    }
                });
            }
        });
    }

    private void publishConfirmedLaunchSnapshot(final int displayId) {
        final TaskRepository.Snapshot snapshot =
                TaskRepository.loadNow(displayId);
        if (!snapshot.available) {
            return;
        }
        mActivity.runOnUiThread(() -> {
            if (!mActivity.isActivityUnavailable()
                    && displayId == mActivity.getCurrentDisplayId()) {
                mActivity.syncTaskbarWithSnapshot(snapshot);
            }
        });
    }

    void launchFullscreen(final AppItem app) {
        launchFullscreen(app, true);
    }

    private void launchFullscreen(
            final AppItem app,
            final boolean rememberMode) {
        launchFullscreen(
                app,
                rememberMode,
                app.label,
                null);
    }

    private void launchFullscreen(
            final AppItem app,
            final boolean rememberMode,
            final String label,
            final PreparedTaskAction afterLaunch) {
        Log.i(TAG, "launch fullscreen package=" + app.packageName
                + " display=" + mActivity.getCurrentDisplayId());
        final List<TaskRepository.TaskEntry> visibleTasks =
                takeInteractionVisibleTasks();
        final int excludedTaskId = findPackageTaskId(
                visibleTasks, app.packageName);
        final int displayId = mActivity.getCurrentDisplayId();
        final AppWindowStateStore.PendingModeUpdate modeUpdate =
                rememberMode && remembersWindowState(app)
                        ? AppWindowStateStore.beginModeUpdate(
                                windowStateKey(app),
                                AppWindowState.Mode.FULLSCREEN)
                        : null;
        mActivity.setTaskbarVisible(
                DesktopTaskSnapshotController.hasVisibleFreeformTask(
                        visibleTasks, excludedTaskId));
        mActivity.setStatus(mActivity.getString(
                R.string.status_launching_fullscreen, label));
        TaskCommandQueue.execute(() -> {
            try {
                MagicDeskRuntime.beginFullscreenTransition(
                        displayId, visibleTasks, excludedTaskId);
                final int taskId = prepareFullscreenTask(app, displayId);
                if (!MagicDeskRuntime.protectExplicitFullscreenTask(
                        displayId, taskId)) {
                    Log.w(TAG, "fullscreen activity handoff guard unavailable"
                            + " package=" + app.packageName
                            + " task=" + taskId
                            + " display=" + displayId);
                }
                if (afterLaunch != null) {
                    afterLaunch.run(displayId, taskId);
                }
                if (modeUpdate != null) {
                    AppWindowStateStore.commitModeUpdate(modeUpdate);
                }
                MagicDeskRuntime.finishFullscreenTransition(
                        displayId, true);
            } catch (IOException | RuntimeException error) {
                AppWindowStateStore.cancelModeUpdate(modeUpdate);
                MagicDeskRuntime.finishFullscreenTransition(
                        displayId, false);
                reportFullscreenLaunchFailure(app, displayId, error);
            }
        });
    }

    private void reportFullscreenLaunchFailure(
            final AppItem app,
            final int displayId,
            final Exception error) {
        mActivity.runOnUiThread(() -> {
            if (mActivity.isActivityUnavailable()
                    || displayId != mActivity.getCurrentDisplayId()) {
                return;
            }
            mActivity.setTaskbarVisible(true);
            if (error instanceof IOException) {
                mActivity.setErrorStatus(
                        "TASK-FULLSCREEN-001",
                        mActivity.getString(
                                R.string.status_switch_failed,
                                error.getMessage()),
                        "package=" + app.packageName
                                + " display=" + displayId,
                        error);
            } else {
                mActivity.showLaunchFailure(error);
            }
        });
    }

    private int prepareFullscreenTask(
            final AppItem app,
            final int displayId) throws IOException {
        if (ShellAccess.isReady()) {
            final ExistingTaskController.ReuseResult reuseResult =
                    ExistingTaskController.reuseIfExists(
                            app.launchTarget, displayId, false);
            if (reuseResult.found) {
                MagicDeskRuntime.focusDesktopTask(
                        displayId, reuseResult.taskId, null);
                DesktopTaskLaunchDiagnostics.note(
                        reuseResult.taskId,
                        reuseResult.originalDisplayId,
                        displayId,
                        "desktop-fullscreen-reuse");
                Log.i(TAG, "reused fullscreen package=" + app.packageName);
                return reuseResult.taskId;
            }
        }

        Log.i(TAG, "fresh fullscreen launch package=" + app.packageName);
        final Intent launchIntent = app.launchTarget.resolve(
                mActivity.getPackageManager());
        if (launchIntent == null) {
            throw new IOException("no launcher activity");
        }
        launchIntent.addFlags(getFullscreenLaunchFlags());
        final int taskId;
        if (DesktopDisplayDrivers.activeTaskAreaPolicy(displayId)
                .usesManagedApplicationArea()) {
            taskId = MagicDeskRuntime.launchFullscreenTaskInManagedSession(
                    displayId, launchIntent);
        } else {
            taskId = MagicDeskRuntime.launchFullscreenTask(
                    displayId, launchIntent);
        }
        DesktopTaskLaunchDiagnostics.note(
                taskId,
                displayId,
                displayId,
                "desktop-fullscreen-new");
        return taskId;
    }

    private void showMissingLauncher(final AppItem app) {
        final List<TaskRepository.TaskEntry> visibleTasks =
                takeInteractionVisibleTasks();
        MagicDeskRuntime.focusStack(visibleTasks, null, null);
        mActivity.setErrorStatus(
                "APP-LAUNCH-002",
                mActivity.getString(
                        R.string.status_launch_failed,
                        "no launcher activity"),
                "package=" + app.packageName,
                null);
    }

    private static boolean canControlWindowing() {
        return ShellAccess.isReady();
    }

    void focusTask(
            final AppItem app,
            final TaskRepository.TaskEntry task) {
        focusTask(app, task, null);
    }

    void focusTask(
            final AppItem app,
            final TaskRepository.TaskEntry task,
            final Runnable completion) {
        focusTaskWithResult(
                app,
                task,
                completion == null
                        ? null
                        : success -> completion.run());
    }

    private void focusTaskOnSuccess(
            final AppItem app,
            final TaskRepository.TaskEntry task,
            final Runnable onPrepared) {
        focusTaskWithResult(
                app,
                task,
                success -> {
                    if (success) {
                        runIfPresent(onPrepared);
                    }
                });
    }

    private void focusTaskWithResult(
            final AppItem app,
            final TaskRepository.TaskEntry task,
            final TaskFocusCompletion completion) {
        mActivity.setStatus(mActivity.getString(
                R.string.status_switching_to, app.label));
        final List<TaskRepository.TaskEntry> visibleTasks =
                takeInteractionVisibleTasks();
        final int displayId = mActivity.getCurrentDisplayId();
        TaskRepository.load(displayId, snapshot ->
                mActivity.runOnUiThread(() -> {
                    if (mActivity.isActivityUnavailable()) {
                        return;
                    }
                    if (displayId != mActivity.getCurrentDisplayId()) {
                        runFocusCompletion(completion, false);
                        return;
                    }
                    if (!snapshot.available) {
                        mActivity.setStatus(mActivity.getString(
                                R.string.status_switch_failed,
                                snapshot.error.length() == 0
                                        ? app.label
                                        : snapshot.error));
                        runFocusCompletion(completion, false);
                        return;
                    }
                    mActivity.setTaskSnapshot(snapshot);
                    final TaskRepository.TaskEntry currentTask =
                            DesktopShellActivity.findTask(
                                    snapshot, task.taskId);
                    if (currentTask == null) {
                        mActivity.setStatus(mActivity.getString(
                                R.string.status_switch_failed,
                                app.label));
                        mActivity.refreshTaskSnapshot();
                        runFocusCompletion(completion, false);
                        return;
                    }
                    MagicDeskRuntime.focusStack(
                            visibleTasks,
                            currentTask,
                            result -> mActivity.runOnUiThread(() -> {
                                if (mActivity.isActivityUnavailable()) {
                                    return;
                                }
                                if (!result.success) {
                                    mActivity.setStatus(
                                            mActivity.getString(
                                                    R.string.status_switch_failed,
                                                    result.message.length() == 0
                                                            ? app.label
                                                            : result.message));
                                    runFocusCompletion(completion, false);
                                    return;
                                }
                                mActivity.setTaskbarVisible(
                                        currentTask.isFreeform()
                                                || DesktopTaskSnapshotController
                                                        .hasVisibleFreeformTask(
                                                                visibleTasks,
                                                                currentTask
                                                                        .taskId));
                                mActivity.refreshTaskSnapshot();
                                runFocusCompletion(completion, true);
                            }));
                }));
    }

    private static void runFocusCompletion(
            final TaskFocusCompletion completion,
            final boolean success) {
        if (completion != null) {
            completion.run(success);
        }
    }

    private static void runIfPresent(final Runnable action) {
        if (action != null) {
            action.run();
        }
    }

    void toggleTaskbarTask(
            final AppItem app,
            final TaskRepository.TaskEntry task) {
        if (task == null) {
            return;
        }
        final int displayId = mActivity.getCurrentDisplayId();
        MagicDeskRuntime.toggleTaskbarTask(
                displayId,
                task.taskId,
                result -> mActivity.runOnUiThread(() -> {
                    if (mActivity.isActivityUnavailable()) {
                        return;
                    }
                    if (!result.success) {
                        showTaskbarActionFailure(app, result.message);
                    }
                    mActivity.refreshTaskSnapshot();
                }));
    }

    private void showTaskbarActionFailure(
            final AppItem app,
            final String detail) {
        mActivity.setStatus(mActivity.getString(
                R.string.status_switch_failed,
                detail == null || detail.length() == 0
                        ? app.label : detail));
    }

    void openTaskFullscreen(
            final AppItem app,
            final TaskRepository.TaskEntry task) {
        final boolean keepTaskbarVisible =
                DesktopTaskSnapshotController.hasVisibleFreeformTask(
                        takeInteractionVisibleTasks(), task.taskId);
        rememberWindowBounds(task);
        final int displayId =
                beginFullscreenTransition(task.taskId);
        mActivity.setStatus(mActivity.getString(
                R.string.status_launching_fullscreen, app.label));
        TaskRepository.setFullscreen(
                task,
                result -> {
                    MagicDeskRuntime.finishFullscreenTransition(
                            displayId, result.success);
                    mActivity.runOnUiThread(() -> {
                        if (mActivity.isActivityUnavailable()) {
                            return;
                        }
                        if (result.success) {
                            if (remembersWindowState(app)) {
                                AppWindowStateStore.rememberMode(
                                        windowStateKey(app),
                                        AppWindowState.Mode.FULLSCREEN);
                            }
                            mActivity.setTaskbarVisible(
                                    keepTaskbarVisible);
                        }
                        mActivity.setStatus(mActivity.getString(
                                result.success
                                        ? R.string.status_switch_done
                                        : R.string.status_switch_failed,
                                result.success
                                        ? app.label
                                        : (result.message.length() == 0
                                                ? app.label
                                                : result.message)));
                        mActivity.refreshTaskSnapshot();
                    });
                });
    }

    private void rememberWindowBounds(
            final TaskRepository.TaskEntry task) {
        if (task == null
                || !task.isBoundedFreeform()
                || !BuiltInDesktopAppCatalog.remembersWindowState(task)) {
            return;
        }
        try {
            final RelativeWindowBounds bounds = RelativeWindowBounds.from(
                    task.bounds,
                    FloatingWindowController.getWorkAreaBounds(
                            task.displayId));
            if (bounds != null) {
                AppWindowStateStore.rememberWindowBounds(
                        Collections.singletonMap(
                                BuiltInDesktopAppCatalog.appIdentityKey(task),
                                bounds));
            }
        } catch (IOException ignored) {
            // The runtime task observer will capture the bounds when available.
        }
    }

    int getOtherDisplayId(final TaskRepository.TaskEntry task) {
        if (task == null) {
            return -1;
        }
        if (task.displayId != Display.DEFAULT_DISPLAY) {
            return Display.DEFAULT_DISPLAY;
        }
        final int externalDisplayId =
                DesktopRuntimeBridge.getActiveDesktopDisplayId();
        return externalDisplayId > 0 ? externalDisplayId : -1;
    }

    void moveTaskToOtherDisplay(
            final AppItem app,
            final TaskRepository.TaskEntry task) {
        final int targetDisplayId = getOtherDisplayId(task);
        if (targetDisplayId < 0) {
            return;
        }
        rememberWindowBounds(task);
        mActivity.hideAllPanels();
        mActivity.setStatus(mActivity.getString(
                R.string.status_moving_to_display,
                app.label,
                Integer.valueOf(targetDisplayId)));
        TaskRepository.moveTaskToDisplay(
                task,
                targetDisplayId,
                savedWindowBounds(app),
                result -> mActivity.runOnUiThread(() -> {
                    if (mActivity.isActivityUnavailable()) {
                        return;
                    }
                    if (result.success) {
                        mActivity.setStatus(mActivity.getString(
                                R.string.status_moved_to_display,
                                app.label,
                                Integer.valueOf(targetDisplayId)));
                    } else {
                        mActivity.setErrorStatus(
                                "TASK-DISPLAY-001",
                                mActivity.getString(
                                        R.string.status_move_to_display_failed,
                                        result.message));
                    }
                    mActivity.refreshTaskSnapshot();
                    MagicDeskRuntime
                            .refreshDesktopTasks();
                }));
    }

    private static RelativeWindowBounds savedWindowBounds(
            final AppItem app) {
        final AppWindowState state = savedWindowState(app);
        return state == null ? null : state.windowBounds;
    }

    private static AppWindowState savedWindowState(final AppItem app) {
        return remembersWindowState(app)
                ? AppWindowStateStore.load(windowStateKey(app))
                : null;
    }

    private static String windowStateKey(final AppItem app) {
        return app == null
                ? null
                : BuiltInDesktopAppCatalog.appIdentityKey(app.launchTarget);
    }

    private static boolean remembersWindowState(final AppItem app) {
        return app != null
                && BuiltInDesktopAppCatalog.remembersWindowState(
                        app.launchTarget);
    }

    void closeTask(
            final AppItem app,
            final TaskRepository.TaskEntry task) {
        mActivity.hideAllPanels();
        mActivity.setStatus(mActivity.getString(
                R.string.status_closing_window, app.label));
        final TaskRepository.ActionCallback callback =
                result -> mActivity.runOnUiThread(() -> {
                    if (mActivity.isActivityUnavailable()) {
                        return;
                    }
                    mActivity.setStatus(mActivity.getString(
                            result.success
                                    ? R.string.status_window_closed
                                    : R.string.status_close_window_failed,
                            result.success
                                    ? app.label
                                    : result.message));
                    mActivity.refreshTaskSnapshot();
                });
        MagicDeskRuntime.closeTask(task, callback);
    }

    void forceStop(final AppItem app) {
        mActivity.hideAllPanels();
        mActivity.setStatus(mActivity.getString(
                R.string.status_force_stopping, app.label));
        MagicDeskRuntime.forceStopPackage(
                app.packageName,
                result -> mActivity.runOnUiThread(() -> {
                    if (mActivity.isActivityUnavailable()) {
                        return;
                    }
                    mActivity.setStatus(mActivity.getString(
                            result.success
                                    ? R.string.status_app_force_stopped
                                    : R.string.status_force_stop_failed,
                            result.success
                                    ? app.label
                                    : result.message));
                    mActivity.refreshTaskSnapshot();
                }));
    }

    void restoreLastVisibleWindows() {
        mActivity.hideAllPanels();
        mActivity.setTaskbarVisible(true);
        clearInteractionStack();
        final int displayId = mActivity.getCurrentDisplayId();
        final List<TaskRepository.TaskEntry> savedTasks =
                MagicDeskRuntime.getLastVisibleFreeformTasks(
                        displayId);
        if (savedTasks.isEmpty()) {
            restorePreparedSessionWorkspace(
                    displayId,
                    Collections.emptyList(),
                    new TaskRepository.ActionResult(
                            true, "no saved windows"));
            return;
        }
        mActivity.setStatus(R.string.status_restoring_windows);
        TaskRepository.restoreFreeformStack(
                displayId,
                savedTasks,
                result -> restorePreparedSessionWorkspace(
                        displayId, savedTasks, result));
    }

    private void restorePreparedSessionWorkspace(
            final int displayId,
            final List<TaskRepository.TaskEntry> savedTopFirstTasks,
            final TaskRepository.ActionResult preparationResult) {
        mActivity.runOnUiThread(() -> {
            if (mActivity.isActivityUnavailable()) {
                return;
            }
            if (!preparationResult.success) {
                mActivity.setStatus(mActivity.getString(
                        R.string.status_switch_failed,
                        preparationResult.message.length() == 0
                                ? mActivity.getString(
                                        R.string.status_restoring_windows)
                                : preparationResult.message));
                mActivity.refreshTaskSnapshot();
                return;
            }
            final java.util.LinkedHashSet<Integer> order =
                    new java.util.LinkedHashSet<>();
            order.add(Integer.valueOf(mActivity.getTaskId()));
            for (int index = savedTopFirstTasks.size() - 1;
                    index >= 0; index--) {
                final TaskRepository.TaskEntry task =
                        savedTopFirstTasks.get(index);
                if (task != null && task.displayId == displayId) {
                    order.add(Integer.valueOf(task.taskId));
                }
            }
            MagicDeskRuntime.restoreDesktopWorkspace(
                    displayId,
                    new ArrayList<>(order),
                    result -> mActivity.runOnUiThread(() -> {
                        if (mActivity.isActivityUnavailable()) {
                            return;
                        }
                        mActivity.setStatus(result.success
                                ? mActivity.getString(
                                        savedTopFirstTasks.isEmpty()
                                                ? R.string.status_desktop_visible
                                                : R.string.status_windows_restored)
                                : mActivity.getString(
                                        R.string.status_switch_failed,
                                        result.message));
                        mActivity.refreshTaskSnapshot();
                    }));
        });
    }

    private int beginFullscreenTransition(final int excludedTaskId) {
        final List<TaskRepository.TaskEntry> visibleTasks =
                takeInteractionVisibleTasks();
        final int displayId = mActivity.getCurrentDisplayId();
        MagicDeskRuntime.beginFullscreenTransition(
                displayId, visibleTasks, excludedTaskId);
        return displayId;
    }

    private static int findPackageTaskId(
            final List<TaskRepository.TaskEntry> tasks,
            final String packageName) {
        for (final TaskRepository.TaskEntry task : tasks) {
            if (packageName.equals(task.packageName)) {
                return task.taskId;
            }
        }
        return -1;
    }

    private List<TaskRepository.TaskEntry>
            captureVisibleFreeformTasks() {
        final List<TaskRepository.TaskEntry> watchedTasks =
                MagicDeskRuntime.getVisibleFreeformTasks(
                        mActivity.getCurrentDisplayId());
        return watchedTasks == null
                ? getVisibleFreeformTasks(mActivity.getTaskSnapshot())
                : watchedTasks;
    }

    private List<TaskRepository.TaskEntry> getVisibleFreeformTasks(
            final TaskRepository.Snapshot snapshot) {
        return DesktopTaskController.selectVisibleFreeformTasks(snapshot);
    }

    private List<TaskRepository.TaskEntry>
            takeInteractionVisibleTasks() {
        final List<TaskRepository.TaskEntry> visibleTasks =
                mInteractionVisibleTasks.isEmpty()
                        ? captureVisibleFreeformTasks()
                        : new ArrayList<>(mInteractionVisibleTasks);
        mInteractionVisibleTasks = Collections.emptyList();
        return visibleTasks;
    }

    private static int[] getTaskIds(
            final List<TaskRepository.TaskEntry> tasks) {
        final int[] taskIds =
                new int[tasks == null ? 0 : tasks.size()];
        for (int index = 0; index < taskIds.length; index++) {
            taskIds[index] = tasks.get(index).taskId;
        }
        return taskIds;
    }

    private static int getFullscreenLaunchFlags() {
        return Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT;
    }
}

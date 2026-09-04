package io.github.mekhontsev.magicdesk;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
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
    private static final int WINDOWING_MODE_FULLSCREEN = 1;

    private interface PreparedTaskAction {
        void run(int displayId, int taskId, boolean reused) throws IOException;
    }

    private interface LaunchFailureAction {
        void run(Throwable error);
    }

    private interface WindowLaunchOperation {
        WindowedAppLauncher.LaunchResult launch(
                int displayId,
                int[] preservedTaskIds,
                WindowedAppLauncher.TaskReadyCallback taskReadyCallback)
                throws IOException;
    }

    private interface FullscreenTaskSource {
        AppLaunchTarget launchTarget();
        boolean requestsSeparateTask();
        int launchFresh(int displayId) throws IOException;
        void activateExisting(int displayId, int taskId) throws IOException;
        String diagnosticKind();
    }

    private static final class PreparedFullscreenTask {
        final int taskId;
        final boolean reused;

        PreparedFullscreenTask(final int taskId, final boolean reused) {
            this.taskId = taskId;
            this.reused = reused;
        }
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
        launchDefault(app, null, null);
    }

    /** Runs {@code onPrepared} only after the target task is ready for use. */
    void launchDefault(final AppItem app, final Runnable onPrepared) {
        launchDefault(app, onPrepared, null);
    }

    void launchDefault(
            final AppItem app,
            final Runnable onPrepared,
            final DesktopActivityLaunchResult.Completion completion) {
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
                    onPrepared,
                    completion);
        } else if (saved != null
                && saved.mode == AppWindowState.Mode.FULLSCREEN) {
            launchFullscreen(
                    app,
                    false,
                    app.label,
                    preparedTaskAction(onPrepared),
                    completion);
        } else if (canControlWindowing()
                && app.canFloat
                && AppItem.FULLSCREEN_REASON_NONE.equals(
                        app.fullscreenReason)) {
            launchFloating(
                    app,
                    false,
                    null,
                    WindowedAppLauncher.TaskReusePolicy.REUSE_EXISTING,
                    onPrepared,
                    completion);
        } else {
            launchFullscreen(
                    app,
                    false,
                    app.label,
                    preparedTaskAction(onPrepared),
                    completion);
        }
    }

    void launchForMode(
            final AppItem app,
            final DesktopLaunchMode mode,
            final Runnable onPrepared) {
        launchForMode(app, mode, null, onPrepared, null);
    }

    void launchForMode(
            final AppItem app,
            final DesktopLaunchMode mode,
            final RelativeWindowBounds preferredBounds,
            final Runnable onPrepared) {
        launchForMode(app, mode, preferredBounds, onPrepared, null);
    }

    void launchForMode(
            final AppItem app,
            final DesktopLaunchMode mode,
            final RelativeWindowBounds preferredBounds,
            final Runnable onPrepared,
            final DesktopActivityLaunchResult.Completion completion) {
        if (mode == DesktopLaunchMode.WINDOWED) {
            final AppWindowState saved = savedWindowState(app);
            launchFloating(
                    app,
                    true,
                    preferredBounds != null
                            ? preferredBounds
                            : saved == null ? null : saved.windowBounds,
                    WindowedAppLauncher.TaskReusePolicy.REUSE_EXISTING,
                    onPrepared,
                    completion);
        } else if (mode == DesktopLaunchMode.FULLSCREEN) {
            launchFullscreen(
                    app,
                    true,
                    app.label,
                    preparedTaskAction(onPrepared),
                    completion);
        } else {
            launchDefault(app, onPrepared, completion);
        }
    }

    void launchShortcut(
            final AppItem app,
            final AppShortcutAction shortcut) {
        launchShortcut(app, shortcut, DesktopLaunchMode.AUTO, null);
    }

    void launchShortcut(
            final AppItem app,
            final AppShortcutAction shortcut,
            final DesktopLaunchMode launchMode) {
        launchShortcut(app, shortcut, launchMode, null);
    }

    void launchShortcut(
            final AppItem app,
            final AppShortcutAction shortcut,
            final DesktopLaunchMode launchMode,
            final DesktopActivityLaunchResult.Completion completion) {
        if (app == null || shortcut == null) {
            complete(completion, DesktopActivityLaunchResult.failed(
                    "app shortcut is unavailable"));
            return;
        }
        final AppWindowState saved = savedWindowState(app);
        if (launchMode == DesktopLaunchMode.WINDOWED) {
            launchShortcutWindowed(
                    app,
                    shortcut,
                    true,
                    saved == null ? null : saved.windowBounds,
                    completion);
        } else if (launchMode == DesktopLaunchMode.FULLSCREEN) {
            launchShortcutFullscreen(app, shortcut, completion);
        } else if (saved != null
                && saved.shouldLaunchWindowed()
                && canControlWindowing()) {
            launchShortcutWindowed(
                    app, shortcut, true, saved.windowBounds, completion);
        } else if (saved != null
                && saved.mode == AppWindowState.Mode.FULLSCREEN) {
            launchShortcutFullscreen(app, shortcut, completion);
        } else if (canControlWindowing()
                && app.canFloat
                && AppItem.FULLSCREEN_REASON_NONE.equals(
                        app.fullscreenReason)) {
            launchShortcutWindowed(
                    app, shortcut, false, null, completion);
        } else {
            launchShortcutFullscreen(app, shortcut, completion);
        }
    }

    void launchIntent(
            final AppItem app,
            final String name,
            final Intent intent,
            final AppLaunchTarget taskTarget,
            final DesktopLaunchMode mode) {
        launchIntent(
                app,
                name,
                intent,
                taskTarget,
                mode,
                AndroidLaunchSpec.Delivery.SHELL_INTENT,
                null);
    }

    void launchIntent(
            final AppItem app,
            final String name,
            final Intent intent,
            final AppLaunchTarget taskTarget,
            final DesktopLaunchMode mode,
            final AndroidLaunchSpec.Delivery delivery,
            final DesktopActivityLaunchResult.Completion completion) {
        if (app == null || intent == null
                || (delivery == AndroidLaunchSpec.Delivery.SHELL_INTENT
                        && intent.getComponent() == null)
                || taskTarget == null) {
            complete(completion, DesktopActivityLaunchResult.failed(
                    "resolved Activity target is unavailable"));
            return;
        }
        final DesktopLaunchMode resolvedMode = mode == null
                ? DesktopLaunchMode.AUTO : mode;
        if (resolvedMode == DesktopLaunchMode.WINDOWED) {
            final AppWindowState saved = savedWindowState(app);
            launchIntentWindowed(
                    app,
                    name,
                    intent,
                    taskTarget,
                    true,
                    saved == null ? null : saved.windowBounds,
                    delivery,
                    completion);
        } else if (resolvedMode == DesktopLaunchMode.FULLSCREEN) {
            launchIntentFullscreen(
                    app, name, intent, taskTarget, delivery, completion);
        } else {
            final AppWindowState saved = savedWindowState(app);
            if (saved != null
                    && saved.shouldLaunchWindowed()
                    && canControlWindowing()) {
                launchIntentWindowed(
                        app,
                        name,
                        intent,
                        taskTarget,
                        true,
                        saved.windowBounds,
                        delivery,
                        completion);
            } else if (saved != null
                    && saved.mode == AppWindowState.Mode.FULLSCREEN) {
                launchIntentFullscreen(
                        app, name, intent, taskTarget, delivery, completion);
            } else if (canControlWindowing()
                    && app.canFloat
                    && AppItem.FULLSCREEN_REASON_NONE.equals(
                            app.fullscreenReason)) {
                launchIntentWindowed(
                        app,
                        name,
                        intent,
                        taskTarget,
                        false,
                        null,
                        delivery,
                        completion);
            } else {
                launchIntentFullscreen(
                        app, name, intent, taskTarget, delivery, completion);
            }
        }
    }

    private void launchIntentWindowed(
            final AppItem app,
            final String name,
            final Intent intent,
            final AppLaunchTarget taskTarget,
            final boolean explicitWindowed,
            final RelativeWindowBounds preferredBounds,
            final AndroidLaunchSpec.Delivery delivery,
            final DesktopActivityLaunchResult.Completion completion) {
        if (!canControlWindowing()) {
            launchIntentFullscreen(
                    app, name, intent, taskTarget, delivery, completion);
            return;
        }
        if (delivery == AndroidLaunchSpec.Delivery.APP_PENDING_INTENT) {
            final PendingIntent pendingIntent =
                    AndroidPendingActivityLaunch.create(mActivity, intent);
            launchWindow(
                    name,
                    (displayId, preservedTaskIds, taskReadyCallback) ->
                            WindowedAppLauncher.launchPendingActivity(
                                    pendingIntent,
                                    taskTarget,
                                    displayId,
                                    preservedTaskIds,
                                    explicitWindowed,
                                    preferredBounds,
                                    requestsSeparateTask(intent)
                                            ? WindowedAppLauncher.TaskReusePolicy
                                                    .CREATE_NEW
                                            : WindowedAppLauncher.TaskReusePolicy
                                                    .REUSE_EXISTING,
                                    taskReadyCallback),
                    null,
                    null,
                    completion);
            return;
        }
        final AppLaunchTarget intentTarget = taskTarget == null
                ? launchTargetForIntent(app, intent) : taskTarget;
        final boolean indirectLaunch = !intentTarget.packageName.equals(
                intent.getComponent().getPackageName());
        launchWindow(
                new Intent(intent),
                intentTarget,
                name,
                explicitWindowed,
                preferredBounds,
                !indirectLaunch && requestsSeparateTask(intent)
                        ? WindowedAppLauncher.TaskReusePolicy.CREATE_NEW
                        : WindowedAppLauncher.TaskReusePolicy.REUSE_EXISTING,
                indirectLaunch
                        ? (displayId, taskId, bounds) ->
                                MagicDeskRuntime.launchWindowedTask(
                                        displayId,
                                        new Intent(intent),
                                        bounds)
                        : null,
                (displayId, taskId, reused) -> {
                    if (reused && !indirectLaunch) {
                        MagicDeskRuntime.launchTaskAction(
                                displayId, taskId, intent);
                    }
                },
                null,
                completion);
    }

    private void launchIntentFullscreen(
            final AppItem app,
            final String name,
            final Intent intent,
            final AppLaunchTarget taskTarget,
            final AndroidLaunchSpec.Delivery delivery,
            final DesktopActivityLaunchResult.Completion completion) {
        final PendingIntent pendingIntent = delivery
                == AndroidLaunchSpec.Delivery.APP_PENDING_INTENT
                ? AndroidPendingActivityLaunch.create(mActivity, intent)
                : null;
        launchFullscreen(
                app,
                false,
                name,
                intentFullscreenTaskSource(
                        app, intent, taskTarget, pendingIntent),
                null,
                null,
                completion);
    }

    private void launchShortcutWindowed(
            final AppItem app,
            final AppShortcutAction shortcut,
            final boolean explicitWindowed,
            final RelativeWindowBounds preferredBounds) {
        launchShortcutWindowed(
                app,
                shortcut,
                explicitWindowed,
                preferredBounds,
                null);
    }

    private void launchShortcutWindowed(
            final AppItem app,
            final AppShortcutAction shortcut,
            final boolean explicitWindowed,
            final RelativeWindowBounds preferredBounds,
            final DesktopActivityLaunchResult.Completion completion) {
        if (!canControlWindowing()) {
            launchShortcutFullscreen(app, shortcut, completion);
            return;
        }
        Log.i(TAG, "launch app shortcut package=" + app.packageName
                + " shortcut=" + shortcut.id
                + " display=" + mActivity.getCurrentDisplayId());
        launchWindow(
                shortcut.label,
                (displayId, preservedTaskIds, taskReadyCallback) ->
                        WindowedAppLauncher.launchShortcut(
                                shortcut,
                                displayId,
                                preservedTaskIds,
                                explicitWindowed,
                                preferredBounds,
                                taskReadyCallback),
                null,
                null,
                completion);
    }

    private void launchShortcutFullscreen(
            final AppItem app,
            final AppShortcutAction shortcut) {
        launchShortcutFullscreen(app, shortcut, null);
    }

    private void launchShortcutFullscreen(
            final AppItem app,
            final AppShortcutAction shortcut,
            final DesktopActivityLaunchResult.Completion completion) {
        Log.i(TAG, "launch fullscreen app shortcut package="
                + app.packageName
                + " shortcut=" + shortcut.id
                + " display=" + mActivity.getCurrentDisplayId());
        launchFullscreen(
                app,
                false,
                shortcut.label,
                shortcutFullscreenTaskSource(shortcut),
                null,
                null,
                completion);
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
                WindowedAppLauncher.TaskReusePolicy.REUSE_EXISTING,
                null,
                null);
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
                null,
                null);
    }

    private void launchFloating(
            final AppItem app,
            final boolean explicitWindowed,
            final RelativeWindowBounds preferredBounds,
            final WindowedAppLauncher.TaskReusePolicy reusePolicy,
            final Runnable onPrepared) {
        launchFloating(
                app,
                explicitWindowed,
                preferredBounds,
                reusePolicy,
                onPrepared,
                null);
    }

    private void launchFloating(
            final AppItem app,
            final boolean explicitWindowed,
            final RelativeWindowBounds preferredBounds,
            final WindowedAppLauncher.TaskReusePolicy reusePolicy,
            final Runnable onPrepared,
            final DesktopActivityLaunchResult.Completion completion) {
        if (!canControlWindowing()) {
            launchFullscreen(
                    app,
                    false,
                    app.label,
                    preparedTaskAction(onPrepared),
                    completion);
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
                            complete(completion,
                                    DesktopActivityLaunchResult.observedTask(
                                            existingTask.taskId,
                                            existingTask.displayId,
                                            true));
                        } else {
                            AppWindowStateStore.cancelModeUpdate(modeUpdate);
                            complete(completion,
                                    DesktopActivityLaunchResult.failed(
                                            "could not focus reused task"));
                        }
                    });
            return;
        }
        final Intent launchIntent = app.launchTarget.resolve(
                mActivity.getPackageManager());
        if (launchIntent == null) {
            AppWindowStateStore.cancelModeUpdate(modeUpdate);
            showMissingLauncher(app);
            complete(completion, DesktopActivityLaunchResult.failed(
                    "no launcher Activity is available"));
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
                null,
                (displayId, taskId, reused) -> {
                    AppWindowStateStore.commitModeUpdate(modeUpdate);
                    runIfPresent(onPrepared);
                },
                error -> AppWindowStateStore.cancelModeUpdate(modeUpdate),
                completion);
    }

    private static PreparedTaskAction preparedTaskAction(
            final Runnable onPrepared) {
        if (onPrepared == null) {
            return null;
        }
        return (displayId, taskId, reused) -> onPrepared.run();
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
                null,
                afterLaunch,
                null,
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
            final LaunchFailureAction onFailure) {
        launchWindow(
                launchIntent,
                launchTarget,
                label,
                explicitWindowed,
                preferredBounds,
                reusePolicy,
                null,
                afterLaunch,
                onFailure,
                null);
    }

    private void launchWindow(
            final Intent launchIntent,
            final AppLaunchTarget launchTarget,
            final String label,
            final boolean explicitWindowed,
            final RelativeWindowBounds preferredBounds,
            final WindowedAppLauncher.TaskReusePolicy reusePolicy,
            final WindowedAppLauncher.ExistingTaskLauncher existingTaskLauncher,
            final PreparedTaskAction afterLaunch,
            final LaunchFailureAction onFailure,
            final DesktopActivityLaunchResult.Completion completion) {
        launchWindow(
                label,
                (displayId, preservedTaskIds, taskReadyCallback) ->
                        WindowedAppLauncher.launch(
                                launchIntent,
                                launchTarget,
                                displayId,
                                preservedTaskIds,
                                explicitWindowed,
                                preferredBounds,
                                reusePolicy,
                                existingTaskLauncher,
                                taskReadyCallback),
                afterLaunch,
                onFailure,
                completion);
    }

    private void launchWindow(
            final String label,
            final WindowLaunchOperation launchOperation,
            final PreparedTaskAction afterLaunch,
            final LaunchFailureAction onFailure,
            final DesktopActivityLaunchResult.Completion completion) {
        mActivity.setTaskbarVisible(true);
        mActivity.setStatus(mActivity.getString(
                R.string.status_launching_window, label));
        final List<TaskRepository.TaskEntry> visibleTasks =
                takeInteractionVisibleTasks();
        final int displayId = mActivity.getCurrentDisplayId();
        TaskCommandQueue.execute(() -> {
            try {
                final WindowedAppLauncher.LaunchResult launch =
                        launchOperation.launch(
                        displayId,
                        getTaskIds(visibleTasks),
                        () -> publishConfirmedLaunchSnapshot(displayId));
                if (afterLaunch != null) {
                    afterLaunch.run(
                            displayId, launch.taskId, launch.reused);
                }
                complete(completion, DesktopActivityLaunchResult.observedTask(
                        launch.taskId, displayId, launch.reused));
                mActivity.runOnUiThread(() -> {
                    if (mActivity.isActivityUnavailable()) {
                        return;
                    }
                    mActivity.setStatus(mActivity.getString(
                            R.string.status_switch_done, label));
                    mActivity.refreshTaskSnapshot();
                });
            } catch (IOException | RuntimeException error) {
                runIfPresent(onFailure, error);
                complete(completion, DesktopActivityLaunchResult.failed(error));
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
        launchFullscreen(
                app, rememberMode, label, afterLaunch, null);
    }

    private void launchFullscreen(
            final AppItem app,
            final boolean rememberMode,
            final String label,
            final PreparedTaskAction afterLaunch,
            final DesktopActivityLaunchResult.Completion completion) {
        launchFullscreen(
                app,
                rememberMode,
                label,
                intentFullscreenTaskSource(app, null, app.launchTarget),
                afterLaunch,
                null,
                completion);
    }

    private void launchFullscreen(
            final AppItem app,
            final boolean rememberMode,
            final String label,
            final FullscreenTaskSource taskSource,
            final PreparedTaskAction afterLaunch) {
        launchFullscreen(
                app,
                rememberMode,
                label,
                taskSource,
                afterLaunch,
                null,
                null);
    }

    private void launchFullscreen(
            final AppItem app,
            final boolean rememberMode,
            final String label,
            final FullscreenTaskSource taskSource,
            final PreparedTaskAction afterLaunch,
            final LaunchFailureAction onFailure,
            final DesktopActivityLaunchResult.Completion completion) {
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
                final PreparedFullscreenTask prepared =
                        prepareFullscreenTask(app, displayId, taskSource);
                final int taskId = prepared.taskId;
                if (!MagicDeskRuntime.protectExplicitFullscreenTask(
                        displayId, taskId)) {
                    Log.w(TAG, "fullscreen activity handoff guard unavailable"
                            + " package=" + app.packageName
                            + " task=" + taskId
                            + " display=" + displayId);
                }
                if (afterLaunch != null) {
                    afterLaunch.run(displayId, taskId, prepared.reused);
                }
                if (modeUpdate != null) {
                    AppWindowStateStore.commitModeUpdate(modeUpdate);
                }
                MagicDeskRuntime.finishFullscreenTransition(
                        displayId, true);
                complete(completion, DesktopActivityLaunchResult.observedTask(
                        taskId, displayId, prepared.reused));
            } catch (IOException | RuntimeException error) {
                AppWindowStateStore.cancelModeUpdate(modeUpdate);
                MagicDeskRuntime.finishFullscreenTransition(
                        displayId, false);
                runIfPresent(onFailure, error);
                complete(completion, DesktopActivityLaunchResult.failed(error));
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

    private PreparedFullscreenTask prepareFullscreenTask(
            final AppItem app,
            final int displayId,
            final FullscreenTaskSource taskSource) throws IOException {
        if (ShellAccess.isReady() && !taskSource.requestsSeparateTask()) {
            final ExistingTaskController.ReuseResult reuseResult =
                    ExistingTaskController.reuseIfExists(
                            taskSource.launchTarget(), displayId, false);
            if (reuseResult.found) {
                if (!MagicDeskRuntime.attachFullscreenTask(
                        displayId, reuseResult.taskId)) {
                    throw new IOException(
                            "could not attach reused fullscreen task"
                                    + " to its plane");
                }
                taskSource.activateExisting(
                        displayId, reuseResult.taskId);
                DesktopTaskLaunchDiagnostics.note(
                        reuseResult.taskId,
                        reuseResult.originalDisplayId,
                        displayId,
                        taskSource.diagnosticKind() + "-reuse");
                Log.i(TAG, "reused fullscreen package=" + app.packageName);
                return new PreparedFullscreenTask(
                        reuseResult.taskId, true);
            }
        }

        Log.i(TAG, "fresh fullscreen launch package=" + app.packageName);
        final int taskId = taskSource.launchFresh(displayId);
        DesktopTaskLaunchDiagnostics.note(
                taskId,
                displayId,
                displayId,
                taskSource.diagnosticKind() + "-new");
        return new PreparedFullscreenTask(taskId, false);
    }

    private FullscreenTaskSource intentFullscreenTaskSource(
            final AppItem app,
            final Intent initialIntent,
            final AppLaunchTarget taskTarget) {
        return intentFullscreenTaskSource(
                app, initialIntent, taskTarget, null);
    }

    private FullscreenTaskSource intentFullscreenTaskSource(
            final AppItem app,
            final Intent initialIntent,
            final AppLaunchTarget taskTarget,
            final PendingIntent pendingIntent) {
        final Intent sourceIntent = initialIntent == null
                ? null : new Intent(initialIntent);
        final AppLaunchTarget launchTarget = taskTarget == null
                ? launchTargetForIntent(app, sourceIntent) : taskTarget;
        final boolean indirectLaunch = pendingIntent == null
                && sourceIntent != null
                && sourceIntent.getComponent() != null
                && !launchTarget.packageName.equals(
                        sourceIntent.getComponent().getPackageName());
        final boolean separateTask = !indirectLaunch
                && requestsSeparateTask(sourceIntent);
        return new FullscreenTaskSource() {
            @Override
            public AppLaunchTarget launchTarget() {
                return launchTarget;
            }

            @Override
            public boolean requestsSeparateTask() {
                return separateTask;
            }

            @Override
            public int launchFresh(final int displayId) throws IOException {
                return launchFreshFullscreenIntent(
                        app,
                        sourceIntent,
                        launchTarget,
                        pendingIntent,
                        separateTask,
                        displayId);
            }

            @Override
            public void activateExisting(
                    final int displayId,
                    final int taskId) throws IOException {
                if (sourceIntent != null) {
                    if (pendingIntent != null) {
                        MagicDeskRuntime.launchPendingActivity(
                                displayId,
                                launchTarget,
                                pendingIntent,
                                WINDOWING_MODE_FULLSCREEN,
                                new Rect(),
                                taskId);
                    } else if (indirectLaunch) {
                        launchFreshFullscreenIntent(
                                app,
                                sourceIntent,
                                launchTarget,
                                null,
                                false,
                                displayId);
                    } else {
                        MagicDeskRuntime.launchTaskAction(
                                displayId, taskId, sourceIntent);
                    }
                }
            }

            @Override
            public String diagnosticKind() {
                return "desktop-fullscreen";
            }
        };
    }

    private int launchFreshFullscreenIntent(
            final AppItem app,
            final Intent sourceIntent,
            final AppLaunchTarget launchTarget,
            final PendingIntent pendingIntent,
            final boolean separateTask,
            final int displayId) throws IOException {
        if (pendingIntent != null) {
            return MagicDeskRuntime.launchPendingActivity(
                    displayId,
                    launchTarget,
                    pendingIntent,
                    WINDOWING_MODE_FULLSCREEN,
                    new Rect(),
                    -1);
        }
        final Intent launchIntent = sourceIntent == null
                ? app.launchTarget.resolve(mActivity.getPackageManager())
                : new Intent(sourceIntent);
        if (launchIntent == null) {
            throw new IOException("no launcher activity");
        }
        launchIntent.addFlags(separateTask
                ? Intent.FLAG_ACTIVITY_NEW_TASK
                : getFullscreenLaunchFlags());
        return MagicDeskRuntime.launchFullscreenTask(displayId, launchIntent);
    }

    private static FullscreenTaskSource shortcutFullscreenTaskSource(
            final AppShortcutAction shortcut) {
        return new FullscreenTaskSource() {
            @Override
            public AppLaunchTarget launchTarget() {
                return shortcut.taskTarget();
            }

            @Override
            public boolean requestsSeparateTask() {
                return false;
            }

            @Override
            public int launchFresh(final int displayId) throws IOException {
                return MagicDeskRuntime.launchAppShortcut(
                        displayId,
                        shortcut.packageName,
                        shortcut.id,
                        shortcut.user,
                        WINDOWING_MODE_FULLSCREEN,
                        new Rect(),
                        -1);
            }

            @Override
            public void activateExisting(
                    final int displayId,
                    final int taskId) throws IOException {
                MagicDeskRuntime.launchAppShortcut(
                        displayId,
                        shortcut.packageName,
                        shortcut.id,
                        shortcut.user,
                        WINDOWING_MODE_FULLSCREEN,
                        new Rect(),
                        taskId);
            }

            @Override
            public String diagnosticKind() {
                return "app-shortcut-fullscreen";
            }
        };
    }

    private static boolean requestsSeparateTask(final Intent intent) {
        if (intent == null) {
            return false;
        }
        final int separateFlags = Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
        return (intent.getFlags() & separateFlags) == separateFlags;
    }

    private static AppLaunchTarget launchTargetForIntent(
            final AppItem app,
            final Intent intent) {
        final ComponentName component = intent == null
                ? null : intent.getComponent();
        return component == null
                ? app.launchTarget
                : AppLaunchTarget.explicit(
                        component.getPackageName(),
                        component.getClassName(),
                        intent.getAction());
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

    private static void complete(
            final DesktopActivityLaunchResult.Completion completion,
            final DesktopActivityLaunchResult result) {
        if (completion != null) {
            completion.onComplete(result);
        }
    }

    private static void runIfPresent(
            final LaunchFailureAction action,
            final Throwable error) {
        if (action != null) {
            action.run(error);
        }
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
                        runFocusCompletion(completion, false);
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
                    final TaskRepository.Snapshot desktopSnapshot =
                            mActivity.setTaskSnapshot(snapshot);
                    if (!desktopSnapshot.available) {
                        mActivity.setStatus(mActivity.getString(
                                R.string.status_switch_failed,
                                desktopSnapshot.error));
                        runFocusCompletion(completion, false);
                        return;
                    }
                    final TaskRepository.TaskEntry currentTask =
                            DesktopShellActivity.findTask(
                                    desktopSnapshot, task.taskId);
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
                                    runFocusCompletion(completion, false);
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
                                // The pre-command interaction snapshot no
                                // longer describes the visible stack after a
                                // cross-area focus commit. Let the snapshot
                                // controller apply the single taskbar policy
                                // from the resulting hierarchy.
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
        mActivity.setStatus(mActivity.getString(
                R.string.status_launching_fullscreen, app.label));
        final TaskRepository.ActionCallback completion = result ->
                mActivity.runOnUiThread(() -> {
                    if (mActivity.isActivityUnavailable()) {
                        return;
                    }
                    if (result.success) {
                        mActivity.setTaskbarVisible(keepTaskbarVisible);
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
        if (!MagicDeskRuntime.makeTaskFullscreen(task, completion)) {
            completion.onComplete(new TaskRepository.ActionResult(
                    false, "desktop transition gateway unavailable"));
        }
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

package io.github.mekhontsev.magicdesk;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.util.Log;
import android.view.Display;

import org.json.JSONObject;

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
        DesktopTaskInstancePolicy instancePolicy();
        int preferredTaskId();
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
        launchDefault(
                app,
                DesktopLaunchPresentation.automatic(),
                onPrepared,
                completion);
    }

    private void launchDefault(
            final AppItem app,
            final DesktopLaunchPresentation presentation,
            final Runnable onPrepared,
            final DesktopActivityLaunchResult.Completion completion) {
        final DesktopLaunchPresentation policy = presentation == null
                ? DesktopLaunchPresentation.automatic() : presentation;
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
                    policy.instancePolicy,
                    policy.preferredTaskId,
                    onPrepared,
                    completion);
        } else if (saved != null
                && saved.mode == AppWindowState.Mode.FULLSCREEN) {
            launchFullscreen(
                    app,
                    false,
                    app.label,
                    intentFullscreenTaskSource(
                            app,
                            null,
                            app.launchTarget,
                            null,
                            policy.instancePolicy,
                            policy.preferredTaskId),
                    preparedTaskAction(onPrepared),
                    null,
                    completion);
        } else if (canControlWindowing()
                && app.canFloat
                && AppItem.FULLSCREEN_REASON_NONE.equals(
                        app.fullscreenReason)) {
            launchFloating(
                    app,
                    false,
                    policy.bounds,
                    policy.instancePolicy,
                    policy.preferredTaskId,
                    onPrepared,
                    completion);
        } else {
            launchFullscreen(
                    app,
                    false,
                    app.label,
                    intentFullscreenTaskSource(
                            app,
                            null,
                            app.launchTarget,
                            null,
                            policy.instancePolicy,
                            policy.preferredTaskId),
                    preparedTaskAction(onPrepared),
                    null,
                    completion);
        }
    }

    void launchForPresentation(
            final AppItem app,
            final DesktopLaunchPresentation presentation,
            final Runnable onPrepared,
            final DesktopActivityLaunchResult.Completion completion) {
        final DesktopLaunchPresentation policy = presentation == null
                ? DesktopLaunchPresentation.automatic() : presentation;
        if (policy.mode == DesktopLaunchMode.WINDOWED) {
            final AppWindowState saved = savedWindowState(app);
            launchFloating(
                    app,
                    true,
                    policy.bounds != null
                            ? policy.bounds
                            : saved == null ? null : saved.windowBounds,
                    policy.instancePolicy,
                    policy.preferredTaskId,
                    onPrepared,
                    completion);
        } else if (policy.mode == DesktopLaunchMode.FULLSCREEN) {
            launchFullscreen(
                    app,
                    true,
                    app.label,
                    intentFullscreenTaskSource(
                            app,
                            null,
                            app.launchTarget,
                            null,
                            policy.instancePolicy,
                            policy.preferredTaskId),
                    preparedTaskAction(onPrepared),
                    null,
                    completion);
        } else {
            launchDefault(app, policy, onPrepared, completion);
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
            final DesktopLaunchPresentation presentation,
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
        final DesktopLaunchPresentation policy = presentation == null
                ? DesktopLaunchPresentation.automatic() : presentation;
        final DesktopLaunchMode resolvedMode = policy.mode;
        if (resolvedMode == DesktopLaunchMode.WINDOWED) {
            final AppWindowState saved = savedWindowState(app);
            launchIntentWindowed(
                    app,
                    name,
                    intent,
                    taskTarget,
                    true,
                    policy.bounds != null
                            ? policy.bounds
                            : saved == null ? null : saved.windowBounds,
                    policy.instancePolicy,
                    policy.preferredTaskId,
                    delivery,
                    completion);
        } else if (resolvedMode == DesktopLaunchMode.FULLSCREEN) {
            launchIntentFullscreen(
                    app,
                    name,
                    intent,
                    taskTarget,
                    policy.instancePolicy,
                    policy.preferredTaskId,
                    delivery,
                    completion);
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
                        policy.bounds != null
                                ? policy.bounds : saved.windowBounds,
                        policy.instancePolicy,
                        policy.preferredTaskId,
                        delivery,
                        completion);
            } else if (saved != null
                    && saved.mode == AppWindowState.Mode.FULLSCREEN) {
                launchIntentFullscreen(
                        app,
                        name,
                        intent,
                        taskTarget,
                        policy.instancePolicy,
                        policy.preferredTaskId,
                        delivery,
                        completion);
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
                        policy.bounds,
                        policy.instancePolicy,
                        policy.preferredTaskId,
                        delivery,
                        completion);
            } else {
                launchIntentFullscreen(
                        app,
                        name,
                        intent,
                        taskTarget,
                        policy.instancePolicy,
                        policy.preferredTaskId,
                        delivery,
                        completion);
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
            final DesktopTaskInstancePolicy instancePolicy,
            final int preferredTaskId,
            final AndroidLaunchSpec.Delivery delivery,
            final DesktopActivityLaunchResult.Completion completion) {
        if (!canControlWindowing()) {
            launchIntentFullscreen(
                    app,
                    name,
                    intent,
                    taskTarget,
                    instancePolicy,
                    preferredTaskId,
                    delivery,
                    completion);
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
                                    instancePolicy,
                                    preferredTaskId,
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
                instancePolicy,
                preferredTaskId,
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
            final DesktopTaskInstancePolicy instancePolicy,
            final int preferredTaskId,
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
                        app,
                        intent,
                        taskTarget,
                        pendingIntent,
                        instancePolicy,
                        preferredTaskId),
                null,
                null,
                completion);
    }

    void launchPendingActivity(
            final AppItem app,
            final String name,
            final PendingIntent pendingIntent,
            final AppLaunchTarget taskTarget,
            final DesktopLaunchPresentation presentation,
            final DesktopActivityLaunchResult.Completion completion) {
        if (app == null || pendingIntent == null || taskTarget == null) {
            complete(completion, DesktopActivityLaunchResult.failed(
                    "pending Activity target is unavailable"));
            return;
        }
        final DesktopLaunchPresentation policy = presentation == null
                ? DesktopLaunchPresentation.automatic() : presentation;
        if (policy.mode == DesktopLaunchMode.FULLSCREEN) {
            launchFullscreen(
                    app,
                    false,
                    name,
                    intentFullscreenTaskSource(
                            app,
                            null,
                            taskTarget,
                            pendingIntent,
                            policy.instancePolicy,
                            policy.preferredTaskId),
                    null,
                    null,
                    completion);
            return;
        }
        if (!canControlWindowing()
                && policy.mode != DesktopLaunchMode.WINDOWED) {
            launchFullscreen(
                    app,
                    false,
                    name,
                    intentFullscreenTaskSource(
                            app,
                            null,
                            taskTarget,
                            pendingIntent,
                            policy.instancePolicy,
                            policy.preferredTaskId),
                    null,
                    null,
                    completion);
            return;
        }
        launchWindow(
                name,
                (displayId, preservedTaskIds, taskReadyCallback) ->
                        WindowedAppLauncher.launchPendingActivity(
                                pendingIntent,
                                taskTarget,
                                displayId,
                                preservedTaskIds,
                                policy.mode == DesktopLaunchMode.WINDOWED,
                                policy.bounds,
                                policy.instancePolicy,
                                policy.preferredTaskId,
                                taskReadyCallback),
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
                DesktopTaskInstancePolicy.CREATE_NEW);
    }

    void openAppInfo(final AppItem app) {
        if (app == null) {
            return;
        }
        try {
            AndroidDesktopActionDispatcher.dispatch(
                    mActivity,
                    AndroidDesktopActionCatalog.create(
                            "app-details",
                            new JSONObject()
                                    .put("package", app.packageName)
                                    .put("mode", "windowed")
                                    .put("instance", "new"),
                            "app-context-menu"),
                    mActivity.getCurrentDisplayId(),
                    result -> {
                        if (!result.success) {
                            mActivity.setErrorStatus(
                                    "APP-INFO-001", result.message);
                        }
                    });
        } catch (Exception error) {
            mActivity.showLaunchFailure(error);
        }
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
            final DesktopTaskInstancePolicy instancePolicy = multipleWindows
                    ? DesktopTaskInstancePolicy.CREATE_NEW
                    : DesktopTaskInstancePolicy.REUSE_EXISTING;
            final Intent routedIntent = instancePolicy.applyTo(launchIntent)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (!multipleWindows) {
                routedIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            }
            mActivity.startActivity(routedIntent, options.toBundle());
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
                        ? DesktopTaskInstancePolicy.CREATE_NEW
                        : DesktopTaskInstancePolicy.REUSE_EXISTING,
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
                DesktopTaskInstancePolicy.REUSE_EXISTING,
                null,
                null);
    }

    private void launchFloating(
            final AppItem app,
            final boolean explicitWindowed,
            final RelativeWindowBounds preferredBounds,
            final DesktopTaskInstancePolicy instancePolicy) {
        launchFloating(
                app,
                explicitWindowed,
                preferredBounds,
                instancePolicy,
                null,
                null);
    }

    private void launchFloating(
            final AppItem app,
            final boolean explicitWindowed,
            final RelativeWindowBounds preferredBounds,
            final DesktopTaskInstancePolicy instancePolicy,
            final Runnable onPrepared) {
        launchFloating(
                app,
                explicitWindowed,
                preferredBounds,
                instancePolicy,
                onPrepared,
                null);
    }

    private void launchFloating(
            final AppItem app,
            final boolean explicitWindowed,
            final RelativeWindowBounds preferredBounds,
            final DesktopTaskInstancePolicy instancePolicy,
            final Runnable onPrepared,
            final DesktopActivityLaunchResult.Completion completion) {
        launchFloating(
                app,
                explicitWindowed,
                preferredBounds,
                instancePolicy,
                -1,
                onPrepared,
                completion);
    }

    private void launchFloating(
            final AppItem app,
            final boolean explicitWindowed,
            final RelativeWindowBounds preferredBounds,
            final DesktopTaskInstancePolicy instancePolicy,
            final int preferredTaskId,
            final Runnable onPrepared,
            final DesktopActivityLaunchResult.Completion completion) {
        if (!canControlWindowing()) {
            launchFullscreen(
                    app,
                    false,
                    app.label,
                    intentFullscreenTaskSource(
                            app,
                            null,
                            app.launchTarget,
                            null,
                            instancePolicy,
                            preferredTaskId),
                    preparedTaskAction(onPrepared),
                    null,
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
        if (instancePolicy == DesktopTaskInstancePolicy.REUSE_EXISTING
                && preferredTaskId < 0
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
                instancePolicy,
                preferredTaskId,
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
            final DesktopTaskInstancePolicy instancePolicy,
            final PreparedTaskAction afterLaunch) {
        launchWindow(
                launchIntent,
                launchTarget,
                label,
                explicitWindowed,
                preferredBounds,
                instancePolicy,
                -1,
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
            final DesktopTaskInstancePolicy instancePolicy,
            final PreparedTaskAction afterLaunch,
            final LaunchFailureAction onFailure) {
        launchWindow(
                launchIntent,
                launchTarget,
                label,
                explicitWindowed,
                preferredBounds,
                instancePolicy,
                -1,
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
            final DesktopTaskInstancePolicy instancePolicy,
            final int preferredTaskId,
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
                                instancePolicy,
                                preferredTaskId,
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
        if (ShellAccess.isReady()
                && taskSource.instancePolicy()
                        == DesktopTaskInstancePolicy.REUSE_EXISTING) {
            final ExistingTaskController.ReuseResult reuseResult =
                    ExistingTaskController.reuseIfExists(
                            taskSource.launchTarget(),
                            displayId,
                            false,
                            taskSource.preferredTaskId());
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
            if (taskSource.preferredTaskId() > 0) {
                throw new IOException(
                        "preferred task " + taskSource.preferredTaskId()
                                + " is unavailable for the requested target");
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
                app,
                initialIntent,
                taskTarget,
                null,
                DesktopTaskInstancePolicy.REUSE_EXISTING,
                -1);
    }

    private FullscreenTaskSource intentFullscreenTaskSource(
            final AppItem app,
            final Intent initialIntent,
            final AppLaunchTarget taskTarget,
            final PendingIntent pendingIntent,
            final DesktopTaskInstancePolicy instancePolicy,
            final int preferredTaskId) {
        final Intent sourceIntent = initialIntent == null
                ? null : new Intent(initialIntent);
        final AppLaunchTarget launchTarget = taskTarget == null
                ? launchTargetForIntent(app, sourceIntent) : taskTarget;
        final boolean indirectLaunch = pendingIntent == null
                && sourceIntent != null
                && sourceIntent.getComponent() != null
                && !launchTarget.packageName.equals(
                        sourceIntent.getComponent().getPackageName());
        return new FullscreenTaskSource() {
            @Override
            public AppLaunchTarget launchTarget() {
                return launchTarget;
            }

            @Override
            public DesktopTaskInstancePolicy instancePolicy() {
                return instancePolicy;
            }

            @Override
            public int preferredTaskId() {
                return preferredTaskId;
            }

            @Override
            public int launchFresh(final int displayId) throws IOException {
                return launchFreshFullscreenIntent(
                        app,
                        sourceIntent,
                        launchTarget,
                        pendingIntent,
                        instancePolicy,
                        displayId);
            }

            @Override
            public void activateExisting(
                    final int displayId,
                    final int taskId) throws IOException {
                if (pendingIntent != null) {
                    MagicDeskRuntime.launchPendingActivity(
                            displayId,
                            launchTarget,
                            pendingIntent,
                            WINDOWING_MODE_FULLSCREEN,
                            new Rect(),
                            taskId);
                } else if (sourceIntent != null && indirectLaunch) {
                    launchFreshFullscreenIntent(
                            app,
                            sourceIntent,
                            launchTarget,
                            null,
                            DesktopTaskInstancePolicy.REUSE_EXISTING,
                            displayId);
                } else if (sourceIntent != null) {
                    MagicDeskRuntime.launchTaskAction(
                            displayId, taskId, sourceIntent);
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
            final DesktopTaskInstancePolicy instancePolicy,
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
        final Intent unresolvedIntent = sourceIntent == null
                ? app.launchTarget.resolve(mActivity.getPackageManager())
                : new Intent(sourceIntent);
        if (unresolvedIntent == null) {
            throw new IOException("no launcher activity");
        }
        final Intent launchIntent = instancePolicy.applyTo(unresolvedIntent);
        launchIntent.addFlags(instancePolicy
                == DesktopTaskInstancePolicy.CREATE_NEW
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
            public DesktopTaskInstancePolicy instancePolicy() {
                return DesktopTaskInstancePolicy.REUSE_EXISTING;
            }

            @Override
            public int preferredTaskId() {
                return -1;
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

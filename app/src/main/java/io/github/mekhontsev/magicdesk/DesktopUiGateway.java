package io.github.mekhontsev.magicdesk;

import android.content.Intent;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Process-local gateway to the live desktop host Activity. */
final class DesktopUiGateway {
    private static final String TAG = "MagicDesk";

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Object mHostLock = new Object();
    private final DesktopSessionRegistry mSession;

    private final java.util.Map<android.view.Window, DesktopAutomationUiRegistry> mUiWindows =
            new java.util.WeakHashMap<>();

    void registerUiWindow(final android.view.Window window, final DesktopAutomationUiRegistry registry) {
        mUiWindows.put(window, registry);
    }

    void unregisterUiWindow(final android.view.Window window) {
        mUiWindows.remove(window);
    }

    DesktopUiGateway(final DesktopSessionRegistry session) {
        mSession = session;
    }

    // UI-thread only. A phone HOME registry is not a desktop session host.
    private DesktopAutomationUiRegistry automationUi(final int displayId) {
        DesktopAutomationUiRegistry visible = null;
        boolean ambiguous = false;
        for (final java.util.Map.Entry<android.view.Window, DesktopAutomationUiRegistry> entry
                : mUiWindows.entrySet()) {
            final android.view.View decor = entry.getKey().peekDecorView();
            if (decor == null || !decor.isAttachedToWindow() || !decor.isShown()
                    || decor.getWindowVisibility() != android.view.View.VISIBLE
                    || decor.getDisplay() == null || decor.getDisplay().getDisplayId() != displayId) { continue; }
            if (decor.hasWindowFocus()) { return entry.getValue(); }
            ambiguous |= visible != null;
            visible = entry.getValue();
        }
        // Global keyboard focus can be on another display. A sole visible Start
        // still owns its local controls; multiple unfocused windows are ambiguous.
        if (!ambiguous && visible != null) { return visible; }
        final DesktopShellActivity desktop = usableDesktop(displayId, false);
        return desktop != null && desktop.getCurrentDisplayId() == displayId
                ? desktop.automationUi() : null;
    }

    boolean registerDesktop(
            final DesktopShellActivity activity,
            final DesktopDisplayTarget target,
            final DesktopSessionPolicy policy) {
        final DesktopShellActivity previous;
        final int displayId = activity.getCurrentDisplayId();
        synchronized (mHostLock) {
            previous = reconcileSessionHostLocked(displayId);
            if (previous == activity) {
                return true;
            }
            if (!mSession.registerHost(
                    displayId,
                    activity.getTaskId(),
                    target,
                    policy)) {
                recordSession(
                        "host_registration_rejected",
                        displayId,
                        activity.getTaskId());
                return false;
            }
            mSession.workspace(displayId).attachHost(activity);
            AppWindowStateStore.beginSession(mSession.workspace(displayId), mSession.snapshot(displayId).policy());
        }
        final DesktopDisplayTarget activeTarget = sessionSnapshot(displayId).target();
        if (displayId == Display.DEFAULT_DISPLAY
                && activeTarget != null
                && activeTarget.isDefaultWorkspace()
                && ShellAccess.isReady()) {
            LocalDesktopSessionState.markCleanupPending(activity);
        }
        MagicDeskRuntime.refreshDesktopTasks();
        recordSession("host_registered", displayId, activity.getTaskId());
        return true;
    }

    DesktopShellActivity desktopHomeRecipient(final DesktopShellActivity activity) {
        final Intent intent = activity.getIntent();
        // Android may create HOME in each organizer area on either display.
        // Secondary HOME must delegate too; finishing it causes a relaunch loop.
        if (intent == null || !Intent.ACTION_MAIN.equals(intent.getAction())
                || !(intent.hasCategory(Intent.CATEGORY_HOME)
                        || intent.hasCategory(Intent.CATEGORY_SECONDARY_HOME))) {
            return null;
        }
        final DesktopShellActivity host;
        synchronized (mHostLock) {
            host = reconcileSessionHostLocked(activity.getCurrentDisplayId());
            if (host == null || host.getTaskId() == activity.getTaskId()
                    || host.getCurrentDisplayId() != activity.getCurrentDisplayId()) {
                return null;
            }
        }
        return host;
    }

    void unregister(final DesktopShellActivity activity) {
        final boolean changingConfigurations =
                activity.isChangingConfigurations();
        final int displayId = activity.getCurrentDisplayId();
        final boolean desktopRemoved;
        final DesktopSessionPolicy policy;
        final DesktopWorkspaceRuntime workspace;
        synchronized (mHostLock) {
            workspace = mSession.workspace(displayId);
            desktopRemoved = workspace != null && workspace.host() == activity;
            policy = mSession.snapshot(displayId).policy();
            if (desktopRemoved) {
                mSession.unregisterHost(displayId, changingConfigurations);
            }
        }
        if (!desktopRemoved || changingConfigurations) {
            return;
        }
        DesktopSelfTestRunState.noteDesktopSessionClosed(policy, displayId);
        if (policy.persistWorkspace) {
            MagicDeskRuntime.preserveDesktopTasks(displayId);
        }
        MagicDeskRuntime.refreshDesktopTasks();
        MagicDeskRuntime.releaseDesktopWorkspace(workspace, () ->
                TaskCommandQueue.execute(
                        () -> flushWindowSessionState(workspace)));
        recordSession("host_unregistered", displayId, activity.getTaskId());
        if (displayId == Display.DEFAULT_DISPLAY) {
            MagicDeskRuntime.scheduleLocalDesktopCleanup();
        }
    }

    void closeDesktopWorkspace(
            final int displayId,
            final Runnable completion) {
        final DesktopShellActivity activity;
        final DesktopSessionPolicy policy;
        final DesktopWorkspaceRuntime workspace;
        synchronized (mHostLock) {
            activity = usableDesktopLocked(displayId, false);
            final DesktopDisplayTarget target = mSession.snapshot(displayId).target();
            if (displayId < Display.DEFAULT_DISPLAY
                    || target == null
                    || target.workspaceDisplayId != displayId) {
                if (completion != null) {
                    completion.run();
                }
                return;
            }
            policy = mSession.snapshot(displayId).policy();
            workspace = mSession.workspace(displayId);
            mSession.close(displayId);
        }
        DesktopSelfTestRunState.noteDesktopSessionClosed(policy, displayId);
        if (policy.persistWorkspace) {
            MagicDeskRuntime.preserveDesktopTasks(displayId);
        }
        final AtomicInteger remainingCloseParts = new AtomicInteger(2);
        final Runnable closePartFinished = () -> {
            if (remainingCloseParts.decrementAndGet() == 0
                    && completion != null) {
                completion.run();
            }
        };
        final Runnable closeHost = () -> {
            try {
                if (activity != null) {
                    try {
                        activity.releaseDesktopUiWindows();
                    } finally {
                        if (!activity.isFinishing()) {
                            activity.finishAndRemoveTask();
                        }
                    }
                }
            } finally {
                MagicDeskRuntime.refreshDesktopTasks();
                if (displayId == Display.DEFAULT_DISPLAY) {
                    MagicDeskRuntime.scheduleLocalDesktopCleanup();
                }
                closePartFinished.run();
            }
        };
        MagicDeskRuntime.releaseDesktopWorkspace(workspace, () -> {
            TaskCommandQueue.execute(() -> {
                try {
                    flushWindowSessionState(workspace);
                } finally {
                    closePartFinished.run();
                }
            });
            if (Looper.myLooper() == Looper.getMainLooper()) {
                closeHost.run();
            } else {
                mMainHandler.post(closeHost);
            }
        });
    }

    private static void flushWindowSessionState(final DesktopWorkspaceRuntime workspace) {
        if (!AppWindowStateStore.endSession(workspace)) {
            Log.w(TAG, "Could not flush desktop window session state");
        }
    }

    DesktopSessionSnapshot sessionSnapshot(final int displayId) {
        synchronized (mHostLock) {
            reconcileSessionHostLocked(displayId);
            return mSession.snapshot(displayId);
        }
    }

    List<DesktopSessionSnapshot> sessionSnapshots() {
        synchronized (mHostLock) {
            for (final DesktopSessionSnapshot snapshot : mSession.snapshots()) {
                if (snapshot.target() != null) {
                    reconcileSessionHostLocked(snapshot.target().workspaceDisplayId);
                }
            }
            return mSession.snapshots();
        }
    }

    DesktopWorkspaceRuntime workspace(final int displayId) {
        synchronized (mHostLock) {
            return mSession.workspace(displayId);
        }
    }

    void noteDesktopTarget(final DesktopDisplayTarget target) {
        noteDesktopTarget(target, DesktopSessionPolicy.USER);
    }

    void noteDesktopTarget(
            final DesktopDisplayTarget target,
            final DesktopSessionPolicy policy) {
        if (target == null) {
            return;
        }
        synchronized (mHostLock) {
            mSession.noteTarget(target, policy);
        }
        recordSession("target_selected", target.workspaceDisplayId, -1);
    }

    void clearDesktopTarget(final DesktopDisplayTarget target) {
        synchronized (mHostLock) {
            final DesktopSessionSnapshot session = mSession.snapshot(target == null ? -1 : target.workspaceDisplayId);
            if (target == null || session.target() == null || !session.target().sameBinding(target)) {
                return;
            }
            // Rollback must retire a host that appeared just before launch failed.
            // Clearing only its binding would orphan both the UI and observer.
            closeDesktopWorkspace(target.workspaceDisplayId, null);
        }
        recordSession("target_cleared", target.workspaceDisplayId, -1);
    }

    DesktopViewport getDesktopViewport(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(displayId, false);
        if (activity == null || activity.getCurrentDisplayId() != displayId) {
            return null;
        }
        return activity.getDesktopViewport();
    }

    Rect getDesktopWorkAreaBounds(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(displayId, false);
        if (activity == null || activity.getCurrentDisplayId() != displayId) {
            return null;
        }
        final DesktopViewport viewport = activity.getDesktopViewport();
        if (viewport == null) {
            return null;
        }
        return activity.isTaskbarAutoHideEnabled()
                ? viewport.contentBounds()
                : viewport.workAreaBounds(activity.getTaskbarHeight());
    }

    Rect getDesktopTaskbarBounds(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(displayId, false);
        if (activity == null || activity.getCurrentDisplayId() != displayId) {
            return null;
        }
        final DesktopTaskbarHost taskbarHost = activity.taskbarHost();
        return taskbarHost == null
                ? activity.getTaskbarBounds() : taskbarHost.appliedBounds();
    }

    boolean showStart(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(displayId, true);
        if (activity == null) {
            return false;
        }
        postToHost(activity, activity::showStartFromRuntime);
        return true;
    }

    boolean launchDesktopShortcut(
            final DesktopApplicationShortcut shortcut,
            final DesktopLaunchArguments arguments,
            final String desktopFilePath,
            final int displayId) {
        final DesktopShellActivity activity = usableDesktop(displayId, false);
        return activity != null
                && activity.getCurrentDisplayId() == displayId
                && activity.launchDesktopShortcut(
                        shortcut, arguments, desktopFilePath);
    }

    boolean launchDesktopWebShortcut(
            final DesktopWebShortcut shortcut,
            final int displayId) {
        final DesktopShellActivity activity = usableDesktop(displayId, false);
        return activity != null
                && activity.getCurrentDisplayId() == displayId
                && activity.launchDesktopWebShortcut(shortcut);
    }

    boolean launchAutomationRequest(
            final DesktopLaunchRequest request,
            final int displayId) {
        final DesktopShellActivity activity = usableDesktop(displayId, false);
        if (activity == null || request == null
                || activity.getCurrentDisplayId() != displayId) {
            return false;
        }
        final boolean[] launched = new boolean[1];
        final CountDownLatch ready = new CountDownLatch(1);
        mMainHandler.post(() -> {
            if (isCurrentHost(activity)) {
                launched[0] = activity.launchAutomationRequest(request);
            }
            ready.countDown();
        });
        return await(ready) && launched[0];
    }

    DesktopActivityLaunchResult launchAutomationRequestObserved(
            final DesktopLaunchRequest request,
            final int displayId,
            final long timeoutMillis) {
        final DesktopShellActivity activity = usableDesktop(displayId, false);
        if (activity == null || request == null
                || activity.getCurrentDisplayId() != displayId) {
            return DesktopActivityLaunchResult.failed(
                    "desktop host is unavailable");
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return DesktopActivityLaunchResult.failed(
                    "observed launch cannot block the UI thread");
        }
        final DesktopActivityLaunchResult.Awaiter completion =
                new DesktopActivityLaunchResult.Awaiter();
        mMainHandler.post(() -> {
            if (!isCurrentHost(activity)
                    || activity.getCurrentDisplayId() != displayId) {
                completion.onComplete(DesktopActivityLaunchResult.failed(
                        "desktop host became unavailable"));
                return;
            }
            activity.launchAutomationRequest(request, completion);
        });
        return completion.await(timeoutMillis);
    }

    boolean openFilesAt(final String path, final int displayId) {
        final DesktopShellActivity activity = usableDesktop(displayId, false);
        if (activity == null || path == null
                || activity.getCurrentDisplayId() != displayId) {
            return false;
        }
        postToHost(activity, () -> activity.openFilesAt(path));
        return true;
    }

    boolean launchApplication(
            final AppIdentity application,
            final AppLaunchTarget target,
            final DesktopLaunchPresentation presentation,
            final int displayId) {
        final DesktopShellActivity activity = usableDesktop(displayId, false);
        if (activity == null
                || application == null
                || application.profileSerialNumber != activity.appProfile().serialNumber
                || target == null
                || !application.packageName.equals(target.packageName)
                || presentation == null
                || activity.getCurrentDisplayId() != displayId) {
            return false;
        }
        final boolean[] launched = new boolean[1];
        final CountDownLatch ready = new CountDownLatch(1);
        mMainHandler.post(() -> {
            if (isCurrentHost(activity) && !activity.isActivityUnavailable()) {
                final AppItem app = activity.findOrLoadApp(
                        activity.getLauncherApps(), application, target);
                if (app != null) {
                    activity.launchForPresentation(
                            app, presentation, null, null);
                    launched[0] = true;
                }
            }
            ready.countDown();
        });
        return await(ready) && launched[0];
    }

    DesktopActivityLaunchResult launchApplicationObserved(
            final AppIdentity application,
            final AppLaunchTarget target,
            final DesktopLaunchPresentation presentation,
            final int displayId,
            final long timeoutMillis) {
        final DesktopShellActivity activity = usableDesktop(displayId, false);
        if (activity == null
                || application == null
                || application.profileSerialNumber != activity.appProfile().serialNumber
                || target == null
                || !application.packageName.equals(target.packageName)
                || presentation == null
                || activity.getCurrentDisplayId() != displayId) {
            return DesktopActivityLaunchResult.failed(
                    "desktop host is unavailable");
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return DesktopActivityLaunchResult.failed(
                    "observed launch cannot block the UI thread");
        }
        final DesktopActivityLaunchResult.Awaiter completion =
                new DesktopActivityLaunchResult.Awaiter();
        mMainHandler.post(() -> {
            if (!isCurrentHost(activity)
                    || activity.getCurrentDisplayId() != displayId) {
                completion.onComplete(DesktopActivityLaunchResult.failed(
                        "desktop host became unavailable"));
                return;
            }
            final AppItem app = activity.findOrLoadApp(
                    activity.getLauncherApps(), application, target);
            if (app == null) {
                completion.onComplete(DesktopActivityLaunchResult.failed(
                        "application launcher is unavailable"));
                return;
            }
            activity.launchForPresentation(
                    app,
                    presentation,
                    null,
                    completion);
        });
        return completion.await(timeoutMillis);
    }

    DesktopActivityLaunchResult invokeAppActionObserved(
            final AppIdentity application,
            final AppLaunchTarget target,
            final String actionId,
            final DesktopLaunchPresentation presentation,
            final int displayId,
            final long timeoutMillis) {
        try {
            AndroidIntegrationGateway.requireShortcutPresentation(presentation);
        } catch (IllegalArgumentException error) {
            return DesktopActivityLaunchResult.failed(error.getMessage());
        }
        final DesktopShellActivity activity = usableDesktop(displayId, false);
        if (activity == null || application == null
                || application.profileSerialNumber != activity.appProfile().serialNumber
                || target == null
                || !application.packageName.equals(target.packageName)
                || actionId == null || actionId.isEmpty()
                || activity.getCurrentDisplayId() != displayId) {
            return DesktopActivityLaunchResult.failed(
                    "desktop host is unavailable");
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return DesktopActivityLaunchResult.failed(
                    "observed launch cannot block the UI thread");
        }
        final List<AppShortcutAction> shortcuts;
        try {
            shortcuts = new AppShortcutRepository(activity).loadAll(application, target);
        } catch (RuntimeException error) {
            Log.w(TAG, "Cannot load published application shortcuts", error);
            return DesktopActivityLaunchResult.failed(ShellAccess.usefulMessage(error));
        }
        final DesktopActivityLaunchResult.Awaiter completion =
                new DesktopActivityLaunchResult.Awaiter();
        mMainHandler.post(() -> {
            if (!isCurrentHost(activity)
                    || activity.getCurrentDisplayId() != displayId) {
                completion.onComplete(DesktopActivityLaunchResult.failed(
                        "desktop host became unavailable"));
                return;
            }
            final AppItem app = activity.findOrLoadApp(
                    activity.getLauncherApps(), application, target);
            if (app == null) {
                completion.onComplete(DesktopActivityLaunchResult.failed(
                        "application launcher is unavailable"));
                return;
            }
            for (final AppShortcutAction action : shortcuts) {
                if (actionId.equals(action.id)) {
                    activity.launchShortcut(
                            app,
                            action,
                            presentation.mode,
                            completion);
                    return;
                }
            }
            completion.onComplete(DesktopActivityLaunchResult.failed(
                    "published application shortcut is unavailable"));
        });
        return completion.await(timeoutMillis);
    }


    void showTransientStatus(
            final String message,
            final boolean longDuration) {
        final DesktopShellActivity activity = usableDesktop(MagicDeskRuntime.inputDisplayId(), false);
        if (activity == null) {
            return;
        }
        postToHost(activity, () -> {
            activity.setStatus(message);
            Toast.makeText(
                    activity,
                    message,
                    longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT)
                    .show();
        });
    }

    void refreshDesktopControls() {
        for (final DesktopSessionSnapshot workspace : sessionSnapshots()) {
            final DesktopShellActivity activity = usableDesktop(workspace.activeWorkspaceDisplayId(), false);
            if (activity != null) { postToHost(activity, activity::updateDesktopControls); }
        }
    }

    boolean refreshDesktopInputFocus(
            final int displayId,
            final int focusedTaskId) {
        return refreshDesktopInputFocus(displayId, focusedTaskId, null);
    }

    boolean refreshDesktopInputFocus(
            final int displayId,
            final int focusedTaskId,
            final Runnable completion) {
        final DesktopShellActivity activity = usableDesktop(displayId, false);
        if (activity == null
                || activity.getCurrentDisplayId() != displayId) {
            if (completion != null) {
                completion.run();
            }
            return false;
        }
        activity.runOnUiThread(() -> {
            if (!isCurrentHost(activity)) {
                if (completion != null) { completion.run(); }
                return;
            }
            activity.setDesktopWindowFocusable(
                    focusedTaskId == activity.getTaskId());
            activity.refreshDesktopInputFocus(completion);
        });
        return true;
    }

    boolean restoreLastVisibleWindows(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(displayId, false);
        if (activity == null) {
            return false;
        }
        postToHost(activity, activity::restoreLastVisibleWindows);
        return true;
    }

    boolean toggleDesktopWorkspace(final int displayId) {
        return toggleDesktopWorkspace(displayId, null);
    }

    boolean toggleDesktopWorkspace(
            final int displayId,
            final TaskRepository.ActionCallback callback) {
        final DesktopShellActivity activity = usableDesktop(displayId, false);
        if (activity == null) {
            return false;
        }
        activity.runOnUiThread(() -> {
            if (!isCurrentHost(activity)) {
                completeTaskAction(
                        callback, false, "desktop UI is unavailable");
                return;
            }
            activity.hideAllPanels();
            activity.setTaskbarVisible(true);
            toggleShowDesktopWorkspaceOnDisplay(displayId, callback);
        });
        return true;
    }

    boolean recreateShellOnDisplay(final int displayId) {
        final DesktopShellActivity activity;
        synchronized (mHostLock) {
            final DesktopWorkspaceRuntime workspace = mSession.workspace(displayId);
            activity = workspace == null ? null : workspace.host();
        }
        if (!isCurrentHost(activity)
                || activity.getCurrentDisplayId() != displayId) {
            return false;
        }
        mMainHandler.post(() -> {
            if (isCurrentHost(activity) && !activity.isActivityUnavailable()) {
                activity.recreate();
            }
        });
        return true;
    }

    void setSystemDialogVisible(
            final int displayId,
            final boolean visible) {
        final DesktopShellActivity activity = usableDesktop(displayId, false);
        if (!isCurrentHost(activity)
                || activity.getCurrentDisplayId() != displayId) {
            return;
        }
        mMainHandler.post(() -> {
            if (isCurrentHost(activity)
                    && activity.getCurrentDisplayId() == displayId) {
                activity.setSystemDialogVisible(visible);
            }
        });
    }

    boolean advanceAltTab(final int displayId, final boolean reverse) {
        final DesktopShellActivity activity = usableDesktop(displayId, true);
        if (activity == null) {
            return false;
        }
        postToHost(activity, () -> activity.advanceAltTab(reverse));
        return true;
    }

    boolean finishAltTab(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(displayId, false);
        if (activity == null) {
            return false;
        }
        postToHost(activity, activity::finishAltTab);
        return true;
    }

    boolean cancelAltTab(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(displayId, false);
        if (activity == null) {
            return false;
        }
        postToHost(activity, activity::cancelAltTabFromRuntime);
        return true;
    }

    boolean toggleShortcutHelp(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(displayId, true);
        if (activity == null) {
            return false;
        }
        postToHost(activity, activity::toggleShortcutHelp);
        return true;
    }

    boolean toggleNotificationCenter(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(displayId, true);
        if (activity == null) {
            return false;
        }
        postToHost(activity, activity.notifications()::toggle);
        return true;
    }

    boolean toggleSystemPanel(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(displayId, true);
        if (activity == null) {
            return false;
        }
        postToHost(activity, activity::toggleSystemPanel);
        return true;
    }

    boolean openSettings(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(displayId, true);
        if (activity == null) {
            return false;
        }
        postToHost(activity, activity::openSettings);
        return true;
    }

    boolean openApplicationSettings(final int displayId, final AppIdentity application) {
        final DesktopShellActivity activity = usableDesktop(displayId, true);
        if (activity == null) {
            return false;
        }
        postToHost(activity, () ->
                activity.openApplicationSettings(application));
        return true;
    }

    boolean openBuiltin(final int displayId, final String builtin) {
        final DesktopShellActivity activity = usableDesktop(displayId, true);
        if (activity == null || builtin == null) {
            return false;
        }
        final Runnable action;
        switch (builtin) {
            case "files":
                action = activity::openFiles;
                break;
            case "console":
                action = activity::openConsole;
                break;
            case "task_manager":
                action = activity::openTaskManager;
                break;
            case "settings":
                action = activity::openSettings;
                break;
            case "app_profiles":
                action = () -> activity.openApplicationSettings(null);
                break;
            case "diagnostics":
                action = activity::openDiagnostics;
                break;
            case "activity_explorer":
                action = activity::openActivityExplorer;
                break;
            default:
                return false;
        }
        postToHost(activity, action);
        return true;
    }

    boolean openConsole(
            final int displayId,
            final String directory,
            final String command,
            final String terminalId,
            final DesktopExecBackend backend) {
        final DesktopShellActivity activity = usableDesktop(displayId, true);
        if (activity == null) {
            return false;
        }
        postToHost(activity, () -> activity.openConsole(
                directory, command, terminalId, backend));
        return true;
    }

    void refreshSettings() {
        for (final DesktopSessionSnapshot workspace : sessionSnapshots()) {
            final DesktopShellActivity activity = usableDesktop(workspace.activeWorkspaceDisplayId(), false);
            if (activity != null) { postToHost(activity, activity::refreshSettings); }
        }
    }

    boolean isDesktopReadyOnDisplay(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(displayId, false);
        return activity != null
                && activity.getCurrentDisplayId() == displayId
                && activity.isDesktopHostReady();
    }

    boolean isDesktopWallpaperRendered(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(displayId, false);
        return activity != null
                && activity.getCurrentDisplayId() == displayId
                && activity.isDesktopWallpaperRendered();
    }

    boolean isUsingFallbackDesktopWallpaper(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(displayId, false);
        return activity != null
                && activity.getCurrentDisplayId() == displayId
                && activity.isUsingFallbackDesktopWallpaper();
    }

    int getDesktopHostIdentity(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(displayId, false);
        return activity != null
                        && activity.getCurrentDisplayId() == displayId
                ? System.identityHashCode(activity) : 0;
    }

    boolean isDesktopWindowFocused(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(displayId, false);
        return activity != null
                && activity.getCurrentDisplayId() == displayId
                && activity.hasWindowFocus();
    }

    boolean isTaskbarVisibleOnDisplay(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(displayId, false);
        return activity != null
                && activity.getCurrentDisplayId() == displayId
                && activity.isTaskbarVisible();
    }

    DesktopUiSnapshot getAutomationUiSnapshot(final int displayId) {
        final DesktopShellActivity activity = usableDesktop(displayId, false);
        if (activity == null
                || activity.getCurrentDisplayId() != displayId) {
            return DesktopUiSnapshot.UNAVAILABLE;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return activity.getAutomationUiSnapshot();
        }
        final DesktopUiSnapshot[] result = new DesktopUiSnapshot[1];
        final CountDownLatch ready = new CountDownLatch(1);
        mMainHandler.post(() -> {
            if (isCurrentHost(activity)
                    && activity.getCurrentDisplayId() == displayId) {
                result[0] = activity.getAutomationUiSnapshot();
            }
            ready.countDown();
        });
        try {
            if (!ready.await(2L, TimeUnit.SECONDS)) {
                return DesktopUiSnapshot.UNAVAILABLE;
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return DesktopUiSnapshot.UNAVAILABLE;
        }
        return result[0] == null
                ? DesktopUiSnapshot.UNAVAILABLE : result[0];
    }

    DesktopAutomationUiRegistry.Snapshot getAutomationUiElements(
            final int displayId, final String query, final boolean includeHidden) {
        return readAutomationUi(displayId,
                registry -> registry.snapshot(displayId, query, includeHidden),
                DesktopAutomationUiRegistry.Snapshot.UNAVAILABLE);
    }

    DesktopAutomationUiRegistry.ActionResult invokeAutomationUiAction(
            final int displayId, final String elementId, final String action) {
        return readAutomationUi(displayId,
                registry -> registry.invoke(elementId, action),
                new DesktopAutomationUiRegistry.ActionResult(
                        false, "UI surface unavailable or action timed out", null));
    }

    private interface AutomationUiCall<T> {
        T run(DesktopAutomationUiRegistry registry) throws org.json.JSONException;
    }

    private <T> T readAutomationUi(
            final int displayId, final AutomationUiCall<T> call, final T unavailable) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return callAutomationUi(displayId, call, unavailable);
        }
        final java.util.concurrent.atomic.AtomicReference<T> result =
                new java.util.concurrent.atomic.AtomicReference<>(unavailable);
        final CountDownLatch ready = new CountDownLatch(1);
        mMainHandler.post(() -> {
            try {
                result.set(callAutomationUi(displayId, call, unavailable));
            } finally {
                ready.countDown();
            }
        });
        return await(ready) ? result.get() : unavailable;
    }

    private <T> T callAutomationUi(
            final int displayId, final AutomationUiCall<T> call, final T unavailable) {
        final DesktopAutomationUiRegistry registry = automationUi(displayId);
        if (registry == null) {
            return unavailable;
        }
        try {
            return call.run(registry);
        } catch (org.json.JSONException error) {
            Log.w(TAG, "could not access automation UI", error);
            return unavailable;
        }
    }

    private boolean toggleShowDesktopWorkspaceOnDisplay(
            final int displayId,
            final TaskRepository.ActionCallback callback) {
        final DesktopShellActivity activity = usableDesktop(displayId, false);
        if (activity == null
                || activity.getCurrentDisplayId() != displayId) {
            completeTaskAction(callback, false, "desktop UI is unavailable");
            return false;
        }
        final int taskId = activity.getTaskId();
        MagicDeskRuntime.toggleShowDesktopWorkspace(
                displayId,
                taskId,
                result -> {
                    if (!result.success) {
                        Log.w(TAG, "Could not toggle desktop workspace task="
                                + taskId + " display=" + displayId
                                + " result=" + result.message);
                    }
                    completeTaskAction(
                            callback, result.success, result.message);
                });
        return true;
    }

    private static void completeTaskAction(
            final TaskRepository.ActionCallback callback,
            final boolean success,
            final String message) {
        if (callback != null) {
            callback.onComplete(new TaskRepository.ActionResult(
                    success, message));
        }
    }

    void prepareTaskFocus(final int displayId, final int taskId) {
        final DesktopShellActivity activity = usableDesktop(displayId, false);
        if (activity != null
                && activity.getCurrentDisplayId() == displayId) {
            activity.setDesktopWindowFocusable(
                    taskId == activity.getTaskId());
        }
    }

    void syncTaskbarWithSnapshot(
            final int displayId,
            final TaskRepository.Snapshot snapshot) {
        final DesktopShellActivity activity = usableDesktop(displayId, false);
        if (activity == null || snapshot == null || !snapshot.available
                || activity.getCurrentDisplayId() != displayId) {
            return;
        }
        postToHost(activity,
                () -> activity.syncTaskbarWithSnapshot(snapshot));
    }

    private DesktopShellActivity usableDesktop(final int displayId, final boolean requirePanels) {
        synchronized (mHostLock) {
            return usableDesktopLocked(displayId, requirePanels);
        }
    }

    private DesktopShellActivity usableDesktopLocked(
            final int displayId, final boolean requirePanels) {
        final DesktopShellActivity activity = reconcileSessionHostLocked(displayId);
        if (!isUsable(activity) || activity.getCurrentDisplayId() != displayId
                || !activity.isDesktopShell()
                || (requirePanels && activity.panels() == null)) {
            return null;
        }
        return activity;
    }

    private DesktopShellActivity reconcileSessionHostLocked(final int displayId) {
        final DesktopWorkspaceRuntime workspace = mSession.workspace(displayId);
        final DesktopShellActivity activity = workspace == null ? null : workspace.host();
        if (!isUsable(activity) || !activity.isDesktopShell()) {
            final DesktopSessionSnapshot snapshot = mSession.snapshot(displayId);
            if (snapshot.hasHost()) {
                mSession.unregisterHost(
                        snapshot.activeWorkspaceDisplayId(), true);
            }
            return null;
        }
        final DesktopSessionSnapshot snapshot = mSession.snapshot(displayId);
        if (snapshot.activeWorkspaceDisplayId() != activity.getCurrentDisplayId()
                || snapshot.hostTaskId() != activity.getTaskId()) {
            recordSession(
                    "host_identity_mismatch",
                    activity.getCurrentDisplayId(),
                    activity.getTaskId());
            return null;
        }
        return activity;
    }

    private boolean isCurrentHost(final DesktopShellActivity activity) {
        if (!isUsable(activity)) { return false; }
        synchronized (mHostLock) {
            final DesktopWorkspaceRuntime workspace = mSession.workspace(activity.getCurrentDisplayId());
            return workspace != null && workspace.host() == activity;
        }
    }

    private void postToHost(final DesktopShellActivity activity, final Runnable action) {
        activity.runOnUiThread(() -> {
            if (isCurrentHost(activity)) { action.run(); }
        });
    }

    private static boolean isUsable(final DesktopShellActivity activity) {
        return activity != null
                && !activity.isFinishing()
                && !activity.isDestroyed();
    }

    private static boolean await(final CountDownLatch ready) {
        return await(ready, 2L);
    }

    private static boolean await(
            final CountDownLatch ready,
            final long timeoutSeconds) {
        try {
            return ready.await(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void recordSession(
            final String operation,
            final int displayId,
            final int taskId) {
        try {
            final org.json.JSONObject data = new org.json.JSONObject()
                    .put("displayId", displayId);
            if (taskId >= 0) {
                data.put("taskId", taskId);
            }
            DesktopAutomationEventJournal.record(
                    "session",
                    operation,
                    true,
                    "display=" + displayId,
                    data);
        } catch (org.json.JSONException ignored) {
            DesktopAutomationEventJournal.record(
                    "session", operation, true, "display=" + displayId);
        }
    }
}

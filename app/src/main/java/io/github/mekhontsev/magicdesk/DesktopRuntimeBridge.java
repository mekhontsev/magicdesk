package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;


/** Stable process-local facade for desktop session state and live UI access. */
public final class DesktopRuntimeBridge {
    private static final DesktopSessionRegistry SESSION =
            new DesktopSessionRegistry();
    private static final DesktopUiGateway UI = new DesktopUiGateway(SESSION);

    private DesktopRuntimeBridge() {
    }

    static void registerUiWindow(final android.view.Window window,
            final DesktopAutomationUiRegistry registry) {
        UI.registerUiWindow(window, registry);
    }

    static void unregisterUiWindow(final android.view.Window window) {
        UI.unregisterUiWindow(window);
    }

    static boolean canDelegateDesktopHome(final DesktopShellActivity activity) {
        return UI.desktopHomeRecipient(activity) != null;
    }

    static void delegateDesktopHome(final DesktopShellActivity activity) {
        final DesktopShellActivity host = UI.desktopHomeRecipient(activity);
        if (host != null) {
            host.handleLaunchAction(activity.getIntent());
        }
    }

    static boolean registerDesktop(
            final DesktopShellActivity activity,
            final DesktopDisplayTarget target,
            final DesktopSessionPolicy policy) {
        return UI.registerDesktop(activity, target, policy);
    }

    static void unregister(final DesktopShellActivity activity) {
        UI.unregister(activity);
    }

    static void closeDesktopWorkspace(final int displayId) {
        UI.closeDesktopWorkspace(displayId, null);
    }

    static void closeDesktopWorkspace(
            final int displayId,
            final Runnable completion) {
        UI.closeDesktopWorkspace(displayId, completion);
    }

    static java.util.List<DesktopSessionSnapshot> getWorkspaces() {
        return UI.sessionSnapshots();
    }

    static java.util.List<DesktopDisplayTarget> workspaceTargets() {
        return getWorkspaces().stream().map(DesktopSessionSnapshot::target)
                .filter(java.util.Objects::nonNull).toList();
    }

    static boolean hasWorkspaces() {
        return !getWorkspaces().isEmpty();
    }

    static java.util.Set<Integer> workspaceDisplayIds() {
        return getWorkspaces().stream().filter(workspace -> workspace.target() != null)
                .map(workspace -> workspace.target().workspaceDisplayId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    static DesktopSessionSnapshot getSessionSnapshot(final int displayId) {
        return UI.sessionSnapshot(displayId);
    }

    static boolean hasWorkspace(final int displayId) {
        return getSessionSnapshot(displayId).target() != null;
    }

    /** Only an unambiguous command boundary may infer a destination. */
    static int requireSingleDesktopDisplay() {
        final java.util.List<DesktopSessionSnapshot> workspaces = getWorkspaces();
        if (workspaces.size() != 1) {
            throw new IllegalStateException(workspaces.isEmpty()
                    ? "no Desktop is running" : "several Desktops are running; specify displayId");
        }
        return workspaces.get(0).target().workspaceDisplayId;
    }

    static DesktopWorkspaceRuntime getWorkspaceRuntime(final int displayId) {
        return UI.workspace(displayId);
    }

    static void noteDesktopTarget(final DesktopDisplayTarget target) {
        UI.noteDesktopTarget(target);
    }

    static void noteDesktopTarget(
            final DesktopDisplayTarget target,
            final DesktopSessionPolicy policy) {
        UI.noteDesktopTarget(target, policy);
    }

    static void clearDesktopTarget(final DesktopDisplayTarget target) {
        UI.clearDesktopTarget(target);
    }

    static DesktopDisplayTarget getDesktopTarget(final int displayId) {
        return getSessionSnapshot(displayId).target();
    }

    static boolean isLocalDesktopActiveOrStarting() {
        return hasWorkspace(android.view.Display.DEFAULT_DISPLAY);
    }

    static DesktopViewport getDesktopViewport(final int displayId) {
        return UI.getDesktopViewport(displayId);
    }

    static Rect getDesktopWorkAreaBounds(final int displayId) {
        return UI.getDesktopWorkAreaBounds(displayId);
    }

    static Rect getDesktopTaskbarBounds(final int displayId) {
        return UI.getDesktopTaskbarBounds(displayId);
    }

    static boolean showStart(final int displayId) {
        return UI.showStart(displayId);
    }

    static boolean launchDesktopShortcut(
            final DesktopApplicationShortcut shortcut,
            final int displayId) {
        return launchDesktopShortcut(
                shortcut,
                DesktopLaunchArguments.empty(),
                "",
                displayId);
    }

    static boolean launchDesktopShortcut(
            final DesktopApplicationShortcut shortcut,
            final DesktopLaunchArguments arguments,
            final String desktopFilePath,
            final int displayId) {
        return UI.launchDesktopShortcut(
                shortcut, arguments, desktopFilePath, displayId);
    }

    static boolean launchDesktopWebShortcut(
            final DesktopWebShortcut shortcut,
            final int displayId) {
        return UI.launchDesktopWebShortcut(shortcut, displayId);
    }

    static boolean launchAutomationRequest(
            final DesktopLaunchRequest request,
            final int displayId) {
        return UI.launchAutomationRequest(request, displayId);
    }

    static DesktopActivityLaunchResult launchAutomationRequestObserved(
            final DesktopLaunchRequest request,
            final int displayId,
            final long timeoutMillis) {
        return UI.launchAutomationRequestObserved(
                request, displayId, timeoutMillis);
    }

    static boolean openFilesAt(final String path, final int displayId) {
        return UI.openFilesAt(path, displayId);
    }

    static boolean launchApplication(
            final AppIdentity application,
            final AppLaunchTarget target,
            final DesktopLaunchPresentation presentation,
            final int displayId) {
        return UI.launchApplication(
                application, target, presentation, displayId);
    }

    static DesktopActivityLaunchResult launchApplicationObserved(
            final AppIdentity application,
            final AppLaunchTarget target,
            final DesktopLaunchPresentation presentation,
            final int displayId,
            final long timeoutMillis) {
        return UI.launchApplicationObserved(
                application, target,
                presentation,
                displayId,
                timeoutMillis);
    }

    static DesktopActivityLaunchResult invokeAppActionObserved(
            final AppIdentity application,
            final AppLaunchTarget target,
            final String actionId,
            final DesktopLaunchPresentation presentation,
            final int displayId,
            final long timeoutMillis) {
        return UI.invokeAppActionObserved(
                application, target,
                actionId,
                presentation,
                displayId,
                timeoutMillis);
    }


    static void showTransientStatus(
            final String message,
            final boolean longDuration) {
        UI.showTransientStatus(message, longDuration);
    }

    public static void refreshDesktopControls() {
        UI.refreshDesktopControls();
    }

    static boolean refreshDesktopInputFocus(
            final int displayId,
            final int focusedTaskId) {
        return UI.refreshDesktopInputFocus(displayId, focusedTaskId);
    }

    static boolean refreshDesktopInputFocus(
            final int displayId,
            final int focusedTaskId,
            final Runnable completion) {
        return UI.refreshDesktopInputFocus(
                displayId, focusedTaskId, completion);
    }

    static void setSystemDialogVisible(
            final int displayId,
            final boolean visible) {
        UI.setSystemDialogVisible(displayId, visible);
    }

    static boolean restoreLastVisibleWindows(final int displayId) {
        return UI.restoreLastVisibleWindows(displayId);
    }

    static boolean toggleDesktopWorkspace(final int displayId) {
        return UI.toggleDesktopWorkspace(displayId);
    }

    static boolean toggleDesktopWorkspace(
            final int displayId,
            final TaskRepository.ActionCallback callback) {
        return UI.toggleDesktopWorkspace(displayId, callback);
    }

    static boolean recreateShellOnDisplay(final int displayId) {
        return UI.recreateShellOnDisplay(displayId);
    }

    static boolean advanceAltTab(final int displayId, final boolean reverse) {
        return UI.advanceAltTab(displayId, reverse);
    }

    static boolean finishAltTab(final int displayId) {
        return UI.finishAltTab(displayId);
    }

    static boolean cancelAltTab(final int displayId) {
        return UI.cancelAltTab(displayId);
    }

    static boolean toggleShortcutHelp(final int displayId) {
        return UI.toggleShortcutHelp(displayId);
    }

    static boolean toggleNotificationCenter(final int displayId) {
        return UI.toggleNotificationCenter(displayId);
    }

    static boolean toggleSystemPanel(final int displayId) {
        return UI.toggleSystemPanel(displayId);
    }

    static boolean openSettings(final int displayId) {
        return UI.openSettings(displayId);
    }

    static boolean openApplicationSettings(final int displayId, final AppIdentity application) {
        return UI.openApplicationSettings(displayId, application);
    }

    static boolean openBuiltin(final int displayId, final String builtin) {
        return UI.openBuiltin(displayId, builtin);
    }

    static boolean openConsole(
            final int displayId,
            final String directory,
            final String command,
            final String terminalId,
            final DesktopExecBackend backend) {
        return UI.openConsole(displayId, directory, command, terminalId, backend);
    }

    static void refreshSettings() {
        UI.refreshSettings();
    }

    static boolean isDesktopReadyOnDisplay(final int displayId) {
        return UI.isDesktopReadyOnDisplay(displayId);
    }

    static boolean isDesktopWallpaperRendered(final int displayId) {
        return UI.isDesktopWallpaperRendered(displayId);
    }

    static boolean isUsingFallbackDesktopWallpaper(final int displayId) {
        return UI.isUsingFallbackDesktopWallpaper(displayId);
    }

    static int getDesktopHostIdentity(final int displayId) {
        return UI.getDesktopHostIdentity(displayId);
    }

    static boolean isDesktopWindowFocused(final int displayId) {
        return UI.isDesktopWindowFocused(displayId);
    }

    static boolean isTaskbarVisibleOnDisplay(final int displayId) {
        return UI.isTaskbarVisibleOnDisplay(displayId);
    }

    static DesktopUiSnapshot getAutomationUiSnapshot(final int displayId) {
        return UI.getAutomationUiSnapshot(displayId);
    }

    static DesktopAutomationUiRegistry.Snapshot getAutomationUiElements(
            final int displayId,
            final String query,
            final boolean includeHidden) {
        return UI.getAutomationUiElements(displayId, query, includeHidden);
    }

    static DesktopAutomationUiRegistry.ActionResult invokeAutomationUiAction(
            final int displayId,
            final String elementId,
            final String action) {
        return UI.invokeAutomationUiAction(
                displayId, elementId, action);
    }

    static void prepareTaskFocus(
            final int displayId, final int taskId) {
        UI.prepareTaskFocus(displayId, taskId);
    }

    static void syncTaskbarWithSnapshot(
            final int displayId,
            final TaskRepository.Snapshot snapshot) {
        UI.syncTaskbarWithSnapshot(displayId, snapshot);
    }
}

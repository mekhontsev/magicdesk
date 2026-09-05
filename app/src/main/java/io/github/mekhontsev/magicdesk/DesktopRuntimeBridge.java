package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;


/** Stable process-local facade for desktop session state and live UI access. */
public final class DesktopRuntimeBridge {
    private static final DesktopSessionRegistry SESSION =
            new DesktopSessionRegistry();
    private static final DesktopUiGateway UI = new DesktopUiGateway(SESSION);

    private DesktopRuntimeBridge() {
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

    static void registerPhoneHome(final PhoneHomeActivity activity) {
        UI.registerPhoneHome(activity);
    }

    static void unregisterPhoneHome(final PhoneHomeActivity activity) {
        UI.unregisterPhoneHome(activity);
    }

    static void closeDesktopSession(final int displayId) {
        UI.closeDesktopSession(displayId, null);
    }

    static void closeDesktopSession(
            final int displayId,
            final Runnable completion) {
        UI.closeDesktopSession(displayId, completion);
    }

    static void prepareDesktopSessionRemoval(
            final int displayId,
            final Runnable completion) {
        UI.prepareDesktopSessionRemoval(displayId, completion);
    }

    static void resumeDesktopSessionAfterFailedRemoval(
            final int displayId) {
        UI.resumeDesktopSessionAfterFailedRemoval(displayId);
    }

    public static int getActiveDesktopDisplayId() {
        return getSessionSnapshot().activeDisplayId();
    }

    static DesktopSessionSnapshot getSessionSnapshot() {
        return UI.sessionSnapshot();
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
        return getSessionSnapshot().targetForDisplay(displayId);
    }

    static DesktopDisplayTarget getActiveDesktopTarget() {
        return getSessionSnapshot().target();
    }

    static boolean isLocalDesktopActiveOrStarting() {
        return getSessionSnapshot().isLocalActiveOrStarting();
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

    static boolean showStart() {
        return UI.showStart();
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
            final AppLaunchTarget target,
            final DesktopLaunchPresentation presentation,
            final int displayId) {
        return UI.launchApplication(
                target, presentation, displayId);
    }

    static DesktopActivityLaunchResult launchApplicationObserved(
            final AppLaunchTarget target,
            final DesktopLaunchPresentation presentation,
            final int displayId,
            final long timeoutMillis) {
        return UI.launchApplicationObserved(
                target,
                presentation,
                displayId,
                timeoutMillis);
    }

    static DesktopActivityLaunchResult invokeAppActionObserved(
            final AppLaunchTarget target,
            final String actionId,
            final DesktopLaunchPresentation presentation,
            final int displayId,
            final long timeoutMillis) {
        return UI.invokeAppActionObserved(
                target,
                actionId,
                presentation,
                displayId,
                timeoutMillis);
    }

    static boolean dispatchPanelTextInput(
            final int displayId,
            final int action,
            final String text,
            final int arg1,
            final int arg2,
            final int arg3) {
        return UI.dispatchPanelTextInput(
                displayId, action, text, arg1, arg2, arg3);
    }

    static boolean hasPanelTextInput(final int displayId) {
        return UI.hasPanelTextInput(displayId);
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

    static boolean restoreLastVisibleWindows() {
        return UI.restoreLastVisibleWindows();
    }

    static boolean toggleDesktopWorkspace() {
        return UI.toggleDesktopWorkspace();
    }

    static boolean toggleDesktopWorkspace(
            final TaskRepository.ActionCallback callback) {
        return UI.toggleDesktopWorkspace(callback);
    }

    static boolean recreateShellOnDisplay(final int displayId) {
        return UI.recreateShellOnDisplay(displayId);
    }

    static boolean advanceAltTab(final boolean reverse) {
        return UI.advanceAltTab(reverse);
    }

    static boolean finishAltTab() {
        return UI.finishAltTab();
    }

    static boolean cancelAltTab() {
        return UI.cancelAltTab();
    }

    static boolean toggleShortcutHelp() {
        return UI.toggleShortcutHelp();
    }

    static boolean toggleNotificationCenter() {
        return UI.toggleNotificationCenter();
    }

    static boolean toggleSystemPanel() {
        return UI.toggleSystemPanel();
    }

    static boolean openSettings() {
        return UI.openSettings();
    }

    static boolean openApplicationSettings(final String packageName) {
        return UI.openApplicationSettings(packageName);
    }

    static boolean openBuiltin(final String builtin) {
        return UI.openBuiltin(builtin);
    }

    static boolean openConsole(
            final String directory,
            final String command,
            final String terminalId,
            final DesktopExecBackend backend) {
        return UI.openConsole(directory, command, terminalId, backend);
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

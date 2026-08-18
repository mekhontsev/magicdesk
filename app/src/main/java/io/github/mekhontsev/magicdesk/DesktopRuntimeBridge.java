package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;

/** Stable process-local facade for desktop session state and live UI access. */
public final class DesktopRuntimeBridge {
    private static final DesktopSessionRegistry SESSION =
            new DesktopSessionRegistry();
    private static final DesktopUiGateway UI = new DesktopUiGateway(SESSION);

    private DesktopRuntimeBridge() {
    }

    static void registerShell(final DesktopShellActivity activity) {
        UI.registerShell(activity);
    }

    static void registerDesktop(final DesktopShellActivity activity) {
        UI.registerDesktop(activity);
    }

    static void unregister(final DesktopShellActivity activity) {
        UI.unregister(activity);
    }

    static void closeDesktopSession(final int displayId) {
        UI.closeDesktopSession(displayId);
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

    static boolean showStart() {
        return UI.showStart();
    }

    static boolean dispatchOverlayTextInput(
            final int displayId,
            final int action,
            final String text,
            final int arg1,
            final int arg2,
            final int arg3) {
        return UI.dispatchOverlayTextInput(
                displayId, action, text, arg1, arg2, arg3);
    }

    static boolean hasOverlayTextInput(final int displayId) {
        return UI.hasOverlayTextInput(displayId);
    }

    static void showTransientStatus(
            final String message,
            final boolean longDuration) {
        UI.showTransientStatus(message, longDuration);
    }

    public static void refreshDesktopControls() {
        UI.refreshDesktopControls();
    }

    static boolean refreshDesktopInputFocus(final int displayId) {
        return UI.refreshDesktopInputFocus(displayId);
    }

    static void setDesktopPlaneForeground(
            final int displayId,
            final boolean foreground) {
        UI.setDesktopPlaneForeground(displayId, foreground);
    }

    static boolean restoreLastVisibleWindows() {
        return UI.restoreLastVisibleWindows();
    }

    static boolean toggleDesktopWorkspace() {
        return UI.toggleDesktopWorkspace();
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

    static boolean focusDesktopOnDisplay(final int displayId) {
        return UI.focusDesktopOnDisplay(displayId);
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

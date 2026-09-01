package io.github.mekhontsev.magicdesk;

import android.graphics.Point;

/** Process-local operations supplied while the Android runtime service lives. */
interface MagicDeskRuntimeBackend {
    boolean isAvailable();

    boolean isDesktopRuntimeInitialized();

    void prepareForStop(Runnable completion);

    void releaseDesktopTaskSession(Runnable completion);

    void refreshNotification();

    void setOperationStatus(String status);

    void refreshDesktopTasks();

    void refreshPlatformState();

    void refreshSettings(Runnable completion);

    boolean isSessionWakeLockHeld();

    void reconcileFailedDesktopLaunch(int displayId);

    void scheduleLocalDesktopCleanup();

    boolean isDesktopMouseBridgeReady();

    boolean isFullKeyboardShortcutMode();

    DesktopPointerState getDesktopPointerState(int displayId);

    InputRelayRuntimeDiagnostics.Snapshot captureInputRelayDiagnostics();

    boolean capturePointerPosition();

    void restorePointerPositionOnNextMotion();

    void reactivatePointerOnNextMotion();

    boolean prepareDesktopDisplayRemoval(int displayId);

    void cancelDesktopDisplayRemoval(int displayId);

    Point getDesktopPointerPosition(int displayId);

    boolean updateDesktopPointerPosition(
            int displayId, int x, int y, int action, long downTime);

    boolean activateDesktopPointer(int displayId);

    boolean clickDesktopPointer(int displayId, int button);

    boolean scrollDesktopPointer(int displayId, float amount);

    boolean updateDesktopTextInput(
            int displayId,
            int action,
            String text,
            int arg1,
            int arg2,
            int arg3);

    boolean beginDesktopTextInput(int displayId);

    void endDesktopTextInput(int displayId);

    boolean showStart();

    boolean toggleDesktopWorkspace();

    boolean toggleDesktopWorkspace(TaskRepository.ActionCallback callback);

    boolean restoreLastVisibleWindows();

    boolean advanceAltTab(boolean reverse);

    boolean finishAltTab();

    boolean cancelAltTab();

    boolean toggleShortcutHelp();

    boolean toggleNotificationCenter();

    boolean toggleSystemPanel();

    boolean openSettings();

    DesktopTaskRuntime desktopTasks();

    DesktopTaskParkingRuntime desktopTaskParking();
}

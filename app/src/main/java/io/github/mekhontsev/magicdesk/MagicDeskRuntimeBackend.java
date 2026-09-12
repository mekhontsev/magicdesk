package io.github.mekhontsev.magicdesk;


/** Process-local operations supplied while the Android runtime service lives. */
interface MagicDeskRuntimeBackend {
    boolean isAvailable();

    boolean isDesktopRuntimeInitialized();

    void prepareForStop(Runnable completion);

    void releaseDesktopRuntime();

    void releaseDesktopWorkspace(DesktopWorkspaceRuntime workspace, Runnable completion);

    void refreshNotification();

    void setOperationStatus(String status);

    void refreshDesktopTasks();

    void refreshPlatformState();

    void refreshSettings(Runnable completion);

    boolean isSessionWakeLockHeld();

    void reconcileFailedDesktopLaunch(int displayId);

    void scheduleLocalDesktopCleanup();

    boolean isPointerTransportReady();

    boolean isFullKeyboardShortcutMode();

    DesktopPointerState getPointerState(int displayId);

    DesktopInputDiagnostics.Snapshot captureInputDiagnostics();

    int inputDisplayId();

    int readyInputDisplayId();
    boolean inputTransitioning();
    String inputError();

    void selectInputDisplay(int displayId, TaskRepository.ActionCallback callback);

    void releaseDisplayInput(int displayId, Runnable completion);

    boolean movePointer(int displayId, float deltaX, float deltaY);

    boolean setPointerButtonPressed(
            int displayId, int button, boolean pressed);

    boolean clickPointer(int displayId, int button);

    boolean scrollPointer(int displayId, float amount);


    boolean showStart(final int displayId);

    boolean toggleDesktopWorkspace(final int displayId);

    boolean toggleDesktopWorkspace(int displayId, TaskRepository.ActionCallback callback);

    boolean restoreLastVisibleWindows(final int displayId);

    boolean advanceAltTab(int displayId, boolean reverse);

    boolean finishAltTab(final int displayId);

    boolean cancelAltTab(final int displayId);

    boolean toggleShortcutHelp(final int displayId);

    boolean toggleNotificationCenter(final int displayId);

    boolean toggleSystemPanel(final int displayId);

    boolean openSettings(final int displayId);

    DesktopTaskRuntime desktopTasks();

    DesktopTaskParkingRuntime desktopTaskParking();
}

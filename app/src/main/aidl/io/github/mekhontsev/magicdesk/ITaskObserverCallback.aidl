package io.github.mekhontsev.magicdesk;

oneway interface ITaskObserverCallback {
    void onTasksChanged() = 1;

    void onImmersiveRequest(
        int taskId, boolean requesting, boolean initialSample,
        boolean foreground) = 2;

    void onTaskGone(int taskId) = 3;

    void onWindowingModeChanged(
        int taskId, int previousMode, int currentMode,
        int previousCaptionSourceId,
        boolean backgroundAppFullscreenReleased) = 4;

    void onFocusStackResult(
        long sequence, boolean success, int taskCount, String error) = 5;

    void onObserverError(String error) = 6;

    void onFreeformBoundsChanged(
        int taskId, String stateKey, int displayId,
        int left, int top, int right, int bottom) = 7;

    void onInputFocusRefreshRequired() = 8;

    void onPhoneTaskNormalized(int taskId) = 9;

    void onDesktopTaskAreaForegroundChanged(boolean foreground) = 10;

    void onTaskActivityModeCorrected(
        int taskId, String activityName, String restoredMode) = 11;

    void onDesktopProcessFailure(
        int type, String processName, int pid, int taskId,
        int displayId, int windowingMode, String topActivity,
        String reason) = 12;

    void onDesktopTaskOwnershipChanged(
        int displayId, in int[] taskIds) = 13;

    void onPhoneLauncherEvent(
        int type, String processName, int pid, String reason,
        boolean protectionActivated) = 14;

    void onTaskRequestedOrientationChanged(
        int taskId, int requestedOrientation) = 15;
}

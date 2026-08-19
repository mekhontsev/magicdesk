package io.github.mekhontsev.magicdesk;

oneway interface ITaskObserverCallback {
    void onTasksChanged() = 1;

    void onImmersiveRequest(
        int taskId, boolean requesting, boolean initialSample,
        boolean restoredByObserver) = 2;

    void onTaskGone(int taskId) = 3;

    void onWindowingModeChanged(
        int taskId, int previousMode, int currentMode,
        int previousCaptionSourceId) = 4;

    void onFocusStackResult(
        long sequence, boolean success, int taskCount, String error) = 5;

    void onObserverError(String error) = 6;

    void onFreeformBoundsChanged(
        int taskId, String packageName, int displayId,
        int left, int top, int right, int bottom) = 7;

    void onInputFocusRefreshRequired() = 8;

    void onPhoneTaskNormalized(int taskId) = 9;

    void onDesktopTaskAreaForegroundChanged(boolean foreground) = 10;

    void onWindowedTaskStartupCorrected(
        int taskId, String activityName) = 11;
}

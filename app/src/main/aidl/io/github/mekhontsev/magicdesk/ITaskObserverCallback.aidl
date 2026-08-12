package io.github.mekhontsev.magicdesk;

oneway interface ITaskObserverCallback {
    void onTasksChanged() = 1;

    void onImmersiveRequest(
        int taskId, boolean requesting, boolean initialSample) = 2;

    void onTaskGone(int taskId) = 3;

    void onNativeMaximizeChanged(
        int taskId, boolean enteredFullscreen) = 4;

    void onFocusStackResult(
        long sequence, boolean success, int taskCount, String error) = 5;

    void onObserverError(String error) = 6;

    void onFreeformBoundsChanged(
        int taskId, String packageName, int displayId,
        int left, int top, int right, int bottom) = 7;

    void onInputFocusRefreshRequired() = 8;
}

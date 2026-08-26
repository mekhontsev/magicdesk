package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;

/** Stable shell-facing coordinator for fullscreen topology strategies. */
final class ShellFullscreenTaskArea implements AutoCloseable {
    enum FocusResult {
        NOT_HANDLED,
        SESSION_FOREGROUND,
        FULLSCREEN_FOREGROUND
    }

    private final ShellFullscreenTaskTopology mTopology;

    ShellFullscreenTaskArea(final ShellDesktopTaskOwnership ownership) {
        mTopology = new LegacyFullscreenTaskTopology(ownership);
    }

    synchronized FocusResult focusStack(
            final Object service,
            final int displayId,
            final int[] taskIds) {
        return mTopology.focusStack(service, displayId, taskIds);
    }

    synchronized boolean beginAppFullscreen(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect restoreBounds) {
        return mTopology.beginAppFullscreen(
                service, displayId, taskId, restoreBounds);
    }

    synchronized boolean beginFullscreen(
            final Object service,
            final int displayId,
            final int taskId,
            final boolean refreshCaption) {
        return mTopology.beginFullscreen(
                service, displayId, taskId, refreshCaption);
    }

    synchronized boolean restoreTask(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect bounds) {
        return mTopology.restoreTask(service, displayId, taskId, bounds);
    }

    synchronized boolean closeTask(
            final Object service,
            final int displayId,
            final int taskId) {
        return mTopology.closeTask(service, displayId, taskId);
    }

    synchronized boolean onWindowingModeChanged(
            final int displayId,
            final int taskId,
            final int windowingMode,
            final boolean focused) {
        return mTopology.onWindowingModeChanged(
                displayId, taskId, windowingMode, focused);
    }

    synchronized void onTaskRemoved(final int taskId) {
        mTopology.onTaskRemoved(taskId);
    }

    synchronized void onTaskMovedToFront(
            final int displayId,
            final int taskId) {
        mTopology.onTaskMovedToFront(displayId, taskId);
    }

    synchronized void onTaskStackChanged() {
        mTopology.onTaskStackChanged();
    }

    synchronized void onTaskDisplayChanged(
            final int taskId,
            final int displayId) {
        mTopology.onTaskDisplayChanged(taskId, displayId);
    }

    synchronized void configure(
            final int displayId,
            final DesktopTaskAreaPolicy taskAreaPolicy,
            final int parentFeatureId,
            final Object releaseParentToken) {
        mTopology.configure(
                displayId,
                taskAreaPolicy,
                parentFeatureId,
                releaseParentToken);
    }

    static boolean shouldJoinPreparedArea(
            final DesktopTaskAreaPolicy taskAreaPolicy,
            final boolean areaExists,
            final int managedTaskCount) {
        return LegacyFullscreenTaskTopology.shouldJoinPreparedArea(
                taskAreaPolicy, areaExists, managedTaskCount);
    }

    static boolean shouldUseSessionParent(
            final DesktopTaskAreaPolicy taskAreaPolicy) {
        return LegacyFullscreenTaskTopology.shouldUseSessionParent(
                taskAreaPolicy);
    }

    static boolean shouldReleaseBackgroundAppFullscreen(
            final DesktopTaskAreaPolicy taskAreaPolicy,
            final boolean focused,
            final int windowingMode,
            final boolean appFullscreenTracked,
            final boolean managedAreaMember) {
        return LegacyFullscreenTaskTopology
                .shouldReleaseBackgroundAppFullscreen(
                        taskAreaPolicy,
                        focused,
                        windowingMode,
                        appFullscreenTracked,
                        managedAreaMember);
    }

    @Override
    public synchronized void close() {
        mTopology.close();
    }
}

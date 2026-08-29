package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;

/** Stable shell-facing coordinator for fullscreen topology strategies. */
final class ShellFullscreenTaskArea implements AutoCloseable {
    enum FocusResult {
        NOT_HANDLED,
        SESSION_FOREGROUND,
        FULLSCREEN_FOREGROUND
    }

    private final ShellFullscreenTaskTopology mSessionTopology;
    private final ShellFullscreenTaskTopology mIndependentTopology;
    private ShellFullscreenTaskTopology mTopology;
    private DesktopTaskAreaPolicy mTaskAreaPolicy =
            DesktopTaskAreaPolicy.SESSION;

    ShellFullscreenTaskArea(final ShellDesktopTaskOwnership ownership) {
        mSessionTopology = new SessionFullscreenTaskTopology(ownership);
        mIndependentTopology =
                new IndependentFullscreenTaskTopology(ownership);
        mTopology = mSessionTopology;
    }

    synchronized FocusResult focusStack(
            final Object service,
            final int displayId,
            final int[] taskIds) {
        return mTopology.focusStack(service, displayId, taskIds);
    }

    synchronized boolean concealForShowDesktop(final int displayId) {
        return mTopology.concealForShowDesktop(displayId);
    }

    synchronized boolean usesDirectRootWorkspace() {
        return mTaskAreaPolicy.usesDirectRootWorkspace();
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
            final DesktopTaskAreaPolicy taskAreaPolicy) {
        if (taskAreaPolicy == null) {
            throw new IllegalArgumentException(
                    "fullscreen task area policy is required");
        }
        final ShellFullscreenTaskTopology selected =
                taskAreaPolicy.usesIndependentFullscreenPlanes()
                        ? mIndependentTopology : mSessionTopology;
        if (selected != mTopology) {
            mTopology.close();
            mTopology = selected;
        }
        mTopology.configure(
                displayId,
                taskAreaPolicy);
        mTaskAreaPolicy = taskAreaPolicy;
    }

    @Override
    public synchronized void close() {
        mSessionTopology.close();
        mIndependentTopology.close();
    }
}

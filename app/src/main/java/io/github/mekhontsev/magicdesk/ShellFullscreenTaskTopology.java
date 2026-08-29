package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;

/** Strategy boundary for one desktop session's fullscreen hierarchy. */
interface ShellFullscreenTaskTopology extends AutoCloseable {
    ShellFullscreenTaskArea.FocusResult focusStack(
            Object service, int displayId, int[] taskIds);

    boolean concealForShowDesktop(int displayId);

    boolean beginAppFullscreen(
            Object service, int displayId, int taskId, Rect restoreBounds);

    boolean beginFullscreen(
            Object service, int displayId, int taskId,
            boolean refreshCaption);

    boolean restoreTask(
            Object service, int displayId, int taskId, Rect bounds);

    boolean closeTask(Object service, int displayId, int taskId);

    boolean onWindowingModeChanged(
            int displayId, int taskId, int windowingMode, boolean focused);

    void onTaskRemoved(int taskId);

    void onTaskMovedToFront(int displayId, int taskId);

    void onTaskStackChanged();

    void onTaskDisplayChanged(int taskId, int displayId);

    void configure(
            int displayId,
            DesktopTaskAreaPolicy taskAreaPolicy);

    @Override
    void close();
}

package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;

/** Semantic desktop-window operation independent of its WMShell executor. */
final class DesktopWindowTransitionRequest {
    enum Operation {
        ENTER_FULLSCREEN("enter-fullscreen", false),
        ENTER_APP_FULLSCREEN("enter-app-fullscreen", true),
        RESTORE_FREEFORM("restore-freeform", true),
        CLOSE_FULLSCREEN("close-fullscreen", false),
        CLOSE_FREEFORM("close-freeform", false);

        final String wireName;
        final boolean requiresBounds;

        Operation(final String wireName, final boolean requiresBounds) {
            this.wireName = wireName;
            this.requiresBounds = requiresBounds;
        }
    }

    final Operation operation;
    final int displayId;
    final int taskId;
    private final Rect mBounds;

    private DesktopWindowTransitionRequest(
            final Operation operation,
            final int displayId,
            final int taskId,
            final Rect bounds) {
        if (operation == null || displayId < 0 || taskId < 0) {
            throw new IllegalArgumentException(
                    "valid transition operation, display, and task are required");
        }
        if (operation.requiresBounds != (bounds != null)) {
            throw new IllegalArgumentException(
                    operation.wireName + " bounds mismatch");
        }
        this.operation = operation;
        this.displayId = displayId;
        this.taskId = taskId;
        mBounds = bounds == null ? null : new Rect(bounds);
    }

    static DesktopWindowTransitionRequest enterFullscreen(
            final int displayId,
            final int taskId) {
        return new DesktopWindowTransitionRequest(
                Operation.ENTER_FULLSCREEN, displayId, taskId, null);
    }

    static DesktopWindowTransitionRequest enterAppFullscreen(
            final int displayId,
            final int taskId,
            final Rect restoreBounds) {
        return new DesktopWindowTransitionRequest(
                Operation.ENTER_APP_FULLSCREEN,
                displayId, taskId, restoreBounds);
    }

    static DesktopWindowTransitionRequest restoreFreeform(
            final int displayId,
            final int taskId,
            final Rect targetBounds) {
        return new DesktopWindowTransitionRequest(
                Operation.RESTORE_FREEFORM,
                displayId, taskId, targetBounds);
    }

    static DesktopWindowTransitionRequest closeFullscreen(
            final int displayId,
            final int taskId) {
        return new DesktopWindowTransitionRequest(
                Operation.CLOSE_FULLSCREEN, displayId, taskId, null);
    }

    static DesktopWindowTransitionRequest closeFreeform(
            final int displayId,
            final int taskId) {
        return new DesktopWindowTransitionRequest(
                Operation.CLOSE_FREEFORM, displayId, taskId, null);
    }

    Rect bounds() {
        return mBounds == null ? null : new Rect(mBounds);
    }
}

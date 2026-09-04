package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;

/** Semantic desktop-window operation independent of its WMShell executor. */
final class DesktopWindowTransitionRequest {
    enum Operation {
        ENTER_FULLSCREEN("enter-fullscreen", false),
        ENTER_APP_FULLSCREEN("enter-app-fullscreen", true),
        RESTORE_FREEFORM("restore-freeform", true);

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
    final int densityDpi;
    final String origin;
    private final Rect mBounds;

    private DesktopWindowTransitionRequest(
            final Operation operation,
            final int displayId,
            final int taskId,
            final Rect bounds,
            final int densityDpi,
            final String origin) {
        if (operation == null || displayId < 0 || taskId < 0) {
            throw new IllegalArgumentException(
                    "valid transition operation, display, and task are required");
        }
        if (operation.requiresBounds != (bounds != null)) {
            throw new IllegalArgumentException(
                    operation.wireName + " bounds mismatch");
        }
        if (!DesktopTaskDensity.isValid(densityDpi)
                || densityDpi == DesktopTaskDensity.UNCHANGED) {
            throw new IllegalArgumentException(
                    "transition requires an explicit task density");
        }
        this.operation = operation;
        this.displayId = displayId;
        this.taskId = taskId;
        this.densityDpi = densityDpi;
        mBounds = bounds == null ? null : new Rect(bounds);
        this.origin = origin == null || origin.trim().isEmpty()
                ? "unspecified" : origin.trim();
    }

    static DesktopWindowTransitionRequest enterFullscreen(
            final int displayId,
            final int taskId,
            final int densityDpi,
            final String origin) {
        return new DesktopWindowTransitionRequest(
                Operation.ENTER_FULLSCREEN,
                displayId, taskId, null, densityDpi, origin);
    }

    static DesktopWindowTransitionRequest enterAppFullscreen(
            final int displayId,
            final int taskId,
            final Rect restoreBounds,
            final int densityDpi,
            final String origin) {
        return new DesktopWindowTransitionRequest(
                Operation.ENTER_APP_FULLSCREEN,
                displayId, taskId, restoreBounds, densityDpi, origin);
    }

    static DesktopWindowTransitionRequest restoreFreeform(
            final int displayId,
            final int taskId,
            final Rect targetBounds,
            final int densityDpi,
            final String origin) {
        return new DesktopWindowTransitionRequest(
                Operation.RESTORE_FREEFORM,
                displayId, taskId, targetBounds, densityDpi, origin);
    }

    Rect bounds() {
        return mBounds == null ? null : new Rect(mBounds);
    }
}

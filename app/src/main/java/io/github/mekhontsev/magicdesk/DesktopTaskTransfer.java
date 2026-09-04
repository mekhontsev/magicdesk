package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.view.Display;

import java.io.IOException;

/** Executes cross-display task transfers through their mode-specific protocol. */
final class DesktopTaskTransfer {
    private DesktopTaskTransfer() {
    }

    static String moveFreeform(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final Rect freeformBounds) throws IOException {
        return moveFreeform(
                taskId,
                sourceDisplayId,
                targetDisplayId,
                freeformBounds,
                null);
    }

    static String moveFreeform(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final Rect freeformBounds,
            final DesktopTransitionSurfaceProbe.Reference surfaceReference)
            throws IOException {
        requireTransfer(taskId, sourceDisplayId, targetDisplayId);
        requireDesktopTarget(targetDisplayId);
        requireFreeformBounds(freeformBounds);
        return ShellAccess.run(createFreeformCommand(
                taskId,
                sourceDisplayId,
                targetDisplayId,
                freeformBounds,
                surfaceReference));
    }

    static String moveFullscreen(
            final int taskId,
            final int rootTaskId,
            final int sourceDisplayId,
            final int targetDisplayId) throws IOException {
        requireTransfer(taskId, sourceDisplayId, targetDisplayId);
        if (rootTaskId < 0) {
            throw new IllegalArgumentException("invalid root task id");
        }
        requireDesktopTarget(targetDisplayId);
        return ShellAccess.run(TaskFullscreenMoveCommand.createMoveCommand(
                taskId, rootTaskId, sourceDisplayId, targetDisplayId));
    }

    static String createFreeformCommand(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final Rect freeformBounds,
            final DesktopTransitionSurfaceProbe.Reference surfaceReference) {
        requireTransfer(taskId, sourceDisplayId, targetDisplayId);
        requireFreeformBounds(freeformBounds);
        return surfaceReference == null
                ? TaskDisplayAreaLaunchCommand.createMoveCommand(
                        taskId,
                        sourceDisplayId,
                        targetDisplayId,
                        freeformBounds)
                : TaskDisplayAreaLaunchCommand.createObservedMoveCommand(
                        taskId,
                        sourceDisplayId,
                        targetDisplayId,
                        freeformBounds,
                        surfaceReference);
    }

    private static void requireTransfer(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId) {
        if (taskId < 0 || sourceDisplayId < 0 || targetDisplayId < 0
                || sourceDisplayId == targetDisplayId) {
            throw new IllegalArgumentException("invalid task transfer");
        }
    }

    private static void requireDesktopTarget(final int targetDisplayId)
            throws IOException {
        if (targetDisplayId != Display.DEFAULT_DISPLAY
                && !DesktopDisplayDrivers.hasActiveWorkspace(
                        targetDisplayId)) {
            throw new IOException(
                    "target desktop workspace is unavailable on display "
                            + targetDisplayId);
        }
    }

    private static void requireFreeformBounds(final Rect bounds) {
        if (bounds == null
                || bounds.right <= bounds.left
                || bounds.bottom <= bounds.top) {
            throw new IllegalArgumentException(
                    "freeform transfer requires bounds");
        }
    }
}

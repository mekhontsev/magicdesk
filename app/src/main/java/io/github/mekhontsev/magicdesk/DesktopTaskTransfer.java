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
            final Rect freeformBounds,
            final int densityDpi) throws IOException {
        return moveFreeform(
                taskId,
                sourceDisplayId,
                targetDisplayId,
                freeformBounds,
                null,
                densityDpi);
    }

    static String moveFreeform(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final Rect freeformBounds,
            final DesktopTransitionSurfaceProbe.Reference surfaceReference,
            final int densityDpi)
            throws IOException {
        requireTransfer(taskId, sourceDisplayId, targetDisplayId);
        requireDesktopTarget(targetDisplayId);
        requireFreeformBounds(freeformBounds);
        return ShellAccess.run(createFreeformCommand(
                taskId,
                sourceDisplayId,
                targetDisplayId,
                freeformBounds,
                surfaceReference,
                densityDpi));
    }

    static String moveFullscreen(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final int densityDpi) throws IOException {
        requireTransfer(taskId, sourceDisplayId, targetDisplayId);
        requireDesktopTarget(targetDisplayId);
        return ShellAccess.run(TaskFullscreenMoveCommand.createMoveCommand(
                taskId,
                sourceDisplayId,
                targetDisplayId,
                densityDpi));
    }

    static String createFreeformCommand(
            final int taskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final Rect freeformBounds,
            final DesktopTransitionSurfaceProbe.Reference surfaceReference,
            final int densityDpi) {
        requireTransfer(taskId, sourceDisplayId, targetDisplayId);
        requireFreeformBounds(freeformBounds);
        return surfaceReference == null
                ? TaskDisplayAreaLaunchCommand.createMoveCommand(
                        taskId,
                        sourceDisplayId,
                        targetDisplayId,
                        freeformBounds,
                        densityDpi)
                : TaskDisplayAreaLaunchCommand.createObservedMoveCommand(
                        taskId,
                        sourceDisplayId,
                        targetDisplayId,
                        freeformBounds,
                        surfaceReference,
                        densityDpi);
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

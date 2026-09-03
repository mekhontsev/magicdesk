package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.view.Display;

import java.io.IOException;

/** Selects and executes the ownership-preserving route for a task transfer. */
final class DesktopTaskTransfer {
    enum Mode {
        FREEFORM,
        FULLSCREEN
    }

    enum Route {
        DIRECT_ROOT,
        UNAVAILABLE
    }

    private DesktopTaskTransfer() {
    }

    static String move(
            final int taskId,
            final int rootTaskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final Mode mode,
            final Rect freeformBounds) throws IOException {
        if (taskId < 0 || sourceDisplayId < 0 || targetDisplayId < 0
                || sourceDisplayId == targetDisplayId) {
            throw new IllegalArgumentException("invalid task transfer");
        }
        final Route route = routeFor(
                DesktopDisplayDrivers.hasActiveWorkspace(targetDisplayId),
                targetDisplayId);
        if (route != Route.DIRECT_ROOT) {
            throw new IOException(
                    "target desktop workspace is unavailable on display "
                            + targetDisplayId);
        }
        return ShellAccess.run(createDirectRootCommand(
                taskId,
                rootTaskId,
                sourceDisplayId,
                targetDisplayId,
                mode,
                freeformBounds));
    }

    static String createDirectRootCommand(
            final int taskId,
            final int rootTaskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final Mode mode,
            final Rect freeformBounds) {
        return createDirectRootCommand(
                taskId,
                rootTaskId,
                sourceDisplayId,
                targetDisplayId,
                mode,
                freeformBounds,
                null);
    }

    static String createDirectRootCommand(
            final int taskId,
            final int rootTaskId,
            final int sourceDisplayId,
            final int targetDisplayId,
            final Mode mode,
            final Rect freeformBounds,
            final DesktopTransitionSurfaceProbe.Reference surfaceReference) {
        if (mode == Mode.FREEFORM) {
            requireFreeformBounds(freeformBounds);
            return TaskDisplayAreaLaunchCommand.createRootTaskMoveCommand(
                    taskId,
                    rootTaskId,
                    sourceDisplayId,
                    targetDisplayId,
                    freeformBounds,
                    surfaceReference);
        }
        return TaskFullscreenMoveCommand.createMoveCommand(
                taskId,
                rootTaskId,
                sourceDisplayId,
                targetDisplayId);
    }

    static boolean usesDirectRoot(final int targetDisplayId) {
        return routeFor(
                DesktopDisplayDrivers.hasActiveWorkspace(targetDisplayId),
                targetDisplayId) == Route.DIRECT_ROOT;
    }

    static Route routeFor(
            final boolean configuredDesktop,
            final int targetDisplayId) {
        if (targetDisplayId < Display.DEFAULT_DISPLAY) {
            return Route.UNAVAILABLE;
        }
        if (configuredDesktop
                || targetDisplayId == Display.DEFAULT_DISPLAY) {
            return Route.DIRECT_ROOT;
        }
        return Route.UNAVAILABLE;
    }

    private static void requireFreeformBounds(final Rect bounds) {
        if (bounds == null || bounds.isEmpty()) {
            throw new IllegalArgumentException(
                    "freeform transfer requires bounds");
        }
    }
}

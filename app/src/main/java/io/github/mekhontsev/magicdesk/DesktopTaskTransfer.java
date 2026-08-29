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
        MANAGED_SESSION,
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
        final DesktopTaskAreaPolicy targetPolicy =
                DesktopDisplayDrivers.activeTaskAreaPolicy(targetDisplayId);
        final Route route = routeFor(targetPolicy, targetDisplayId);
        if (route == Route.MANAGED_SESSION) {
            if (mode == Mode.FREEFORM) {
                requireFreeformBounds(freeformBounds);
                MagicDeskRuntime.placeWindowedTaskInManagedSession(
                        taskId,
                        sourceDisplayId,
                        targetDisplayId,
                        freeformBounds);
            } else {
                MagicDeskRuntime.placeFullscreenTaskInManagedSession(
                        taskId, sourceDisplayId, targetDisplayId);
            }
            return resultMarker(taskId, mode);
        }
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
        final DesktopTaskAreaPolicy policy =
                DesktopDisplayDrivers.activeTaskAreaPolicy(targetDisplayId);
        return routeFor(policy, targetDisplayId) == Route.DIRECT_ROOT;
    }

    static Route routeFor(
            final DesktopTaskAreaPolicy policy,
            final int targetDisplayId) {
        if (policy == null || targetDisplayId < Display.DEFAULT_DISPLAY) {
            return Route.UNAVAILABLE;
        }
        if (policy.usesManagedApplicationArea()) {
            return Route.MANAGED_SESSION;
        }
        if (policy.usesDirectRootWorkspace()
                || targetDisplayId == Display.DEFAULT_DISPLAY) {
            return Route.DIRECT_ROOT;
        }
        return Route.UNAVAILABLE;
    }

    private static String resultMarker(final int taskId, final Mode mode) {
        return mode == Mode.FREEFORM
                ? "task-freeform-move=" + taskId
                : "task-fullscreen-move=" + taskId;
    }

    private static void requireFreeformBounds(final Rect bounds) {
        if (bounds == null || bounds.isEmpty()) {
            throw new IllegalArgumentException(
                    "freeform transfer requires bounds");
        }
    }
}

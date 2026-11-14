package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;

/** One display/logical coordinate boundary for observations and explicit window operations. */
final class FrameworkWindowGeometry {
    static final String ENVIRONMENT_KEY = "MAGICDESK_WINDOW_GEOMETRY";
    private static volatile boolean sEnabled = !"0".equals(System.getenv(ENVIRONMENT_KEY));

    private FrameworkWindowGeometry() { }

    static boolean enabled() { return sEnabled; }
    static void initialize(boolean enabled) { sEnabled = enabled; }

    static boolean adapted() {
        return sEnabled && PlatformDrivers.current().windowing().hasScaledWindows();
    }

    static float scale(Object task, int mode) {
        if (!sEnabled || mode != FrameworkTaskSnapshot.WINDOWING_MODE_FREEFORM) return 1f;
        return PlatformDrivers.current().windowing().taskScale(task);
    }

    static Plan plan(Object task, Rect displayBounds) throws ReflectiveOperationException {
        return plan(task, displayBounds, null);
    }

    static Plan plan(Object task, Rect displayBounds, Float retainedScale)
            throws ReflectiveOperationException {
        float scale = adapted() && retainedScale != null ? retainedScale
                : scale(task, HiddenTaskApi.getTaskWindowingMode(task));
        if (!TaskWindowGeometry.validScale(scale)) {
            throw new IllegalStateException("task window scale is unavailable");
        }
        return new Plan(displayBounds, scale);
    }

    static Plan capture(Object task) throws ReflectiveOperationException {
        return new Plan(scale(task, HiddenTaskApi.getTaskWindowingMode(task)),
                HiddenTaskApi.readBounds(task));
    }

    static String diagnosticDetail() {
        return "enabled=" + sEnabled + ", "
                + PlatformDrivers.current().windowing().geometryDiagnostic();
    }

    static final class Plan {
        final Rect logicalBounds;
        final float scale;
        Plan(Rect displayBounds, float scale) {
            this(scale, TaskWindowGeometry.logicalBounds(displayBounds, scale));
        }
        private Plan(float scale, Rect logical) {
            if (!TaskWindowGeometry.validScale(scale)) {
                throw new IllegalStateException("task window scale is unavailable");
            }
            this.scale = scale;
            logicalBounds = logical;
        }
        Rect displayBounds() { return TaskWindowGeometry.displayBounds(logicalBounds, scale); }
        void apply(FrameworkWindowingApi windowing, Object transaction, Object token)
                throws ReflectiveOperationException {
            if (adapted()) {
                PlatformDrivers.current().windowing().prepareTaskGeometry(
                        transaction, windowing.tokenClass(), token, scale);
            }
            windowing.setBounds(transaction, token, logicalBounds);
        }
    }

    /** A new freeform task may receive its firmware scale only at the transition boundary. */
    static void finishPlacement(Object service, int displayId, int taskId, Rect requested)
            throws ReflectiveOperationException {
        if (!adapted()) return;
        FrameworkWindowCommitBarrier.awaitSystemTransitions();
        FrameworkTaskSnapshot task = FrameworkTaskSnapshotSource.findTask(service, displayId, taskId);
        if (task == null || task.windowingMode != FrameworkTaskSnapshot.WINDOWING_MODE_FREEFORM) {
            throw new IllegalStateException("freeform task unavailable after placement");
        }
        if (!TaskWindowGeometry.validScale(task.windowScale)) {
            throw new IllegalStateException("task window scale is unavailable after placement");
        }
        if (task.windowScale != 1f
                && !TaskDisplayAreaLaunchCommand.satisfiesRequestedBounds(task.bounds, requested)) {
            // One finalization of this explicit placement, never an observer-driven repair loop.
            TaskWindowingCommand.resizeBounds(service, displayId, new int[] {taskId}, new Rect[] {requested});
        }
        TaskDisplayAreaLaunchCommand.waitForTaskFreeformBounds(service, displayId, taskId, requested);
    }
}

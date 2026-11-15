package io.github.mekhontsev.magicdesk;

/** Admission for one released native scale change, never a target-size watchdog. */
final class NativeWindowScaleCorrection {
    private NativeWindowScaleCorrection() { }

    static boolean eligible(FrameworkTaskSnapshot task) {
        return task != null && task.visible
                && task.activityType == FrameworkTaskSnapshot.ACTIVITY_TYPE_STANDARD
                && task.windowingMode == FrameworkTaskSnapshot.WINDOWING_MODE_FREEFORM
                && TaskWindowGeometry.validScale(task.windowScale)
                && !task.bounds.isEmpty() && !task.logicalBounds.isEmpty()
                && (task.topPackage == null || task.topPackage.equals(task.packageName));
    }

    static boolean sameIdentity(FrameworkTaskSnapshot first, FrameworkTaskSnapshot second) {
        return first != null && second != null && first.taskId == second.taskId
                && first.rootTaskId == second.rootTaskId && first.userId == second.userId
                && first.displayId == second.displayId
                && first.displayAreaFeatureId == second.displayAreaFeatureId
                && first.componentName.equals(second.componentName)
                && first.topActivityName.equals(second.topActivityName);
    }

    static boolean candidate(FrameworkTaskSnapshot previous, FrameworkTaskSnapshot current) {
        return eligible(previous) && eligible(current) && sameIdentity(previous, current)
                && previous.densityDpi == current.densityDpi
                && previous.windowScale != current.windowScale
                && previous.logicalBounds.width() == current.logicalBounds.width()
                && previous.logicalBounds.height() == current.logicalBounds.height()
                && !previous.bounds.equals(current.bounds);
    }

    static boolean matches(FrameworkTaskSnapshot expected, FrameworkTaskSnapshot current) {
        return eligible(current) && sameIdentity(expected, current)
                && expected.windowScale == current.windowScale
                && expected.densityDpi == current.densityDpi
                && expected.logicalBounds.equals(current.logicalBounds);
    }
}

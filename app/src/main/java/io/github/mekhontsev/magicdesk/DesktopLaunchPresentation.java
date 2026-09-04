package io.github.mekhontsev.magicdesk;

/** Window and task-instance policy independent from an Android launch target. */
final class DesktopLaunchPresentation {
    private static final DesktopLaunchPresentation AUTO =
            new DesktopLaunchPresentation(
                    DesktopLaunchMode.AUTO,
                    null,
                    DesktopTaskInstancePolicy.REUSE_EXISTING,
                    -1);

    final DesktopLaunchMode mode;
    final RelativeWindowBounds bounds;
    final DesktopTaskInstancePolicy instancePolicy;
    final int preferredTaskId;

    DesktopLaunchPresentation(
            final DesktopLaunchMode mode,
            final RelativeWindowBounds bounds,
            final DesktopTaskInstancePolicy instancePolicy,
            final int preferredTaskId) {
        this.mode = mode == null ? DesktopLaunchMode.AUTO : mode;
        this.bounds = bounds;
        this.instancePolicy = instancePolicy == null
                ? DesktopTaskInstancePolicy.REUSE_EXISTING : instancePolicy;
        if (bounds != null && this.mode != DesktopLaunchMode.WINDOWED) {
            throw new IllegalArgumentException(
                    "bounds require mode=windowed");
        }
        if (preferredTaskId == 0 || preferredTaskId < -1) {
            throw new IllegalArgumentException("invalid preferred task id");
        }
        if (preferredTaskId > 0
                && this.instancePolicy
                        != DesktopTaskInstancePolicy.REUSE_EXISTING) {
            throw new IllegalArgumentException(
                    "a preferred task requires instance=reuse");
        }
        if (preferredTaskId > 0 && this.mode == DesktopLaunchMode.AUTO) {
            throw new IllegalArgumentException(
                    "a preferred task requires an explicit mode");
        }
        if (preferredTaskId > 0 && bounds != null) {
            throw new IllegalArgumentException(
                    "a preferred task cannot request initial bounds");
        }
        this.preferredTaskId = preferredTaskId;
    }

    static DesktopLaunchPresentation automatic() {
        return AUTO;
    }

    static DesktopLaunchPresentation forMode(final DesktopLaunchMode mode) {
        return mode == null || mode == DesktopLaunchMode.AUTO
                ? AUTO
                : new DesktopLaunchPresentation(
                        mode,
                        null,
                        DesktopTaskInstancePolicy.REUSE_EXISTING,
                        -1);
    }

    DesktopLaunchPresentation withBounds(
            final RelativeWindowBounds value) {
        return new DesktopLaunchPresentation(
                mode, value, instancePolicy, preferredTaskId);
    }

    DesktopLaunchPresentation withInstancePolicy(
            final DesktopTaskInstancePolicy value) {
        return new DesktopLaunchPresentation(mode, bounds, value, -1);
    }

    DesktopLaunchPresentation withPreferredTask(final int taskId) {
        return new DesktopLaunchPresentation(
                mode,
                bounds,
                DesktopTaskInstancePolicy.REUSE_EXISTING,
                taskId);
    }
}

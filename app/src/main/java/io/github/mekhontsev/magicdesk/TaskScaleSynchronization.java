package io.github.mekhontsev.magicdesk;

/** Orders decoration scale and WM geometry; compensates to observed WM state on failure. */
final class TaskScaleSynchronization {
    interface Geometry {
        boolean isCurrent() throws ReflectiveOperationException;
        void commit() throws ReflectiveOperationException;
        // NaN withdraws ownership: never write to a removed/replaced/moved task.
        float committedScale() throws ReflectiveOperationException;
    }

    private TaskScaleSynchronization() { }

    static boolean apply(PlatformWindowingDriver.TaskScalePeer peer, float before, float after,
            Geometry geometry) throws ReflectiveOperationException {
        if (!TaskWindowGeometry.validScale(before) || !TaskWindowGeometry.validScale(after)) {
            throw new IllegalArgumentException("invalid scale change");
        }
        if (!geometry.isCurrent()) return false;
        if (peer.read() != before) throw new IllegalStateException("WM and decoration scale disagree");
        boolean requested = false;
        try {
            if (before != after) {
                requested = true;
                update(peer, after);
            }
            if (!geometry.isCurrent()) return false;
            geometry.commit();
            if (geometry.committedScale() != after || peer.read() != after) {
                throw new IllegalStateException("window scale commit did not converge");
            }
            requested = false;
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            if (requested) {
                requested = false;
                try { compensate(peer, geometry); }
                catch (ReflectiveOperationException | RuntimeException rollback) { error.addSuppressed(rollback); }
            }
            throw error;
        } finally {
            if (requested) compensate(peer, geometry);
        }
    }

    private static void compensate(PlatformWindowingDriver.TaskScalePeer peer, Geometry geometry)
            throws ReflectiveOperationException {
        float current = geometry.committedScale();
        if (TaskWindowGeometry.validScale(current)) update(peer, current);
    }

    private static void update(PlatformWindowingDriver.TaskScalePeer peer, float scale)
            throws ReflectiveOperationException {
        peer.request(scale);
        // The one-way request has no acknowledgement callback. Timeout is failure,
        // not a gesture-settling delay; unchanged windows never enter this wait.
        float observed = BoundedStateAwaiter.awaitFramework(BoundedStateAwaiter.Reason.VENDOR_STATE,
                1000L, 20L, peer::read, value -> value == scale);
        if (observed != scale) throw new IllegalStateException("decoration scale acknowledgement timed out");
    }
}

package io.github.mekhontsev.magicdesk;

import android.view.Display;

/** Immutable desktop target and host identity observed as one runtime state. */
final class DesktopSessionSnapshot {
    private final DesktopDisplayTarget mTarget;
    private final int mHostDisplayId;
    private final int mHostTaskId;

    private DesktopSessionSnapshot(
            final DesktopDisplayTarget target,
            final int hostDisplayId,
            final int hostTaskId) {
        mTarget = target;
        mHostDisplayId = hostDisplayId;
        mHostTaskId = hostTaskId;
    }

    static DesktopSessionSnapshot empty() {
        return new DesktopSessionSnapshot(
                null, Display.INVALID_DISPLAY, -1);
    }

    DesktopDisplayTarget target() {
        return mTarget;
    }

    DesktopDisplayTarget targetForDisplay(final int displayId) {
        return mTarget != null && mTarget.displayId == displayId
                ? mTarget : null;
    }

    int activeDisplayId() {
        return mHostDisplayId;
    }

    int hostTaskId() {
        return mHostTaskId;
    }

    boolean hasHost() {
        return mHostDisplayId >= Display.DEFAULT_DISPLAY;
    }

    boolean isLocalActiveOrStarting() {
        return mHostDisplayId == Display.DEFAULT_DISPLAY
                || (mTarget != null
                        && mTarget.displayId == Display.DEFAULT_DISPLAY
                        && mTarget.kind == DesktopDisplayTarget.Kind.PHONE);
    }

    DesktopSessionSnapshot noteTarget(final DesktopDisplayTarget target) {
        return target == null
                ? this
                : new DesktopSessionSnapshot(
                        target, mHostDisplayId, mHostTaskId);
    }

    DesktopSessionSnapshot clearTarget(final DesktopDisplayTarget target) {
        if (!sameTarget(mTarget, target)) {
            return this;
        }
        return new DesktopSessionSnapshot(
                null, mHostDisplayId, mHostTaskId);
    }

    DesktopSessionSnapshot registerHost(
            final int displayId,
            final int taskId,
            final boolean replacingSameTask) {
        DesktopDisplayTarget target = mTarget;
        // Preserve an external target when WMS briefly moves the same desktop
        // task to the phone before the replacement Console HOME is registered.
        if (!replacingSameTask || displayId != Display.DEFAULT_DISPLAY) {
            if (target == null || target.displayId != displayId) {
                target = displayId == Display.DEFAULT_DISPLAY
                        ? DesktopDisplayTarget.phone() : null;
            }
        }
        return new DesktopSessionSnapshot(target, displayId, taskId);
    }

    DesktopSessionSnapshot observeHost(
            final int displayId, final int taskId) {
        if (mHostDisplayId == displayId && mHostTaskId == taskId) {
            return this;
        }
        return new DesktopSessionSnapshot(mTarget, displayId, taskId);
    }

    DesktopSessionSnapshot unregisterHost(
            final int displayId,
            final boolean changingConfigurations) {
        DesktopDisplayTarget target = mTarget;
        if (!changingConfigurations
                && target != null
                && (displayId == target.displayId
                        || target.kind == DesktopDisplayTarget.Kind.PHONE)) {
            target = null;
        }
        return new DesktopSessionSnapshot(
                target, Display.INVALID_DISPLAY, -1);
    }

    DesktopSessionSnapshot close() {
        return empty();
    }

    private static boolean sameTarget(
            final DesktopDisplayTarget first,
            final DesktopDisplayTarget second) {
        return first != null
                && second != null
                && first.displayId == second.displayId
                && first.kind == second.kind;
    }
}

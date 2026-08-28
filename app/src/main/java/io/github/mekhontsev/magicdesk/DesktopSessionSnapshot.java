package io.github.mekhontsev.magicdesk;

import android.view.Display;

/** Immutable desktop target and host identity observed as one runtime state. */
final class DesktopSessionSnapshot {
    private final DesktopDisplayTarget mTarget;
    private final DesktopSessionPolicy mPolicy;
    private final int mHostDisplayId;
    private final int mHostTaskId;

    private DesktopSessionSnapshot(
            final DesktopDisplayTarget target,
            final DesktopSessionPolicy policy,
            final int hostDisplayId,
            final int hostTaskId) {
        mTarget = target;
        mPolicy = policy == null ? DesktopSessionPolicy.USER : policy;
        mHostDisplayId = hostDisplayId;
        mHostTaskId = hostTaskId;
    }

    static DesktopSessionSnapshot empty() {
        return new DesktopSessionSnapshot(
                null, DesktopSessionPolicy.USER,
                Display.INVALID_DISPLAY, -1);
    }

    DesktopDisplayTarget target() {
        return mTarget;
    }

    DesktopSessionPolicy policy() {
        return mPolicy;
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
        return noteTarget(target, DesktopSessionPolicy.USER);
    }

    DesktopSessionSnapshot noteTarget(
            final DesktopDisplayTarget target,
            final DesktopSessionPolicy policy) {
        return target == null
                ? this
                : new DesktopSessionSnapshot(
                        target, policy, mHostDisplayId, mHostTaskId);
    }

    DesktopSessionSnapshot clearTarget(final DesktopDisplayTarget target) {
        if (!sameTarget(mTarget, target)) {
            return this;
        }
        return new DesktopSessionSnapshot(
                null, DesktopSessionPolicy.USER,
                mHostDisplayId, mHostTaskId);
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
        return new DesktopSessionSnapshot(
                target, mPolicy, displayId, taskId);
    }

    DesktopSessionSnapshot observeHost(
            final int displayId, final int taskId) {
        if (mHostDisplayId == displayId && mHostTaskId == taskId) {
            return this;
        }
        return new DesktopSessionSnapshot(
                mTarget, mPolicy, displayId, taskId);
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
                target,
                target == null ? DesktopSessionPolicy.USER : mPolicy,
                Display.INVALID_DISPLAY,
                -1);
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

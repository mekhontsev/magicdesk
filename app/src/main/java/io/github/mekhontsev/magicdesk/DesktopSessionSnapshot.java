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

    DesktopDisplayTarget targetForWorkspace(final int displayId) {
        return mTarget != null && mTarget.workspaceDisplayId == displayId
                ? mTarget : null;
    }

    int activeWorkspaceDisplayId() {
        return mHostDisplayId;
    }

    int activeOutputDisplayId() {
        return hasHost() && mTarget != null ? mTarget.output.displayId : Display.INVALID_DISPLAY;
    }

    int inputDisplayId() {
        return activeWorkspaceDisplayId();
    }

    int hostTaskId() {
        return mHostTaskId;
    }

    boolean hasHost() {
        return mHostDisplayId >= Display.DEFAULT_DISPLAY;
    }

    boolean isLocalActiveOrStarting() {
        return mHostDisplayId == Display.DEFAULT_DISPLAY
                || (mTarget != null && mTarget.isPhoneWorkspace());
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
        if (mTarget == null || !mTarget.sameBinding(target)) {
            return this;
        }
        return new DesktopSessionSnapshot(
                null, DesktopSessionPolicy.USER,
                mHostDisplayId, mHostTaskId);
    }

    DesktopSessionSnapshot registerHost(
            final int displayId,
            final int taskId) {
        if (mTarget == null || mTarget.workspaceDisplayId != displayId) {
            throw new IllegalStateException(
                    "desktop host does not match the prepared target");
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
                && (displayId == target.workspaceDisplayId
                        || target.isPhoneWorkspace())) {
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

}

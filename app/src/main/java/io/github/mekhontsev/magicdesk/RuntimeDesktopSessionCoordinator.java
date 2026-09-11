package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Handler;
import android.util.Log;
import android.view.Display;

import java.util.function.IntPredicate;

/** Owns desktop display identity and retained phone-task recovery. */
final class RuntimeDesktopSessionCoordinator {
    private static final String TAG = "MagicDeskSessionRuntime";
    private static final long LOCAL_DESKTOP_CLEANUP_DELAY_MILLIS = 500;
    private static final long DISPLAY_REMOVAL_WATCHDOG_MILLIS = 2000;
    private static final long HOME_LEASE_RECONCILIATION_DELAY_MILLIS = 2000;

    interface Listener {
        void onOwnershipRefreshed(boolean changed);
    }

    private final Context mContext;
    private final Handler mHandler;
    private final IntPredicate mDisplayExists;
    private final Listener mListener;
    private int mDesktopDisplayId = Display.INVALID_DISPLAY;
    private boolean mRecoverPhoneTasks;
    private RemovedDisplayRecovery mRemovedDisplayRecovery;
    private int mExpectedRemovedDisplayId = Display.INVALID_DISPLAY;
    private boolean mLocalDesktopCleanupInFlight;
    private boolean mHomeLeaseRecoveryInFlight;
    private boolean mDestroyed;
    private final Runnable mPhoneTaskRecoveryRunnable =
            this::recoverTasksAfterDisplayRemoval;
    private final Runnable mDisplayRemovalWatchdogRunnable =
            () -> schedulePhoneTaskRecovery(true);
    private final Runnable mLocalDesktopCleanupRunnable =
            this::cleanupClosedLocalDesktop;
    private final Runnable mHomeLeaseReconciliationRunnable =
            this::reconcileHomeLease;

    RuntimeDesktopSessionCoordinator(
            final Context context,
            final Handler handler,
            final IntPredicate displayExists,
            final Listener listener) {
        mContext = context;
        mHandler = handler;
        mDisplayExists = displayExists;
        mListener = listener;
    }

    void start() {
        SecondaryDisplayWindowing.recoverPending();
        refreshOwnership();
        if (LocalDesktopSessionState.isCleanupPending(mContext)) {
            scheduleLocalDesktopCleanup();
        }
        scheduleHomeLeaseReconciliation();
    }

    void destroy() {
        mDestroyed = true;
        mExpectedRemovedDisplayId = Display.INVALID_DISPLAY;
        clearRemovedDisplayRecovery();
        mHandler.removeCallbacks(mLocalDesktopCleanupRunnable);
        mHandler.removeCallbacks(mHomeLeaseReconciliationRunnable);
    }

    int desktopDisplayId() {
        return mDesktopDisplayId;
    }

    boolean ownsExternalDesktop() {
        return mDesktopDisplayId > Display.DEFAULT_DISPLAY;
    }

    boolean prepareDisplayRemoval(final int displayId) {
        if (mDestroyed || displayId <= Display.DEFAULT_DISPLAY
                || displayId != mDesktopDisplayId) {
            return false;
        }
        mExpectedRemovedDisplayId = displayId;
        return true;
    }

    void reconcileFailedDesktopLaunch(final int displayId) {
        if (mDestroyed
                || displayId <= Display.DEFAULT_DISPLAY
                || mDisplayExists.test(displayId)) {
            return;
        }
        beginRemovedDisplayRecovery(displayId, false);
    }

    void handleDisplayStateChanged(
            final int displayId,
            final boolean displayRemoved) {
        final DesktopSessionSnapshot session =
                DesktopRuntimeBridge.getSessionSnapshot();
        final DesktopDisplayTarget desktopTarget = displayRemoved
                ? session.targetForWorkspace(displayId)
                : null;
        final boolean activeDesktopRemoved = displayRemoved
                && session.activeWorkspaceDisplayId() == displayId;
        final boolean externalDesktopRemoved = isExternalDesktopRemoval(
                displayRemoved,
                displayId,
                mDesktopDisplayId,
                desktopTarget,
                activeDesktopRemoved);
        final boolean expectedDesktopRemoval = displayRemoved
                && (mExpectedRemovedDisplayId == displayId
                        || DesktopOperations.isSessionTransitionInProgress());
        if (expectedDesktopRemoval) {
            mExpectedRemovedDisplayId = Display.INVALID_DISPLAY;
        }
        if (displayRemoved) {
            if (externalDesktopRemoved && !expectedDesktopRemoval) {
                releaseHomeLeaseAfterSessionLoss(displayId);
            }
            PhoneTouchpadController.release(displayId);
            DesktopRuntimeBridge.closeDesktopSession(displayId);
            if (externalDesktopRemoved && !expectedDesktopRemoval) {
                // Explicit Close already returns and reconciles phone tasks
                // before completing. Only unexpected loss needs event recovery.
                beginRemovedDisplayRecovery(displayId, true);
            }
        }
        refreshOwnership();
        if (displayRemoved) {
            schedulePhoneTaskRecovery();
        }
    }

    void refreshOwnership() {
        final DesktopSessionSnapshot session =
                DesktopRuntimeBridge.getSessionSnapshot();
        if (mRemovedDisplayRecovery != null
                && !mRemovedDisplayRecovery.shouldContinue(session)) {
            clearRemovedDisplayRecovery();
        }
        final int desktopDisplayId = session.activeWorkspaceDisplayId();
        if (session.target() != null) {
            mRecoverPhoneTasks = DesktopCompatibilitySettings.current().enabled(
                    DesktopCompatibilityPolicy.Option.PHONE_TASK_RECOVERY);
        }
        if (mExpectedRemovedDisplayId > Display.DEFAULT_DISPLAY
                && desktopDisplayId != mExpectedRemovedDisplayId
                && mDisplayExists.test(mExpectedRemovedDisplayId)) {
            // A local desktop session can close while its backing display
            // remains connected, so no display-removed callback will consume
            // the expected transition marker.
            mExpectedRemovedDisplayId = Display.INVALID_DISPLAY;
        }
        final boolean changed = desktopDisplayId != mDesktopDisplayId;
        mDesktopDisplayId = desktopDisplayId;
        mListener.onOwnershipRefreshed(changed);
        if (changed
                && desktopDisplayId == Display.INVALID_DISPLAY
                && DesktopHomeRoleLease.snapshot() != null) {
            scheduleHomeLeaseReconciliation();
        }
    }

    void onTaskStackChanged() {
        schedulePhoneTaskRecovery();
    }

    void onShellReady() {
        SecondaryDisplayWindowing.recoverPending();
        schedulePhoneTaskRecovery();
        if (LocalDesktopSessionState.isCleanupPending(mContext)) {
            scheduleLocalDesktopCleanup();
        }
        scheduleHomeLeaseReconciliation();
    }

    void schedulePhoneTaskRecovery() {
        schedulePhoneTaskRecovery(false);
    }

    void scheduleLocalDesktopCleanup() {
        if (mDestroyed) {
            return;
        }
        mHandler.removeCallbacks(mLocalDesktopCleanupRunnable);
        mHandler.postDelayed(
                mLocalDesktopCleanupRunnable,
                LOCAL_DESKTOP_CLEANUP_DELAY_MILLIS);
    }

    private void schedulePhoneTaskRecovery(
            final boolean allowUnsettledDisplayRecovery) {
        final RemovedDisplayRecovery recovery = mRemovedDisplayRecovery;
        if (mDestroyed || recovery == null || !ShellAccess.isReady()) {
            return;
        }
        if (!recovery.shouldContinue(DesktopRuntimeBridge.getSessionSnapshot())) {
            clearRemovedDisplayRecovery();
            return;
        }
        recovery.allowUnsettled |= allowUnsettledDisplayRecovery;
        mHandler.removeCallbacks(mPhoneTaskRecoveryRunnable);
        mHandler.post(mPhoneTaskRecoveryRunnable);
    }

    private void beginRemovedDisplayRecovery(
            final int displayId, final boolean restorePhonePanel) {
        if (mDestroyed || (mRemovedDisplayRecovery != null
                && mRemovedDisplayRecovery.displayId == displayId)) {
            return;
        }
        clearRemovedDisplayRecovery();
        mRemovedDisplayRecovery = new RemovedDisplayRecovery(
                displayId, mRecoverPhoneTasks, restorePhonePanel);
        mHandler.postDelayed(
                mDisplayRemovalWatchdogRunnable,
                DISPLAY_REMOVAL_WATCHDOG_MILLIS);
        schedulePhoneTaskRecovery();
    }

    private void clearRemovedDisplayRecovery() {
        if (mRemovedDisplayRecovery != null) {
            mRemovedDisplayRecovery.cancel();
            mRemovedDisplayRecovery = null;
        }
        mHandler.removeCallbacks(mPhoneTaskRecoveryRunnable);
        mHandler.removeCallbacks(mDisplayRemovalWatchdogRunnable);
    }

    private void scheduleHomeLeaseReconciliation() {
        if (mDestroyed) {
            return;
        }
        mHandler.removeCallbacks(mHomeLeaseReconciliationRunnable);
        mHandler.postDelayed(
                mHomeLeaseReconciliationRunnable,
                HOME_LEASE_RECONCILIATION_DELAY_MILLIS);
    }

    private void reconcileHomeLease() {
        if (mDestroyed || !ShellAccess.isReady()
                || DesktopOperations.isSessionTransitionInProgress()) {
            return;
        }
        final DesktopHomeRoleLease.State lease =
                DesktopHomeRoleLease.snapshot();
        if (lease == null) {
            return;
        }
        final DesktopSessionSnapshot session =
                DesktopRuntimeBridge.getSessionSnapshot();
        final DesktopDisplayTarget target = session.target();
        final boolean matchingTarget = target != null
                && lease.matches(target);
        final boolean sessionAlive = matchingTarget && session.hasHost();
        if (lease.phase == DesktopHomeRoleLease.Phase.ACTIVE
                && !sessionAlive) {
            if (matchingTarget || mHomeLeaseRecoveryInFlight) {
                return;
            }
            final DesktopDisplayTarget leasedTarget = lease.target();
            if (leasedTarget.workspaceDisplayId == Display.DEFAULT_DISPLAY
                    || mDisplayExists.test(leasedTarget.workspaceDisplayId)) {
                mHomeLeaseRecoveryInFlight = true;
                DesktopOperations.recoverDesktopSession(
                        leasedTarget,
                        lease.policy,
                        success -> mHandler.post(() ->
                                finishHomeLeaseRecovery(
                                        leasedTarget, success)));
                return;
            }
        }
        try {
            if (DesktopHomeRoleLease.reconcile(sessionAlive)) {
                Log.i(TAG, "reconciled stale desktop HOME lease display="
                        + lease.target().workspaceDisplayId + " phase=" + lease.phase);
            }
        } catch (java.io.IOException error) {
            Log.w(TAG, "could not reconcile desktop HOME lease", error);
            CompatibilityDiagnostics.record(
                    "DESKTOP-HOME-004",
                    "Could not reconcile the temporary Home role",
                    error.getMessage(),
                    error);
        }
    }

    private void finishHomeLeaseRecovery(
            final DesktopDisplayTarget target,
            final boolean success) {
        mHomeLeaseRecoveryInFlight = false;
        if (mDestroyed) {
            return;
        }
        if (success) {
            refreshOwnership();
            reconcileHomeLease();
            return;
        }
        DesktopRuntimeBridge.clearDesktopTarget(target);
        try {
            DesktopHomeRoleLease.reconcile(false);
        } catch (java.io.IOException error) {
            Log.w(TAG, "could not release failed desktop HOME recovery", error);
            CompatibilityDiagnostics.record(
                    "DESKTOP-HOME-007",
                    "Could not release the failed Home session",
                    error.getMessage(),
                    error);
        }
    }

    private static void releaseHomeLeaseAfterSessionLoss(
            final int displayId) {
        // The explicit transition owner also handles display loss during its
        // start/close. Do not disable its HOME hosts from a second callback.
        if (DesktopOperations.isSessionTransitionInProgress()) {
            return;
        }
        try {
            if (DesktopHomeRoleLease.releaseAfterSessionLoss(displayId)) {
                Log.i(TAG, "released desktop HOME lease after display loss="
                        + displayId);
            }
        } catch (java.io.IOException error) {
            Log.w(TAG, "could not release HOME after display loss="
                    + displayId, error);
            CompatibilityDiagnostics.record(
                    "DESKTOP-HOME-005",
                    "Could not restore the Home app after display loss",
                    error.getMessage(),
                    error);
        }
    }

    private void recoverTasksAfterDisplayRemoval() {
        final RemovedDisplayRecovery recovery = mRemovedDisplayRecovery;
        if (mDestroyed || recovery == null || !ShellAccess.isReady()) {
            return;
        }
        if (!recovery.shouldContinue(DesktopRuntimeBridge.getSessionSnapshot())) {
            clearRemovedDisplayRecovery();
            return;
        }
        if (!recovery.begin()) {
            return;
        }
        PhoneDesktopTaskRecovery.recoverRemovedDisplay(
                recovery.required, recovery.displayId, recovery.allowUnsettled,
                () -> recovery.shouldContinue(DesktopRuntimeBridge.getSessionSnapshot()),
                result -> mHandler.post(() -> finishRemovedDisplayRecovery(recovery, result)));
    }

    private void finishRemovedDisplayRecovery(
            final RemovedDisplayRecovery recovery,
            final PhoneDesktopTaskRecovery.Result result) {
        if (mDestroyed || mRemovedDisplayRecovery != recovery) {
            return;
        }
        if (!recovery.shouldContinue(DesktopRuntimeBridge.getSessionSnapshot())) {
            clearRemovedDisplayRecovery();
            return;
        }
        final boolean rerun = recovery.finish(result);
        if (result.pending && !result.cancelled) {
            if (rerun) {
                schedulePhoneTaskRecovery();
            }
            return;
        }
        clearRemovedDisplayRecovery();
        if (!result.success && !result.cancelled) {
            Log.w(TAG, "removed-display task recovery failed: " + result.message);
            CompatibilityDiagnostics.record(
                    "PHONE-TASK-003",
                    "Could not recover tasks after desktop display loss",
                    result.message);
        }
        if (!result.cancelled && recovery.restorePhonePanel) {
            DesktopOperations.restorePhoneAfterExternalDesktop();
        }
    }

    private void cleanupClosedLocalDesktop() {
        final DesktopSessionSnapshot session =
                DesktopRuntimeBridge.getSessionSnapshot();
        if (mDestroyed
                || mLocalDesktopCleanupInFlight
                || !LocalDesktopSessionState.isCleanupPending(mContext)
                || session.activeWorkspaceDisplayId() == Display.DEFAULT_DISPLAY) {
            return;
        }
        if (!ShellAccess.isReady()) {
            Log.w(TAG,
                    "pending phone freeform cleanup requires shell task control");
            return;
        }
        mLocalDesktopCleanupInFlight = true;
        PhoneDesktopTaskRecovery.recover(
                LocalDesktopSessionState.requiresTaskRecovery(mContext),
                () -> !DesktopRuntimeBridge.isLocalDesktopActiveOrStarting(),
                result -> mHandler.post(() -> {
                    mLocalDesktopCleanupInFlight = false;
                    if (mDestroyed) {
                        return;
                    }
                    if (result.cancelled) {
                        return;
                    }
                    if (!result.success) {
                        Log.w(TAG,
                                "phone desktop recovery failed: "
                                        + result.message);
                        CompatibilityDiagnostics.record(
                                "PHONE-TASK-004",
                                "Could not clean local desktop tasks before"
                                        + " leaving phone desktop mode",
                                result.message);
                        return;
                    }
                    if (DesktopRuntimeBridge
                            .isLocalDesktopActiveOrStarting()) {
                        return;
                    }
                    LocalDesktopSessionState.clearCleanupPending(mContext);
                    Log.i(TAG,
                            "recovered phone desktop tasks after local desktop");
                }));
    }

    static boolean isExternalDesktopRemoval(
            final boolean displayRemoved,
            final int displayId,
            final int ownedDesktopDisplayId,
            final DesktopDisplayTarget desktopTarget,
            final boolean activeDesktopRemoved) {
        if (!displayRemoved || displayId <= Display.DEFAULT_DISPLAY) {
            return false;
        }
        if (desktopTarget == null) {
            return displayId == ownedDesktopDisplayId;
        }
        return DesktopDisplayDrivers.forTarget(desktopTarget)
                .isSessionDisplayRemoval(
                        desktopTarget,
                        displayId,
                        activeDesktopRemoved);
    }

    /** One removal owns its callbacks; only unsettled migration can request another pass. */
    static final class RemovedDisplayRecovery {
        final int displayId;
        final boolean required;
        final boolean restorePhonePanel;
        boolean allowUnsettled;
        private boolean mInFlight;
        private boolean mAgain;
        private volatile boolean mCancelled;

        RemovedDisplayRecovery(
                final int displayId, final boolean required, final boolean restorePhonePanel) {
            this.displayId = displayId;
            this.required = required;
            this.restorePhonePanel = restorePhonePanel;
        }

        boolean shouldContinue(final DesktopSessionSnapshot session) {
            if ((session.target() != null && session.target().workspaceDisplayId != displayId)
                    || (session.hasHost() && session.activeWorkspaceDisplayId() != displayId)) {
                // Checked on the task queue before each mutation as well as on
                // lifecycle callbacks, including before a new host is ready.
                cancel();
            }
            return !mCancelled;
        }

        void cancel() {
            mCancelled = true;
        }

        boolean begin() {
            if (mCancelled) {
                return false;
            }
            if (mInFlight) {
                mAgain = true;
                return false;
            }
            mInFlight = true;
            mAgain = false;
            return true;
        }

        boolean finish(final PhoneDesktopTaskRecovery.Result result) {
            mInFlight = false;
            // Task changes caused by recovery itself must not turn a terminal
            // failure (for example a dead SystemUI task ID) into an endless retry.
            if (!result.pending || result.cancelled) {
                cancel();
            }
            return !mCancelled && mAgain;
        }
    }
}

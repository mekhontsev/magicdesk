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
    private boolean mPhoneTaskRecoveryInFlight;
    private boolean mPhoneTaskRecoveryAgain;
    private boolean mAllowUnsettledDisplayRecovery;
    private int mRemovedDesktopDisplayId = Display.INVALID_DISPLAY;
    private int mExpectedRemovedDisplayId = Display.INVALID_DISPLAY;
    private boolean mRestorePhonePanelAfterRecovery;
    private boolean mLocalDesktopCleanupInFlight;
    private boolean mHomeLeaseRecoveryInFlight;
    private boolean mDestroyed;
    private final Runnable mPhoneTaskRecoveryRunnable =
            this::recoverTasksAfterDisplayRemoval;
    private final Runnable mDisplayRemovalWatchdogRunnable = () -> {
        if (mRemovedDesktopDisplayId > Display.DEFAULT_DISPLAY) {
            schedulePhoneTaskRecovery(true);
        }
    };
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
        refreshOwnership();
        if (LocalDesktopSessionState.isCleanupPending(mContext)) {
            scheduleLocalDesktopCleanup();
        }
        scheduleHomeLeaseReconciliation();
    }

    void destroy() {
        mDestroyed = true;
        mExpectedRemovedDisplayId = Display.INVALID_DISPLAY;
        mHandler.removeCallbacks(mPhoneTaskRecoveryRunnable);
        mHandler.removeCallbacks(mDisplayRemovalWatchdogRunnable);
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

    void cancelDisplayRemoval(final int displayId) {
        if (mExpectedRemovedDisplayId == displayId) {
            mExpectedRemovedDisplayId = Display.INVALID_DISPLAY;
        }
    }

    void reconcileFailedDesktopLaunch(final int displayId) {
        if (mDestroyed
                || displayId <= Display.DEFAULT_DISPLAY
                || mDisplayExists.test(displayId)) {
            return;
        }
        mRemovedDesktopDisplayId = displayId;
        scheduleDisplayRemovalWatchdog();
        schedulePhoneTaskRecovery();
    }

    void handleDisplayStateChanged(
            final int displayId,
            final boolean displayRemoved) {
        final DesktopSessionSnapshot session =
                DesktopRuntimeBridge.getSessionSnapshot();
        final DesktopDisplayTarget desktopTarget = displayRemoved
                ? session.targetForDisplay(displayId)
                : null;
        final boolean activeDesktopRemoved = displayRemoved
                && session.activeDisplayId() == displayId;
        final boolean externalDesktopRemoved = isExternalDesktopRemoval(
                displayRemoved,
                displayId,
                mDesktopDisplayId,
                desktopTarget,
                activeDesktopRemoved);
        final boolean expectedDesktopRemoval = displayRemoved
                && mExpectedRemovedDisplayId == displayId;
        if (expectedDesktopRemoval) {
            mExpectedRemovedDisplayId = Display.INVALID_DISPLAY;
        }
        if (displayRemoved) {
            if (externalDesktopRemoved && !expectedDesktopRemoval) {
                mRestorePhonePanelAfterRecovery = true;
                releaseHomeLeaseAfterSessionLoss(displayId);
            }
            PhoneTouchpadController.release(displayId);
            DesktopRuntimeBridge.closeDesktopSession(displayId);
            if (desktopTarget != null
                    && desktopTarget.kind
                            == DesktopDisplayTarget.Kind.SIMULATED) {
                SimulatedDesktopDisplayController.release(displayId);
            }
            if (externalDesktopRemoved) {
                mRemovedDesktopDisplayId = displayId;
                scheduleDisplayRemovalWatchdog();
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
        final int desktopDisplayId = session.activeDisplayId();
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
        if (mRemovedDesktopDisplayId > Display.DEFAULT_DISPLAY) {
            schedulePhoneTaskRecovery();
        }
    }

    void onShellReady() {
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
        if (mDestroyed || !ShellAccess.isReady()
                || mRemovedDesktopDisplayId <= Display.DEFAULT_DISPLAY) {
            return;
        }
        mAllowUnsettledDisplayRecovery |=
                allowUnsettledDisplayRecovery;
        mHandler.removeCallbacks(mPhoneTaskRecoveryRunnable);
        mHandler.post(mPhoneTaskRecoveryRunnable);
    }

    private void scheduleDisplayRemovalWatchdog() {
        if (mDestroyed) {
            return;
        }
        mHandler.removeCallbacks(mDisplayRemovalWatchdogRunnable);
        mHandler.postDelayed(
                mDisplayRemovalWatchdogRunnable,
                DISPLAY_REMOVAL_WATCHDOG_MILLIS);
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
        if (mDestroyed || !ShellAccess.isReady()) {
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
            if (leasedTarget.displayId == Display.DEFAULT_DISPLAY
                    || mDisplayExists.test(leasedTarget.displayId)) {
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
                        + lease.displayId + " phase=" + lease.phase);
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
        if (mDestroyed || !ShellAccess.isReady()) {
            return;
        }
        if (mPhoneTaskRecoveryInFlight) {
            mPhoneTaskRecoveryAgain = true;
            return;
        }
        final int removedDisplayId = mRemovedDesktopDisplayId;
        if (removedDisplayId <= Display.DEFAULT_DISPLAY) {
            return;
        }
        mPhoneTaskRecoveryInFlight = true;
        final boolean allowUnsettledDisplayRecovery =
                mAllowUnsettledDisplayRecovery;
        final PhoneDesktopTaskRecovery.Callback callback = result ->
                mHandler.post(() -> {
                    mPhoneTaskRecoveryInFlight = false;
                    if (!result.success && !result.pending) {
                        Log.w(TAG, "removed-display task recovery failed: "
                                + result.message);
                        CompatibilityDiagnostics.record(
                                "PHONE-TASK-003",
                                "Could not recover tasks after desktop display loss",
                                result.message);
                    }
                    final boolean recoveryComplete =
                            isPhoneRecoveryComplete(
                                    result.success && !result.pending,
                                    mPhoneTaskRecoveryAgain);
                    final boolean restorePhonePanel =
                            mRestorePhonePanelAfterRecovery
                                    && mRemovedDesktopDisplayId
                                            == removedDisplayId
                                    && recoveryComplete;
                    if (!mDestroyed && recoveryComplete
                            && mRemovedDesktopDisplayId == removedDisplayId) {
                        mRemovedDesktopDisplayId = Display.INVALID_DISPLAY;
                        mAllowUnsettledDisplayRecovery = false;
                        mHandler.removeCallbacks(
                                mDisplayRemovalWatchdogRunnable);
                    }
                    if (!mDestroyed && restorePhonePanel) {
                        mRestorePhonePanelAfterRecovery = false;
                        DesktopOperations
                                .restorePhoneAfterExternalDesktop();
                    }
                    if (!mDestroyed && mPhoneTaskRecoveryAgain) {
                        mPhoneTaskRecoveryAgain = false;
                        schedulePhoneTaskRecovery();
                    }
                });
        if (allowUnsettledDisplayRecovery) {
            PhoneDesktopTaskRecovery.recoverRemovedDisplayAfterTimeout(
                    removedDisplayId, callback);
        } else {
            PhoneDesktopTaskRecovery.recoverRemovedDisplay(
                    removedDisplayId, callback);
        }
    }

    private void cleanupClosedLocalDesktop() {
        final DesktopSessionSnapshot session =
                DesktopRuntimeBridge.getSessionSnapshot();
        if (mDestroyed
                || mLocalDesktopCleanupInFlight
                || !LocalDesktopSessionState.isCleanupPending(mContext)
                || session.activeDisplayId() == Display.DEFAULT_DISPLAY) {
            return;
        }
        if (!ShellAccess.isReady()) {
            Log.w(TAG,
                    "pending phone freeform cleanup requires shell task control");
            return;
        }
        mLocalDesktopCleanupInFlight = true;
        PhoneDesktopTaskRecovery.recover(
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

    static boolean isPhoneRecoveryComplete(
            final boolean settled,
            final boolean rerunQueued) {
        return settled && !rerunQueued;
    }
}

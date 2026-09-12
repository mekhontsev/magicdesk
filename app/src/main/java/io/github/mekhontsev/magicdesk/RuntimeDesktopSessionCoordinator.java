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
    private java.util.Map<Integer, DesktopDisplayTarget> mOwnedTargets = java.util.Map.of();
    private boolean mRecoverPhoneTasks;
    private final java.util.Map<Integer, RemovedDisplayRecovery> mRemovedDisplayRecoveries =
            new java.util.LinkedHashMap<>();
    private final java.util.Set<Integer> mExpectedRemovedDisplays = new java.util.HashSet<>();
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
        mExpectedRemovedDisplays.clear();
        for (final RemovedDisplayRecovery recovery : mRemovedDisplayRecoveries.values()) { recovery.cancel(); }
        mRemovedDisplayRecoveries.clear();
        mHandler.removeCallbacks(mPhoneTaskRecoveryRunnable);
        mHandler.removeCallbacks(mDisplayRemovalWatchdogRunnable);
        mHandler.removeCallbacks(mLocalDesktopCleanupRunnable);
        mHandler.removeCallbacks(mHomeLeaseReconciliationRunnable);
    }

    boolean ownsExternalDesktop() {
        return mOwnedTargets.keySet().stream().anyMatch(id -> id > Display.DEFAULT_DISPLAY);
    }

    boolean prepareDisplayRemoval(final int displayId) {
        if (mDestroyed || displayId <= Display.DEFAULT_DISPLAY
                || !mOwnedTargets.containsKey(displayId)) {
            return false;
        }
        mExpectedRemovedDisplays.add(displayId);
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

    void handleDisplayStateChanged(final int displayId, final boolean displayRemoved) {
        final DesktopDisplayTarget target = mOwnedTargets.get(displayId);
        final boolean owned = target != null || DesktopRuntimeBridge.hasWorkspace(displayId);
        final boolean expected = displayRemoved && mExpectedRemovedDisplays.remove(displayId);
        if (displayRemoved && owned) {
            if (!expected) { releaseHomeLeaseAfterSessionLoss(displayId); }
            PhoneTouchpadController.release(displayId);
            DesktopRuntimeBridge.closeDesktopWorkspace(displayId);
            if (!expected && displayId > Display.DEFAULT_DISPLAY) {
                beginRemovedDisplayRecovery(displayId, true);
            }
        }
        refreshOwnership();
        if (displayRemoved) { schedulePhoneTaskRecovery(); }
    }

    void refreshOwnership() {
        final java.util.Map<Integer, DesktopDisplayTarget> targets = new java.util.LinkedHashMap<>();
        for (final DesktopSessionSnapshot session : DesktopRuntimeBridge.getWorkspaces()) {
            if (session.target() != null) {
                targets.put(session.target().workspaceDisplayId, session.target());
            }
        }
        for (final RemovedDisplayRecovery recovery : java.util.List.copyOf(mRemovedDisplayRecoveries.values())) {
            if (!canRecover(recovery)) { clearRemovedDisplayRecovery(recovery); }
        }
        if (!targets.isEmpty()) {
            mRecoverPhoneTasks = DesktopCompatibilitySettings.current().enabled(
                    DesktopCompatibilityPolicy.Option.PHONE_TASK_RECOVERY);
        }
        mExpectedRemovedDisplays.removeIf(id -> !targets.containsKey(id) && mDisplayExists.test(id));
        final boolean changed = !mOwnedTargets.equals(targets);
        mOwnedTargets = java.util.Map.copyOf(targets);
        mListener.onOwnershipRefreshed(changed);
        if (changed && DesktopHomeRoleLease.snapshot() != null) {
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

    private void schedulePhoneTaskRecovery(final boolean allowUnsettledDisplayRecovery) {
        if (mDestroyed || mRemovedDisplayRecoveries.isEmpty() || !ShellAccess.isReady()) { return; }
        for (final RemovedDisplayRecovery recovery : mRemovedDisplayRecoveries.values()) {
            recovery.allowUnsettled |= allowUnsettledDisplayRecovery;
        }
        mHandler.removeCallbacks(mPhoneTaskRecoveryRunnable);
        mHandler.post(mPhoneTaskRecoveryRunnable);
    }

    private void beginRemovedDisplayRecovery(final int displayId, final boolean restorePhonePanel) {
        if (mDestroyed || mRemovedDisplayRecoveries.containsKey(displayId)) { return; }
        mRemovedDisplayRecoveries.put(displayId,
                new RemovedDisplayRecovery(displayId, mRecoverPhoneTasks, restorePhonePanel));
        mHandler.removeCallbacks(mDisplayRemovalWatchdogRunnable);
        mHandler.postDelayed(mDisplayRemovalWatchdogRunnable, DISPLAY_REMOVAL_WATCHDOG_MILLIS);
        schedulePhoneTaskRecovery();
    }

    private boolean canRecover(final RemovedDisplayRecovery recovery) {
        // Ordinary-phone normalization must not rewrite a live phone Desktop.
        if (DesktopRuntimeBridge.isLocalDesktopActiveOrStarting()) { recovery.cancel(); }
        return recovery.shouldContinue(DesktopRuntimeBridge.getSessionSnapshot(recovery.displayId));
    }

    private void clearRemovedDisplayRecovery(final RemovedDisplayRecovery recovery) {
        recovery.cancel();
        mRemovedDisplayRecoveries.remove(recovery.displayId, recovery);
        if (mRemovedDisplayRecoveries.isEmpty()) {
            mHandler.removeCallbacks(mPhoneTaskRecoveryRunnable);
            mHandler.removeCallbacks(mDisplayRemovalWatchdogRunnable);
        }
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

    void reconcileHomeLease() {
        if (mDestroyed || !ShellAccess.isReady()
                || DesktopOperations.isSessionTransitionInProgress()) {
            return;
        }
        DesktopHomeRoleLease.State lease =
                DesktopHomeRoleLease.snapshot();
        if (lease == null) {
            return;
        }
        if (mHomeLeaseRecoveryInFlight) { return; }
        if (lease.closingDisplayId >= 0) {
            try {
                DesktopHomeRoleLease.finishSessionClose(lease.targetForDisplay(lease.closingDisplayId));
                lease = DesktopHomeRoleLease.snapshot();
                if (lease == null) { return; }
            } catch (java.io.IOException error) {
                Log.w(TAG, "could not finish workspace HOME release", error);
                return;
            }
        }
        boolean sessionAlive = false;
        for (final DesktopDisplayTarget target : lease.targets) {
            final DesktopSessionSnapshot session = DesktopRuntimeBridge.getSessionSnapshot(target.workspaceDisplayId);
            if (session.target() != null && lease.matches(session.target())) {
                sessionAlive = true;
                continue;
            }
            if (lease.phase == DesktopHomeRoleLease.Phase.ACTIVE
                    && (target.isDefaultWorkspace() || mDisplayExists.test(target.workspaceDisplayId))) {
                mHomeLeaseRecoveryInFlight = true;
                DesktopOperations.recoverDesktopSession(target, lease.policy,
                        success -> mHandler.post(() -> finishHomeLeaseRecovery(target, success)));
                return;
            }
            if (sessionAlive || DesktopRuntimeBridge.hasWorkspaces()) {
                releaseHomeLeaseAfterSessionLoss(target.workspaceDisplayId);
            }
        }
        try {
            if (DesktopHomeRoleLease.reconcile(sessionAlive)) {
                Log.i(TAG, "reconciled stale desktop HOME lease phase=" + lease.phase);
            }
        } catch (java.io.IOException error) {
            Log.w(TAG, "could not reconcile desktop HOME lease", error);
            CompatibilityDiagnostics.record("DESKTOP-HOME-004",
                    "Could not reconcile the temporary Home role", error.getMessage(), error);
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
            DesktopHomeRoleLease.releaseAfterSessionLoss(target.workspaceDisplayId);
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
        if (mDestroyed || !ShellAccess.isReady()) { return; }
        for (final RemovedDisplayRecovery recovery : java.util.List.copyOf(mRemovedDisplayRecoveries.values())) {
            if (!canRecover(recovery)) { clearRemovedDisplayRecovery(recovery); continue; }
            if (!recovery.begin()) { continue; }
            PhoneDesktopTaskRecovery.recoverRemovedDisplay(
                    recovery.required, recovery.displayId, recovery.allowUnsettled,
                    () -> canRecover(recovery),
                    result -> mHandler.post(() -> finishRemovedDisplayRecovery(recovery, result)));
        }
    }

    private void finishRemovedDisplayRecovery(final RemovedDisplayRecovery recovery,
            final PhoneDesktopTaskRecovery.Result result) {
        if (mDestroyed || mRemovedDisplayRecoveries.get(recovery.displayId) != recovery) { return; }
        if (!canRecover(recovery)) { clearRemovedDisplayRecovery(recovery); return; }
        final boolean rerun = recovery.finish(result);
        if (result.pending && !result.cancelled) {
            if (rerun) { schedulePhoneTaskRecovery(); }
            return;
        }
        clearRemovedDisplayRecovery(recovery);
        if (!result.success && !result.cancelled) {
            Log.w(TAG, "removed-display task recovery failed: " + result.message);
            CompatibilityDiagnostics.record("PHONE-TASK-003",
                    "Could not recover tasks after desktop display loss", result.message);
        }
        if (!result.cancelled && recovery.restorePhonePanel && !DesktopRuntimeBridge.hasWorkspaces()) {
            DesktopOperations.restorePhoneAfterExternalDesktop();
        }
    }

    private void cleanupClosedLocalDesktop() {
        final DesktopSessionSnapshot session =
                DesktopRuntimeBridge.getSessionSnapshot(Display.DEFAULT_DISPLAY);
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
            // A new residency on this display supersedes the removed workspace.
            if (session.target() != null || session.hasHost()) { cancel(); }
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

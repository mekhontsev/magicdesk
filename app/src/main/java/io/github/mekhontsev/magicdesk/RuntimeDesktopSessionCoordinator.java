package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Handler;
import android.util.Log;
import android.view.Display;

import java.util.function.IntPredicate;

/** Owns desktop display identity and phone recovery for the runtime service. */
final class RuntimeDesktopSessionCoordinator {
    private static final String TAG = "MagicDeskSessionRuntime";
    private static final long LOCAL_DESKTOP_CLEANUP_DELAY_MILLIS = 500;
    private static final long DISPLAY_REMOVAL_WATCHDOG_MILLIS = 2000;

    interface Listener {
        void onOwnershipRefreshed(boolean changed);
    }

    private final Context mContext;
    private final Handler mHandler;
    private final IntPredicate mDisplayExists;
    private final Listener mListener;
    private int mDesktopDisplayId = Display.INVALID_DISPLAY;
    private boolean mPhoneHomeRecoveryInFlight;
    private boolean mPhoneHomeRecoveryAgain;
    private boolean mAllowUnsettledDisplayRecovery;
    private int mRemovedDesktopDisplayId = Display.INVALID_DISPLAY;
    private int mExpectedRemovedDisplayId = Display.INVALID_DISPLAY;
    private boolean mRestorePhonePanelAfterRecovery;
    private boolean mLocalDesktopCleanupInFlight;
    private boolean mLocalDesktopExitRecoveryPending;
    private boolean mDestroyed;
    private final Runnable mPhoneHomeRecoveryRunnable =
            this::restorePrimaryPhoneHomeIfNeeded;
    private final Runnable mDisplayRemovalWatchdogRunnable = () -> {
        if (mRemovedDesktopDisplayId > Display.DEFAULT_DISPLAY) {
            schedulePhoneHomeRecovery(true);
        }
    };
    private final Runnable mLocalDesktopCleanupRunnable =
            this::cleanupClosedLocalDesktop;

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
            maintainLocalDesktopNavigationGuard();
            scheduleLocalDesktopCleanup();
        }
    }

    void destroy() {
        mDestroyed = true;
        mExpectedRemovedDisplayId = Display.INVALID_DISPLAY;
        mHandler.removeCallbacks(mPhoneHomeRecoveryRunnable);
        mHandler.removeCallbacks(mDisplayRemovalWatchdogRunnable);
        mHandler.removeCallbacks(mLocalDesktopCleanupRunnable);
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
        schedulePhoneHomeRecovery();
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
            PhoneTouchpadController.release(displayId);
            DesktopRuntimeBridge.closeDesktopSession(displayId);
            if (desktopTarget != null
                    && desktopTarget.kind
                            == DesktopDisplayTarget.Kind.SIMULATED) {
                SimulatedDesktopDisplayController.release(displayId);
            }
            if (externalDesktopRemoved) {
                mRemovedDesktopDisplayId = displayId;
                if (!expectedDesktopRemoval) {
                    mRestorePhonePanelAfterRecovery = true;
                }
                scheduleDisplayRemovalWatchdog();
            }
        }
        refreshOwnership();
        if (displayRemoved) {
            schedulePhoneHomeRecovery();
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
    }

    void onTaskStackChanged() {
        if (mRemovedDesktopDisplayId > Display.DEFAULT_DISPLAY
                || mLocalDesktopExitRecoveryPending) {
            schedulePhoneHomeRecovery();
        }
    }

    void onShellReady() {
        maintainLocalDesktopNavigationGuard();
        schedulePhoneHomeRecovery();
    }

    void schedulePhoneHomeRecovery() {
        schedulePhoneHomeRecovery(false);
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

    private void schedulePhoneHomeRecovery(
            final boolean allowUnsettledDisplayRecovery) {
        if (mDestroyed || !ShellAccess.isReady()) {
            return;
        }
        mAllowUnsettledDisplayRecovery |=
                allowUnsettledDisplayRecovery;
        mHandler.removeCallbacks(mPhoneHomeRecoveryRunnable);
        mHandler.post(mPhoneHomeRecoveryRunnable);
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

    private void restorePrimaryPhoneHomeIfNeeded() {
        if (mDestroyed || !ShellAccess.isReady()) {
            return;
        }
        if (mPhoneHomeRecoveryInFlight) {
            mPhoneHomeRecoveryAgain = true;
            return;
        }
        mPhoneHomeRecoveryInFlight = true;
        final boolean allowUnsettledDisplayRecovery =
                mAllowUnsettledDisplayRecovery;
        final boolean includeStrandedDesktop =
                mLocalDesktopExitRecoveryPending;
        final boolean localDesktopExitRecoveryPending =
                mLocalDesktopExitRecoveryPending;
        final int removedDisplayId = mRemovedDesktopDisplayId;
        final DesktopSessionSnapshot session =
                DesktopRuntimeBridge.getSessionSnapshot();
        final boolean localDesktopActive =
                session.isLocalActiveOrStarting();
        PhoneHomeRecoveryController.restoreIfNeeded(
                includeStrandedDesktop,
                removedDisplayId,
                localDesktopActive,
                allowUnsettledDisplayRecovery,
                settled -> mHandler.post(() -> {
                    mPhoneHomeRecoveryInFlight = false;
                    final boolean recoveryComplete =
                            isPhoneRecoveryComplete(
                                    settled, mPhoneHomeRecoveryAgain);
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
                    if (!mDestroyed && recoveryComplete
                            && localDesktopExitRecoveryPending
                            && mLocalDesktopExitRecoveryPending) {
                        if (!DesktopRuntimeBridge
                                .isLocalDesktopActiveOrStarting()) {
                            LocalDesktopSessionState.clearCleanupPending(
                                    mContext);
                        }
                        mLocalDesktopExitRecoveryPending = false;
                    }
                    if (!mDestroyed && mPhoneHomeRecoveryAgain) {
                        mPhoneHomeRecoveryAgain = false;
                        schedulePhoneHomeRecovery();
                    }
                }));
    }

    private void cleanupClosedLocalDesktop() {
        final DesktopSessionSnapshot session =
                DesktopRuntimeBridge.getSessionSnapshot();
        if (mDestroyed
                || mLocalDesktopCleanupInFlight
                || mLocalDesktopExitRecoveryPending
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
        final long generation =
                LocalDesktopNavigationController.currentGeneration();
        LocalDesktopNavigationController.cleanupClosedSession(
                generation,
                (completed, success, message) -> {
                    mLocalDesktopCleanupInFlight = false;
                    if (mDestroyed) {
                        return;
                    }
                    if (!success) {
                        Log.w(TAG,
                                "phone desktop recovery failed: " + message);
                        CompatibilityDiagnostics.record(
                                "PHONE-HOME-003",
                                "Could not clean local desktop tasks before"
                                        + " returning to the phone launcher",
                                message);
                        return;
                    }
                    if (!completed) {
                        final DesktopSessionSnapshot currentSession =
                                DesktopRuntimeBridge.getSessionSnapshot();
                        if (currentSession.activeDisplayId()
                                != Display.DEFAULT_DISPLAY) {
                            scheduleLocalDesktopCleanup();
                        }
                        return;
                    }
                    mLocalDesktopExitRecoveryPending = true;
                    schedulePhoneHomeRecovery();
                    Log.i(TAG,
                            "recovered phone desktop tasks; restoring Home"
                                    + " after local desktop");
                });
    }

    private void maintainLocalDesktopNavigationGuard() {
        final DesktopSessionSnapshot session =
                DesktopRuntimeBridge.getSessionSnapshot();
        if (!ShellAccess.isReady()
                || !LocalDesktopSessionState.isCleanupPending(mContext)
                || session.activeDisplayId() != Display.DEFAULT_DISPLAY) {
            return;
        }
        LocalDesktopNavigationController.acquire(
                (generation, success, message) -> {
                    if (!success) {
                        CompatibilityDiagnostics.record(
                                "PHONE-HOME-005",
                                "Could not maintain the local desktop"
                                        + " navigation guard",
                                message);
                    }
                });
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

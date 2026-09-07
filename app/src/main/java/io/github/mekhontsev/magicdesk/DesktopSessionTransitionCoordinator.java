package io.github.mekhontsev.magicdesk;

import android.util.Log;
import android.view.Display;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Owns serialized desktop activation and close transitions. */
final class DesktopSessionTransitionCoordinator {
    interface CompletionCallback {
        void onComplete(boolean success);
    }

    private static final String TAG = "MagicDeskDesktopOps";
    private static final long SESSION_CLOSE_TIMEOUT_SECONDS = 15L;

    private final SerializedDesktopOperationQueue mOperations;
    private final PlatformFeatures mFeatures;
    private final PlatformProjectionDriver mProjection;
    private final DesktopTransitionGate mGate = new DesktopTransitionGate();

    DesktopSessionTransitionCoordinator(
            final SerializedDesktopOperationQueue operations,
            final PlatformFeatures features,
            final PlatformProjectionDriver projection) {
        if (operations == null || features == null || projection == null) {
            throw new IllegalArgumentException(
                    "desktop transition dependencies are required");
        }
        mOperations = operations;
        mFeatures = features;
        mProjection = projection;
    }

    void showPreferredDesktop() {
        enqueueDesktopStart(this::showPreferredDesktopNow);
    }

    void showWiredDesktop() {
        showWiredDesktop(DesktopSessionPolicy.USER);
    }

    void showWiredDesktop(final DesktopSessionPolicy policy) {
        if (!mFeatures.supportsDisplay(
                DesktopDisplayTarget.Kind.WIRED)) {
            throw new IllegalStateException(
                    "wired displays are unsupported by the current platform");
        }
        enqueueDesktopStart(
                () -> DesktopDisplayDrivers.activateWired(null, policy));
    }

    void showDesktop(final DesktopDisplayTarget target) {
        showDesktop(target, DesktopSessionPolicy.USER);
    }

    void showDesktop(
            final DesktopDisplayTarget target,
            final DesktopSessionPolicy policy) {
        if (target == null
                || target.displayId < Display.DEFAULT_DISPLAY) {
            throw new IllegalArgumentException(
                    "a prepared desktop display target is required");
        }
        if (!mFeatures.supportsDisplay(target.kind)) {
            throw new IllegalStateException(
                    "display target is unsupported by the current platform");
        }
        enqueueDesktopStart(() -> DesktopDisplayDrivers.forTarget(target)
                .showReady(null, target, policy));
    }

    void closeDesktop(
            final DesktopDisplayTarget target,
            final DesktopCloseMode mode,
            final CompletionCallback callback) {
        if (target == null || mode == null) {
            complete(callback, false);
            return;
        }
        if (!mGate.begin(DesktopTransitionGate.Operation.CLOSE)) {
            Log.i(TAG, "Another desktop transition is already in progress");
            complete(callback, false);
            return;
        }
        mOperations.execute(() -> beginDesktopClose(
                target, mode, callback));
    }

    private void finishDesktopClose(
            final CompletionCallback callback,
            final boolean success) {
        mGate.finish(DesktopTransitionGate.Operation.CLOSE);
        complete(callback, success);
    }

    void updateCaptionTransport(final DesktopDisplayTarget target) {
        mOperations.execute(() -> {
            final PlatformProjectionDriver.Transport transport =
                    target == null
                                    || target.displayId
                                            <= Display.DEFAULT_DISPLAY
                            ? PlatformProjectionDriver.Transport.NONE
                            : transportFor(target.kind);
            mProjection.setCaptionTransport(transport);
        });
    }

    private void beginDesktopClose(
            final DesktopDisplayTarget target,
            final DesktopCloseMode mode,
            final CompletionCallback callback) {
        // HOME ownership is the outer session lease. Release it before any
        // task, input, or display teardown so a partial close cannot trap the
        // user in a launcher that Android keeps restarting.
        DesktopHomeRoleLease.RestoredHomePresentation homePresentation = null;
        boolean homeReleased = true;
        try {
            homePresentation =
                    DesktopHomeRoleLease.releaseForSessionClose(target);
        } catch (java.io.IOException | RuntimeException error) {
            homeReleased = false;
            Log.w(TAG, "Could not restore HOME before desktop close", error);
            CompatibilityDiagnostics.record(
                    "DESKTOP-HOME-002",
                    "Could not restore the previous Home app",
                    error.getMessage(),
                    error);
        }
        final DesktopHomeRoleLease.RestoredHomePresentation presentation =
                homePresentation;
        final boolean released = homeReleased;
        MagicDeskRuntime.releaseDesktopInput(target.displayId,
                () -> mOperations.execute(() -> parkAndClose(
                        target, mode, presentation, released, callback)));
    }

    private void parkAndClose(
            final DesktopDisplayTarget target,
            final DesktopCloseMode mode,
            final DesktopHomeRoleLease.RestoredHomePresentation presentation,
            final boolean homeReleased,
            final CompletionCallback callback) {
        try {
            MagicDeskRuntime.disableExternalTaskMigrationProtection();
        } catch (RuntimeException error) {
            recordCloseFailure("Could not release desktop task protection", error);
        }
        if (!mode.parkTasks) {
            finishDesktopSessionClose(
                    target, mode, presentation, homeReleased, callback);
            return;
        }
        try {
            MagicDeskRuntime.parkDesktopTasks(target, parked -> {
                if (!parked) {
                    Log.w(TAG, "Desktop close continues after partial task parking");
                }
                mOperations.execute(() -> finishDesktopSessionClose(
                        target, mode, presentation, homeReleased, callback));
            });
        } catch (RuntimeException error) {
            recordCloseFailure("Could not park desktop tasks", error);
            finishDesktopSessionClose(
                    target, mode, presentation, homeReleased, callback);
        }
    }

    private void finishDesktopSessionClose(
            final DesktopDisplayTarget target,
            final DesktopCloseMode mode,
            final DesktopHomeRoleLease.RestoredHomePresentation
                    homePresentation,
            final boolean homeReleased,
            final CompletionCallback callback) {
        boolean success = homeReleased;
        try {
            if (target.kind == DesktopDisplayTarget.Kind.SIMULATED) {
                success &= removeSimulatedDesktop(target.displayId);
            } else {
                success &= closeDesktopSessionAndWait(target.displayId);
            }
        } catch (RuntimeException error) {
            success = false;
            recordCloseFailure("Desktop close failed", error);
        } finally {
            // A failed display removal is not a request to reopen its host.
            // Keep the quiescence gate, but always release the local session.
            if (DesktopRuntimeBridge.getActiveDesktopDisplayId()
                    == target.displayId) {
                try {
                    success &= closeDesktopSessionAndWait(target.displayId);
                } catch (RuntimeException error) {
                    success = false;
                    recordCloseFailure("Could not release desktop host", error);
                }
            }
        }
        if (mode.parkTasks
                && target.displayId > Display.DEFAULT_DISPLAY) {
            // A wired display can stay connected after Close. Reconcile its
            // returned phone tasks now, without relying on display removal.
            try {
                final PhoneDesktopTaskRecovery.Result recovery =
                        PhoneDesktopTaskRecovery.recoverBlocking(
                                () -> !DesktopRuntimeBridge
                                        .isLocalDesktopActiveOrStarting());
                if (!recovery.success || recovery.cancelled) {
                    success = false;
                    CompatibilityDiagnostics.record(
                            "PHONE-TASK-005",
                            "Could not reconcile phone tasks after desktop close",
                            recovery.message);
                }
            } catch (RuntimeException error) {
                success = false;
                recordCloseFailure("Could not recover phone tasks", error);
            }
        }
        try {
            if (homeReleased) {
                DesktopHomeRoleLease.presentRestoredHome(homePresentation);
            } else {
                DesktopHomeRoleLease.releaseAfterSessionLoss(target.displayId);
            }
        } catch (java.io.IOException | RuntimeException error) {
            success = false;
            Log.w(TAG, "Could not present HOME after desktop close", error);
            CompatibilityDiagnostics.record(
                    "DESKTOP-HOME-008",
                    "Could not show the restored Home app",
                    error.getMessage(),
                    error);
        }
        try {
            if (shouldOpenPhonePanel(
                    mode, ControlActivity.isControlPanelVisible())) {
                PhoneControlPanelLauncher.openOnPhoneWithShell();
            }
        } catch (RuntimeException error) {
            success = false;
            recordCloseFailure("Could not show phone control panel", error);
        } finally {
            finishDesktopClose(callback, success);
        }
    }

    private static void recordCloseFailure(
            final String message, final RuntimeException error) {
        Log.w(TAG, message, error);
        CompatibilityDiagnostics.record(
                "DESKTOP-CLOSE-001", message, error.getMessage(), error);
    }

    private static boolean removeSimulatedDesktop(final int displayId) {
        final CountDownLatch prepared = new CountDownLatch(1);
        DesktopRuntimeBridge.prepareDesktopSessionRemoval(
                displayId, prepared::countDown);
        final boolean ready;
        try {
            ready = prepared.await(
                    SESSION_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Simulated display removal interrupted for display="
                    + displayId, error);
            return false;
        }
        if (!ready) {
            Log.w(TAG, "Simulated display removal preparation timed out for "
                    + "display=" + displayId);
            return false;
        }
        return SimulatedDesktopDisplayController.release(displayId);
    }

    private static boolean closeDesktopSessionAndWait(
            final int displayId) {
        final CountDownLatch closed = new CountDownLatch(1);
        DesktopRuntimeBridge.closeDesktopSession(displayId, closed::countDown);
        try {
            if (closed.await(
                    SESSION_CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return true;
            }
            Log.w(TAG, "Desktop session close timed out for display="
                    + displayId);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Desktop session close interrupted for display="
                    + displayId, error);
        }
        return false;
    }

    static boolean shouldOpenPhonePanel(
            final DesktopCloseMode mode,
            final boolean panelVisible) {
        return mode.showControlPanel && !panelVisible;
    }

    private void showPreferredDesktopNow() {
        final boolean wiredSupported = mFeatures.supportsDisplay(
                DesktopDisplayTarget.Kind.WIRED);
        final boolean wirelessSupported = mFeatures.supportsDisplay(
                DesktopDisplayTarget.Kind.WIRELESS);
        final DesktopDisplayTarget activeTarget =
                DesktopRuntimeBridge.getActiveDesktopTarget();
        if (activeTarget != null
                && activeTarget.displayId > Display.DEFAULT_DISPLAY
                && mFeatures.supportsDisplay(activeTarget.kind)) {
            DesktopDisplayDrivers.forTarget(activeTarget)
                    .showReady(null, activeTarget, DesktopSessionPolicy.USER);
            return;
        }
        if (wiredSupported
                && ExternalDisplayController.findExternalDisplayId()
                        > Display.DEFAULT_DISPLAY) {
            DesktopDisplayDrivers.activateWired(null);
            return;
        }
        final int wirelessDisplayId =
                ExternalDisplayController.findWirelessDisplayId();
        if (wirelessSupported
                && wirelessDisplayId > Display.DEFAULT_DISPLAY) {
            DesktopDisplayDrivers
                    .forKind(DesktopDisplayTarget.Kind.WIRELESS)
                    .showReady(
                            null,
                            DesktopDisplayTarget.wireless(
                                    wirelessDisplayId));
            return;
        }
        if (mFeatures.supportsDisplay(
                DesktopDisplayTarget.Kind.SIMULATED)) {
            SimulatedDesktopDisplayController.show();
            return;
        }
        Log.i(TAG, "No desktop display is available");
    }

    private void enqueueDesktopStart(final Runnable action) {
        if (mGate.isActive(DesktopTransitionGate.Operation.CLOSE)) {
            Log.i(TAG, "Desktop close is already in progress");
            return;
        }
        if (!mGate.begin(DesktopTransitionGate.Operation.START)) {
            Log.i(TAG, "Another desktop transition is already in progress");
            return;
        }
        mOperations.execute(() -> {
            try {
                action.run();
            } finally {
                mGate.finish(DesktopTransitionGate.Operation.START);
            }
        });
    }

    private static PlatformProjectionDriver.Transport transportFor(
            final DesktopDisplayTarget.Kind kind) {
        if (kind == DesktopDisplayTarget.Kind.WIRED) {
            return PlatformProjectionDriver.Transport.WIRED;
        }
        if (kind == DesktopDisplayTarget.Kind.WIRELESS) {
            return PlatformProjectionDriver.Transport.WIRELESS;
        }
        return PlatformProjectionDriver.Transport.NONE;
    }

    private static void complete(
            final CompletionCallback callback,
            final boolean success) {
        if (callback != null) {
            callback.onComplete(success);
        }
    }
}

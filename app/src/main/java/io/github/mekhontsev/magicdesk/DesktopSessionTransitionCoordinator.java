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
            final boolean restorePhonePanel,
            final CompletionCallback callback) {
        if (target == null) {
            complete(callback, false);
            return;
        }
        if (!mGate.begin(DesktopTransitionGate.Operation.CLOSE)) {
            Log.i(TAG, "Another desktop transition is already in progress");
            complete(callback, false);
            return;
        }
        mOperations.execute(() -> beginDesktopClose(
                target, restorePhonePanel, callback));
    }

    private void finishDesktopClose(
            final CompletionCallback callback,
            final boolean success) {
        mGate.finish(DesktopTransitionGate.Operation.CLOSE);
        if (!success) {
            MagicDeskRuntime.restoreExternalTaskMigrationProtection();
        }
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
            final boolean restorePhonePanel,
            final CompletionCallback callback) {
        // HOME ownership is the outer session lease. Release it before any
        // task, input, or display teardown so a partial close cannot trap the
        // user in a launcher that Android keeps restarting.
        final DesktopHomeRoleLease.RestoredHomePresentation homePresentation;
        try {
            homePresentation =
                    DesktopHomeRoleLease.releaseForSessionClose(target);
        } catch (java.io.IOException error) {
            Log.w(TAG, "Could not restore HOME before desktop close", error);
            CompatibilityDiagnostics.record(
                    "DESKTOP-HOME-002",
                    "Could not restore the previous Home app",
                    error.getMessage(),
                    error);
            finishDesktopClose(callback, false);
            return;
        }
        MagicDeskRuntime.disableExternalTaskMigrationProtection();
        if (!restorePhonePanel) {
            finishDesktopSessionClose(
                    target, false, homePresentation, callback);
            return;
        }
        MagicDeskRuntime.parkDesktopTasks(target, parked -> {
            if (!parked) {
                Log.w(TAG,
                        "Desktop close continues after partial task parking");
            }
            mOperations.execute(() -> finishDesktopSessionClose(
                    target, true, homePresentation, callback));
        });
    }

    private void finishDesktopSessionClose(
            final DesktopDisplayTarget target,
            final boolean restorePhonePanel,
            final DesktopHomeRoleLease.RestoredHomePresentation
                    homePresentation,
            final CompletionCallback callback) {
        boolean success;
        try {
            if (target.kind == DesktopDisplayTarget.Kind.SIMULATED) {
                MagicDeskRuntime.prepareDesktopDisplayRemoval(
                        target.displayId);
                success = removeSimulatedDesktop(target.displayId);
            } else {
                success = closeDesktopSessionAndWait(target.displayId);
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Desktop close failed", error);
            finishDesktopClose(callback, false);
            return;
        }
        try {
            DesktopHomeRoleLease.presentRestoredHome(homePresentation);
        } catch (java.io.IOException error) {
            success = false;
            Log.w(TAG, "Could not present HOME after desktop close", error);
            CompatibilityDiagnostics.record(
                    "DESKTOP-HOME-008",
                    "Could not show the restored Home app",
                    error.getMessage(),
                    error);
        }
        if (shouldOpenPhonePanel(
                restorePhonePanel,
                ControlActivity.isControlPanelVisible())) {
            PhoneControlPanelLauncher.openOnPhoneWithShell();
        }
        finishDesktopClose(callback, success);
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
            resumeAfterFailedDisplayRemoval(displayId);
            return false;
        }
        if (!ready) {
            Log.w(TAG, "Simulated display removal preparation timed out for "
                    + "display=" + displayId);
            resumeAfterFailedDisplayRemoval(displayId);
            return false;
        }
        if (SimulatedDesktopDisplayController.release(displayId)) {
            return true;
        }
        resumeAfterFailedDisplayRemoval(displayId);
        return false;
    }

    private static void resumeAfterFailedDisplayRemoval(
            final int displayId) {
        MagicDeskRuntime.cancelDesktopDisplayRemoval(displayId);
        DesktopRuntimeBridge.resumeDesktopSessionAfterFailedRemoval(displayId);
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
            final boolean restorePhonePanel,
            final boolean panelVisible) {
        return restorePhonePanel && !panelVisible;
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

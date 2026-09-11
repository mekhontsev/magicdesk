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
    private final PlatformPhoneUiDriver mPhoneUi;
    private final DesktopTransitionGate mGate = new DesktopTransitionGate();

    DesktopSessionTransitionCoordinator(
            final SerializedDesktopOperationQueue operations,
            final PlatformFeatures features,
            final PlatformProjectionDriver projection,
            final PlatformPhoneUiDriver phoneUi) {
        if (operations == null || features == null || projection == null
                || phoneUi == null) {
            throw new IllegalArgumentException(
                    "desktop transition dependencies are required");
        }
        mOperations = operations;
        mFeatures = features;
        mProjection = projection;
        mPhoneUi = phoneUi;
    }

    void showPreferredDesktop() {
        enqueueDesktopStart(this::showPreferredDesktopNow);
    }

    void showWiredDesktop() {
        showWiredDesktop(DesktopSessionPolicy.USER);
    }

    void showWiredDesktop(final DesktopSessionPolicy policy) {
        if (!mFeatures.supportsDisplay(
                DesktopDisplayOutput.Kind.WIRED)) {
            throw new IllegalStateException(
                    "wired displays are unsupported by the current platform");
        }
        enqueueDesktopStart(
                () -> DesktopDisplayDrivers.activateWired(null, policy));
    }

    void showDesktop(final DesktopDisplayInfo display) {
        if (display == null || !display.canHostDesktop) {
            throw new IllegalArgumentException("an available desktop display is required");
        }
        if (!mFeatures.supportsDisplay(display.target().output.kind)) {
            throw new IllegalStateException("display target is unsupported by the current platform");
        }
        enqueueDesktopStart(() -> {
            try {
                final DesktopDisplayTarget target =
                        DesktopDisplayCatalog.require(display.id, display.uniqueId).target();
                DesktopDisplayDrivers.forTarget(target).showReady(null, target, DesktopSessionPolicy.USER);
            } catch (java.io.IOException error) {
                CompatibilityDiagnostics.record("DISPLAY-START-001",
                        "Selected display is unavailable", error.getMessage(), error);
            }
        });
    }

    void showDesktop(final DesktopDisplayTarget target) {
        showDesktop(target, DesktopSessionPolicy.USER);
    }

    void showDesktop(
            final DesktopDisplayTarget target,
            final DesktopSessionPolicy policy) {
        if (target == null
                || target.workspaceDisplayId < Display.DEFAULT_DISPLAY) {
            throw new IllegalArgumentException(
                    "a prepared desktop display target is required");
        }
        if (!mFeatures.supportsDisplay(target.output.kind)) {
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

    void removeVirtualDisplay(final int displayId, final String uniqueId,
            final CompletionCallback callback) {
        final DesktopDisplayTarget active = DesktopRuntimeBridge.getActiveDesktopTarget();
        if (active != null && active.workspaceDisplayId == displayId) {
            // Revalidate ownership before changing a session. The second call
            // takes the gate again; a competing Start rejects deletion safely.
            mOperations.execute(() -> {
                try {
                    DesktopDisplayCatalog.requireOwned(displayId, uniqueId);
                    closeDesktop(active, DesktopCloseMode.CONTROL_PANEL, success -> {
                        if (success) {
                            removeVirtualDisplay(displayId, uniqueId, callback);
                        } else {
                            complete(callback, false);
                        }
                    });
                } catch (java.io.IOException | RuntimeException error) {
                    complete(callback, false);
                }
            });
            return;
        }
        if (!mGate.begin(DesktopTransitionGate.Operation.DISPLAY)) {
            complete(callback, false);
            return;
        }
        mOperations.execute(() -> {
            boolean success = false;
            try {
                final DesktopDisplayInfo display = DesktopDisplayCatalog.requireOwned(displayId, uniqueId);
                if (DesktopRuntimeBridge.getActiveDesktopDisplayId() == displayId) {
                    throw new IllegalStateException("display still has an active desktop");
                }
                final WindowTransitionHealthDiagnostics.IdleResult idle =
                        WindowTransitionHealthDiagnostics.awaitDisplayIdle(
                                MagicDeskApplication.applicationContext(), displayId, 5_000L);
                if (!idle.idle) {
                    throw new IllegalStateException("display transitions are not idle: " + idle.detail);
                }
                if ("overlay".equals(display.source)) {
                    success = SimulatedDesktopDisplayController.release(displayId);
                } else {
                    ShellAccess.removeVirtualDisplay(display);
                    success = true;
                }
            } catch (java.io.IOException | RuntimeException error) {
                CompatibilityDiagnostics.record("DISPLAY-VIRTUAL-002",
                        "Could not remove virtual display", error.getMessage(), error);
            } finally {
                mGate.finish(DesktopTransitionGate.Operation.DISPLAY);
                complete(callback, success);
            }
        });
    }

    private void finishDesktopClose(
            final CompletionCallback callback,
            final boolean success) {
        mGate.finish(DesktopTransitionGate.Operation.CLOSE);
        complete(callback, success);
    }

    boolean isSessionTransitionInProgress() {
        return mGate.isActive(DesktopTransitionGate.Operation.START)
                || mGate.isActive(DesktopTransitionGate.Operation.CLOSE)
                || mGate.isActive(DesktopTransitionGate.Operation.DISPLAY);
    }

    void restorePhoneAfterExternalDesktop() {
        mOperations.execute(() -> {
            // A new Start may have been queued after the removal callback.
            final DesktopSessionSnapshot session = DesktopRuntimeBridge.getSessionSnapshot();
            if (isSessionTransitionInProgress() || session.target() != null || session.hasHost()) {
                return;
            }
            mPhoneUi.setPhoneScreenOff(false, Display.INVALID_DISPLAY);
            PhoneControlPanelLauncher.openOnPhoneWithShell();
        });
    }

    void updateCaptionTransport(final DesktopDisplayTarget target) {
        mOperations.execute(() -> {
            final PlatformProjectionDriver.Transport transport =
                    target == null
                                    || target.output.displayId
                                            <= Display.DEFAULT_DISPLAY
                            ? PlatformProjectionDriver.Transport.NONE
                            : transportFor(target.output.kind);
            mProjection.setCaptionTransport(transport);
        });
    }

    private void beginDesktopClose(
            final DesktopDisplayTarget target,
            final DesktopCloseMode mode,
            final CompletionCallback callback) {
        final DesktopSessionEndPlan plan;
        try {
            plan = DesktopSessionEndPlan.create(DesktopRuntimeBridge.getSessionSnapshot().workspace(),
                    target, mode, DesktopCompatibilitySettings.current().enabled(
                            DesktopCompatibilityPolicy.Option.PHONE_TASK_RECOVERY));
        } catch (RuntimeException error) {
            recordCloseFailure("Could not prepare session close", error);
            finishDesktopClose(callback, false);
            return;
        }
        // HOME ownership is the outer session lease. Release it before any
        // task, input, or display teardown so a partial close cannot trap the
        // user in a launcher that Android keeps restarting.
        boolean homeReleased = true;
        try {
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
        // Closing a still-connected external desktop must release its phone
        // power override too; neither runtime shutdown nor display loss follows.
        boolean phoneRestored = true;
        try {
            phoneRestored = !mPhoneUi.isPhoneScreenControlActive()
                    || mPhoneUi.setPhoneScreenOff(false, Display.INVALID_DISPLAY);
            if (!phoneRestored) {
                recordCloseFailure("Could not restore phone screen",
                        new IllegalStateException("phone screen restore failed"));
            }
        } catch (RuntimeException error) {
            phoneRestored = false;
            recordCloseFailure("Could not restore phone screen", error);
        }
        final boolean prepared = homeReleased && phoneRestored;
        MagicDeskRuntime.releaseDesktopInput(plan.workspace.workspaceDisplayId,
                () -> mOperations.execute(() -> parkAndClose(
                        plan, prepared, callback)));
    }

    private void parkAndClose(
            final DesktopSessionEndPlan plan,
            final boolean prepared,
            final CompletionCallback callback) {
        try {
            MagicDeskRuntime.disableExternalTaskMigrationProtection();
        } catch (RuntimeException error) {
            recordCloseFailure("Could not release desktop task protection", error);
        }
        if (!plan.returnsTasks()) {
            finishDesktopSessionClose(
                    plan, prepared, callback);
            return;
        }
        try {
            MagicDeskRuntime.parkDesktopTasks(plan.workspace, parked -> {
                if (!parked) {
                    Log.w(TAG, "Desktop close continues after partial task parking");
                }
                mOperations.execute(() -> finishDesktopSessionClose(
                        plan, prepared, callback));
            });
        } catch (RuntimeException error) {
            recordCloseFailure("Could not park desktop tasks", error);
            finishDesktopSessionClose(
                    plan, prepared, callback);
        }
    }

    private void finishDesktopSessionClose(
            final DesktopSessionEndPlan plan,
            final boolean prepared,
            final CompletionCallback callback) {
        final DesktopDisplayTarget target = plan.workspace;
        boolean success = prepared;
        try {
            success &= closeDesktopSessionAndWait(target.workspaceDisplayId);
        } catch (RuntimeException error) {
            success = false;
            recordCloseFailure("Desktop close failed", error);
        }
        if (plan.needsPhoneRecovery()) {
            // Close owns recovery through its terminal result. If the display
            // disappeared, include tasks still retained there by SystemUI;
            // the recovery's bounded waits cover their late migration too.
            try {
                final PhoneDesktopTaskRecovery.Result recovery =
                        PhoneDesktopTaskRecovery.recoverBlocking(
                                plan.recoverPhoneTasks,
                                ExternalDisplayController.displayExists(target.workspaceDisplayId)
                                        ? Display.INVALID_DISPLAY : target.workspaceDisplayId,
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
            SecondaryDisplayWindowing.release(target.workspaceDisplayId);
        } catch (java.io.IOException | RuntimeException error) {
            success = false;
            CompatibilityDiagnostics.record("DISPLAY-WINDOWING-001",
                    "Could not restore secondary display default mode",
                    error.getMessage(), error);
        }
        try {
            final DesktopHomeRoleLease.RestoredHomePresentation presentation =
                    DesktopHomeRoleLease.finishSessionClose(target);
            DesktopHomeRoleLease.presentRestoredHome(presentation);
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
                    plan.destination, ControlActivity.isControlPanelVisible())) {
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
        return mode == DesktopCloseMode.CONTROL_PANEL && !panelVisible;
    }

    private void showPreferredDesktopNow() {
        final boolean wiredSupported = mFeatures.supportsDisplay(
                DesktopDisplayOutput.Kind.WIRED);
        final boolean wirelessSupported = mFeatures.supportsDisplay(
                DesktopDisplayOutput.Kind.WIRELESS);
        final DesktopDisplayTarget activeTarget =
                DesktopRuntimeBridge.getActiveDesktopTarget();
        if (activeTarget != null
                && activeTarget.workspaceDisplayId > Display.DEFAULT_DISPLAY
                && mFeatures.supportsDisplay(activeTarget.output.kind)) {
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
                    .forKind(DesktopDisplayOutput.Kind.WIRELESS)
                    .showReady(
                            null,
                            DesktopDisplayTarget.wireless(
                                    wirelessDisplayId));
            return;
        }
        if (mFeatures.supportsDisplay(
                DesktopDisplayOutput.Kind.SIMULATED)) {
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
            final DesktopDisplayOutput.Kind kind) {
        if (kind == DesktopDisplayOutput.Kind.WIRED) {
            return PlatformProjectionDriver.Transport.WIRED;
        }
        if (kind == DesktopDisplayOutput.Kind.WIRELESS) {
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

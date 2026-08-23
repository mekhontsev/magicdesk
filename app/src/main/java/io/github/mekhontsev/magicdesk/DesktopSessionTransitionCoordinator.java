package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.util.Log;
import android.view.Display;

/** Owns serialized desktop activation, close, and mirror transitions. */
final class DesktopSessionTransitionCoordinator {
    interface CompletionCallback {
        void onComplete(boolean success);
    }

    private static final String TAG = "MagicDeskConsoleSwitcher";

    private final SerializedDesktopOperationQueue mOperations;
    private final Context mContext;
    private final PlatformFeatures mFeatures;
    private final PlatformProjectionDriver mProjection;
    private final DesktopTransitionGate mGate = new DesktopTransitionGate();

    DesktopSessionTransitionCoordinator(
            final SerializedDesktopOperationQueue operations,
            final Context context,
            final PlatformFeatures features,
            final PlatformProjectionDriver projection) {
        if (operations == null || context == null || features == null
                || projection == null) {
            throw new IllegalArgumentException(
                    "desktop transition dependencies are required");
        }
        mOperations = operations;
        mContext = context.getApplicationContext();
        mFeatures = features;
        mProjection = projection;
    }

    void showPreferredDesktop() {
        enqueueDesktopStart(this::showPreferredDesktopNow);
    }

    void showWiredDesktop() {
        if (!mFeatures.supportsDisplay(
                DesktopDisplayTarget.Kind.WIRED)) {
            throw new IllegalStateException(
                    "wired displays are unsupported by the current platform");
        }
        enqueueDesktopStart(
                () -> DesktopDisplayDrivers.activateWired(null));
    }

    void showDesktop(final DesktopDisplayTarget target) {
        if (target == null
                || target.displayId <= Display.DEFAULT_DISPLAY) {
            throw new IllegalArgumentException(
                    "a prepared external display target is required");
        }
        if (!mFeatures.supportsDisplay(target.kind)) {
            throw new IllegalStateException(
                    "display target is unsupported by the current platform");
        }
        enqueueDesktopStart(() -> DesktopDisplayDrivers.forTarget(target)
                .showReady(null, target));
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
        MagicDeskRuntime.disableExternalTaskMigrationProtection();
        final Runnable close = () -> mOperations.execute(() -> {
            final boolean success;
            try {
                success = closeDesktopNow(target, restorePhonePanel);
            } catch (RuntimeException error) {
                Log.w(TAG, "Desktop close failed", error);
                finishDesktopClose(callback, false);
                return;
            }
            finishDesktopClose(callback, success);
        });
        if (restorePhonePanel) {
            MagicDeskRuntime.parkDesktopTasks(target, parked -> {
                if (!parked) {
                    Log.w(TAG,
                            "Desktop close continues after partial task parking");
                }
                close.run();
            });
        } else {
            close.run();
        }
    }

    void switchToMirror(
            final boolean restorePhonePanel,
            final CompletionCallback callback) {
        if (!mGate.begin(
                DesktopTransitionGate.Operation.MODE_TRANSITION)) {
            Log.i(TAG, "Another desktop transition is already in progress");
            complete(callback, false);
            return;
        }
        if (restorePhonePanel) {
            ControlActivity.finishActiveForMirrorTransition();
        }
        mOperations.execute(() -> {
            boolean success = false;
            try {
                success = switchToMirrorNow();
            } catch (RuntimeException error) {
                Log.w(TAG, "Mirror transition failed", error);
            } finally {
                if (restorePhonePanel) {
                    PhoneControlPanelLauncher.openOnPhoneWithShell();
                }
                mGate.finish(
                        DesktopTransitionGate.Operation.MODE_TRANSITION);
            }
            complete(callback, success);
        });
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

    int activeDesktopDisplayId() {
        return mProjection.activeDesktopDisplayId(mContext);
    }

    void updateCaptionTransport(
            final int displayId,
            final boolean wiredDesktop) {
        mOperations.execute(() -> {
            final PlatformProjectionDriver.Transport transport;
            if (displayId <= Display.DEFAULT_DISPLAY) {
                transport = PlatformProjectionDriver.Transport.NONE;
            } else if (wiredDesktop) {
                transport = PlatformProjectionDriver.Transport.WIRED;
            } else if (ConsoleDisplayController.findWirelessDisplayId()
                    == displayId) {
                transport = PlatformProjectionDriver.Transport.WIRELESS;
            } else {
                transport = PlatformProjectionDriver.Transport.NONE;
            }
            mProjection.setCaptionTransport(transport);
        });
    }

    private boolean closeDesktopNow(
            final DesktopDisplayTarget target,
            final boolean restorePhonePanel) {
        final PlatformProjectionDriver.Transport transport =
                transportFor(target.kind);
        final boolean platformOwned = shouldReturnTransportToMirror(
                target,
                transport != PlatformProjectionDriver.Transport.NONE
                        && mProjection.ownsTransportLifecycle(transport));
        if (platformOwned && restorePhonePanel) {
            ControlActivity.finishActiveForMirrorTransition();
        }
        final boolean success;
        if (platformOwned) {
            success = switchToMirrorNow();
        } else {
            MagicDeskRuntime.prepareDesktopDisplayRemoval(
                    target.displayId);
            DesktopRuntimeBridge.closeDesktopSession(target.displayId);
            if (target.kind == DesktopDisplayTarget.Kind.SIMULATED) {
                SimulatedDesktopDisplayController.release(target.displayId);
            }
            success = true;
        }
        if (shouldOpenPhonePanel(
                restorePhonePanel,
                platformOwned,
                ControlActivity.isControlPanelVisible())) {
            PhoneControlPanelLauncher.openOnPhoneWithShell();
        }
        return success;
    }

    static boolean shouldOpenPhonePanel(
            final boolean restorePhonePanel,
            final boolean platformOwned,
            final boolean panelVisible) {
        // A system-owned extended display remains alive after Close Desktop.
        // Reuse its existing phone panel instead of launching a duplicate;
        // Nubia may force-stop the package when that duplicate is started
        // while the extended display is still active.
        return restorePhonePanel && (platformOwned || !panelVisible);
    }

    static boolean shouldReturnTransportToMirror(
            final DesktopDisplayTarget target,
            final boolean platformOwnsLifecycle) {
        if (!platformOwnsLifecycle || target == null) {
            return false;
        }
        // A wired display that already existed before MagicDesk was opened is
        // system-owned. Close only our desktop host and leave its mode intact.
        return target.kind != DesktopDisplayTarget.Kind.WIRED
                || target.activationSource
                        != DesktopDisplayTarget.ActivationSource.ADOPTED_EXISTING;
    }

    private boolean switchToMirrorNow() {
        final int desktopDisplayId = activeDesktopDisplayId();
        boolean success = desktopDisplayId <= Display.DEFAULT_DISPLAY;
        final boolean displayRemovalPrepared = !success
                && MagicDeskRuntime.prepareDesktopDisplayRemoval(
                        desktopDisplayId);
        if (!success && mProjection.requestMirrorMode()) {
            success = mProjection.waitForDesktopStop(mContext);
            if (!success) {
                Log.w(TAG,
                        "Console display remained active after Mirror request");
            }
        }
        if (!success && displayRemovalPrepared) {
            MagicDeskRuntime.cancelDesktopDisplayRemoval(
                    desktopDisplayId);
        }
        if (success && ShellAccess.isReady()) {
            mProjection.setCaptionTransport(
                    PlatformProjectionDriver.Transport.NONE);
        }
        return success;
    }

    private void showPreferredDesktopNow() {
        final boolean wiredSupported = mFeatures.supportsDisplay(
                DesktopDisplayTarget.Kind.WIRED);
        final boolean wirelessSupported = mFeatures.supportsDisplay(
                DesktopDisplayTarget.Kind.WIRELESS);
        if (wiredSupported
                && (activeDesktopDisplayId() > Display.DEFAULT_DISPLAY
                || ConsoleDisplayController.findExternalDisplayId()
                        > Display.DEFAULT_DISPLAY)) {
            DesktopDisplayDrivers.activateWired(null);
            return;
        }
        final int wirelessDisplayId =
                ConsoleDisplayController.findWirelessDisplayId();
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

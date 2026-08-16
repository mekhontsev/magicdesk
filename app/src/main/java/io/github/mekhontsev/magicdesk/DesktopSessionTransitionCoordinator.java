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

    void showPreferredDesktop(final int knownConsoleDisplayId) {
        if (mGate.isCloseInProgress()) {
            Log.i(TAG, "Desktop close is already in progress");
            return;
        }
        if (!mGate.beginDesktopStart()) {
            Log.i(TAG, "MagicDesk activation is already in progress");
            return;
        }
        mOperations.execute(() -> {
            try {
                showPreferredDesktopNow(knownConsoleDisplayId);
            } finally {
                mGate.finishStart();
            }
        });
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
        if (!mGate.beginDesktopStart()) {
            Log.i(TAG, "MagicDesk activation is already in progress");
            return;
        }
        mOperations.execute(() -> {
            try {
                DesktopDisplayDrivers.forTarget(target)
                        .show(null, target.displayId);
            } finally {
                mGate.finishStart();
            }
        });
    }

    void closeDesktop(
            final DesktopDisplayTarget target,
            final boolean restorePhonePanel,
            final CompletionCallback callback) {
        if (target == null) {
            complete(callback, false);
            return;
        }
        if (!mGate.beginClose()) {
            Log.i(TAG, "Desktop close is already in progress");
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
        if (restorePhonePanel
                && target.displayId > Display.DEFAULT_DISPLAY) {
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
        if (!mGate.beginModeTransition()) {
            Log.i(TAG, "Console mode transition is already in progress");
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
                mGate.finishStart();
            }
            complete(callback, success);
        });
    }

    private void finishDesktopClose(
            final CompletionCallback callback,
            final boolean success) {
        mGate.finishClose();
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
        final boolean platformOwned = transport
                != PlatformProjectionDriver.Transport.NONE
                && mProjection.ownsTransportLifecycle(transport);
        if (platformOwned && restorePhonePanel) {
            ControlActivity.finishActiveForMirrorTransition();
        }
        final boolean success;
        if (platformOwned) {
            success = switchToMirrorNow();
        } else {
            DesktopRuntimeBridge.closeDesktopSession(target.displayId);
            success = true;
        }
        if (restorePhonePanel) {
            PhoneControlPanelLauncher.openOnPhoneWithShell();
        }
        return success;
    }

    private boolean switchToMirrorNow() {
        boolean success = activeDesktopDisplayId() <= Display.DEFAULT_DISPLAY;
        if (!success && mProjection.requestMirrorMode()) {
            success = mProjection.waitForDesktopStop(mContext);
            if (!success) {
                Log.w(TAG,
                        "Console display remained active after Mirror request");
            }
        }
        if (success && ShellAccess.isReady()) {
            mProjection.setCaptionTransport(
                    PlatformProjectionDriver.Transport.NONE);
        }
        return success;
    }

    private void showPreferredDesktopNow(
            final int knownConsoleDisplayId) {
        final boolean wiredSupported = mFeatures.supportsDisplay(
                DesktopDisplayTarget.Kind.WIRED);
        final boolean wirelessSupported = mFeatures.supportsDisplay(
                DesktopDisplayTarget.Kind.WIRELESS);
        if (wiredSupported
                && (knownConsoleDisplayId > Display.DEFAULT_DISPLAY
                || activeDesktopDisplayId() > Display.DEFAULT_DISPLAY
                || ConsoleDisplayController.findExternalDisplayId()
                        > Display.DEFAULT_DISPLAY)) {
            DesktopDisplayDrivers
                    .forKind(DesktopDisplayTarget.Kind.WIRED)
                    .show(null, knownConsoleDisplayId);
            return;
        }
        final int wirelessDisplayId =
                ConsoleDisplayController.findWirelessDisplayId();
        if (wirelessSupported
                && wirelessDisplayId > Display.DEFAULT_DISPLAY) {
            DesktopDisplayDrivers
                    .forKind(DesktopDisplayTarget.Kind.WIRELESS)
                    .show(null, wirelessDisplayId);
            return;
        }
        Log.i(TAG, "No connected external display is available");
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

package io.github.mekhontsev.magicdesk;

import android.util.Log;
import android.view.Display;

/** Owns serialized desktop activation, close, and mirror transitions. */
final class DesktopSessionTransitionCoordinator {
    interface CompletionCallback {
        void onComplete(boolean success);
    }

    private static final String TAG = "MagicDeskConsoleSwitcher";

    private final SerializedDesktopOperationQueue mOperations;
    private final DesktopTransitionGate mGate = new DesktopTransitionGate();

    DesktopSessionTransitionCoordinator(
            final SerializedDesktopOperationQueue operations) {
        mOperations = operations;
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
        if (!DesktopDisplayDrivers.isSupported(target.kind)) {
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
        final Runnable close = () -> {
            try {
                DesktopDisplayDrivers.forTarget(target).close(
                        target,
                        restorePhonePanel,
                        success -> finishDesktopClose(callback, success));
            } catch (RuntimeException error) {
                Log.w(TAG, "Desktop close failed", error);
                finishDesktopClose(callback, false);
            }
        };
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

    void switchToMirror(final CompletionCallback callback) {
        if (!mGate.beginModeTransition()) {
            Log.i(TAG, "Console mode transition is already in progress");
            complete(callback, false);
            return;
        }
        mOperations.execute(() -> {
            boolean success = false;
            try {
                if (activeDesktopDisplayId() <= 0) {
                    success = true;
                } else if (PlatformDrivers.current().projection()
                        .requestMirrorMode()) {
                    success = PlatformDrivers.current().projection()
                            .waitForDesktopStop(
                                    MagicDeskApplication
                                            .applicationContext());
                    if (!success) {
                        Log.w(TAG,
                                "Console display remained active after Mirror request");
                    }
                }
                if (success && ShellAccess.isReady()) {
                    PlatformDrivers.current().projection()
                            .setCaptionTransport(
                                    PlatformProjectionDriver.Transport.NONE);
                }
            } finally {
                mGate.finishStart();
                complete(callback, success);
            }
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

    private static void showPreferredDesktopNow(
            final int knownConsoleDisplayId) {
        final boolean wiredSupported = DesktopDisplayDrivers.isSupported(
                DesktopDisplayTarget.Kind.WIRED);
        final boolean wirelessSupported = DesktopDisplayDrivers.isSupported(
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

    private static int activeDesktopDisplayId() {
        return PlatformDrivers.current().projection()
                .activeDesktopDisplayId(
                        MagicDeskApplication.applicationContext());
    }

    private static void complete(
            final CompletionCallback callback,
            final boolean success) {
        if (callback != null) {
            callback.onComplete(success);
        }
    }
}

package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.util.Log;

import java.io.IOException;

/** Shared session operations used by display drivers. */
final class DesktopDisplayDriverSupport {
    private static final String TAG = "MagicDeskDisplayDriver";

    private DesktopDisplayDriverSupport() {
    }

    static void showPrepared(
            final DesktopDisplayDriver driver,
            final int displayId) {
        showPrepared(driver.target(displayId));
    }

    static void showConnectedExternal(
            final DesktopDisplayDriver driver,
            final int displayId) {
        final Context context = MagicDeskApplication.applicationContext();
        final DesktopDisplayTarget target =
                DisplayProfileController.prepareTarget(
                        context, driver.target(displayId));
        final DisplayProfileStore.Profile profile =
                DisplayProfileController.loadPreparedProfile(context, target);
        if (profile != null) {
            ConsoleDisplayController.applyStartupDensity(
                    displayId, profile.dpi);
        }
        showPrepared(target);
    }

    static void showPrepared(final DesktopDisplayTarget target) {
        final DesktopDisplayDriver driver =
                DesktopDisplayDrivers.forTarget(target);
        try {
            final DesktopSessionController.ShowResult result =
                    DesktopSessionController.show(target);
            if (result.ready && result.created
                    && driver.features().phoneTouchpad
                    && MagicDeskSettings.load()
                            .openTouchpadAutomatically) {
                PhoneTouchpadController.open(target.displayId);
            }
        } catch (IOException error) {
            Log.w(TAG, "Desktop launch failed", error);
            CompatibilityDiagnostics.record(
                    "DESKTOP-LAUNCH-002",
                    "Could not open MagicDesk on the selected display",
                    "kind=" + driver.kind()
                            + " display=" + target.displayId
                            + " error=" + error.getMessage(),
                    error);
        }
    }

    static void complete(
            final DesktopDisplayDriver.CompletionCallback callback,
            final boolean success) {
        if (callback != null) {
            callback.onComplete(success);
        }
    }

    static void closeDirectExternal(
            final DesktopDisplayTarget target,
            final boolean restorePhonePanel,
            final DesktopDisplayDriver.CompletionCallback callback) {
        DesktopRuntimeBridge.closeDesktopSession(target.displayId);
        if (restorePhonePanel) {
            PhoneControlPanelLauncher.openOnPhoneWithShell();
        }
        complete(callback, true);
    }

    static boolean ownsTransportLifecycle(
            final PlatformProjectionDriver.Transport transport) {
        return PlatformDrivers.current().projection()
                .ownsTransportLifecycle(transport);
    }

    static void closeExternal(
            final DesktopDisplayTarget target,
            final PlatformProjectionDriver.Transport transport,
            final boolean restorePhonePanel,
            final DesktopDisplayDriver.CompletionCallback callback) {
        if (!ownsTransportLifecycle(transport)) {
            closeDirectExternal(target, restorePhonePanel, callback);
            return;
        }
        if (restorePhonePanel) {
            ConsoleModeSwitcher.switchToMirrorWithControlPanel(
                    success -> complete(callback, success));
        } else {
            ConsoleModeSwitcher.switchToMirror(
                    success -> complete(callback, success));
        }
    }
}

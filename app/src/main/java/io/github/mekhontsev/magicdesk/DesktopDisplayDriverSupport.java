package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.util.Log;
import android.view.Display;

import java.io.IOException;

/** Shared session operations used by display drivers. */
final class DesktopDisplayDriverSupport {
    private static final String TAG = "MagicDeskDisplayDriver";

    private DesktopDisplayDriverSupport() {
    }

    static void showReadySecondary(final DesktopDisplayTarget target) {
        showReadySecondary(target, DesktopSessionPolicy.USER);
    }

    static void showReadySecondary(
            final DesktopDisplayTarget target,
            final DesktopSessionPolicy policy) {
        if (target == null
                || target.displayId <= Display.DEFAULT_DISPLAY) {
            throw new IllegalArgumentException(
                    "a ready secondary display target is required");
        }
        final Context context = MagicDeskApplication.applicationContext();
        final DesktopDisplayTarget preparedTarget =
                DisplayProfileController.prepareTarget(
                        context, target);
        final DisplayProfileStore.Profile profile =
                DisplayProfileController.loadPreparedProfile(
                        context, preparedTarget);
        if (profile != null) {
            try {
                ConsoleDisplayController.applyStartupDensity(
                        preparedTarget.displayId, profile.dpi);
            } catch (RuntimeException error) {
                Log.w(TAG, "Could not prepare secondary display density",
                        error);
            }
        }
        showPrepared(preparedTarget, policy);
    }

    static void showPrepared(final DesktopDisplayTarget target) {
        showPrepared(target, DesktopSessionPolicy.USER);
    }

    static void showPrepared(
            final DesktopDisplayTarget target,
            final DesktopSessionPolicy policy) {
        final DesktopDisplayDriver driver =
                DesktopDisplayDrivers.forTarget(target);
        try {
            final DesktopSessionController.ShowResult result =
                    DesktopSessionController.show(target, policy);
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

}

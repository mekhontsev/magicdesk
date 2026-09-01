package io.github.mekhontsev.magicdesk;

import android.content.Context;

final class DeviceSetupRuntimeController {
    private static volatile boolean sRuntimeAuthorized;

    private DeviceSetupRuntimeController() {
    }

    static void activate(
            final Context context,
            final DeviceSetupManager.Audit audit) {
        if (audit == null) {
            return;
        }
        if (audit.canEnterMagicDesk()) {
            reconcileServices(context);
        } else {
            stopServices(context);
        }
    }

    static void authorize(final Context context) {
        sRuntimeAuthorized = true;
        reconcileServices(context);
    }

    static void revoke(final Context context) {
        sRuntimeAuthorized = false;
        stopServices(context);
    }

    static boolean isAuthorized() {
        return sRuntimeAuthorized;
    }

    private static void reconcileServices(final Context context) {
        if (context == null) {
            return;
        }
        if (sRuntimeAuthorized
                && ShellAccess.isReady()) {
            MagicDeskRuntime.start(context.getApplicationContext());
        } else {
            stopServices(context);
        }
    }

    private static void stopServices(final Context context) {
        PlatformDrivers.current().phoneUi().requestPhoneScreenRestore();
        if (context != null) {
            MagicDeskRuntime.retainAutomationOrStop(
                    context.getApplicationContext());
        }
    }
}

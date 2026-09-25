package io.github.mekhontsev.magicdesk;

import android.content.Context;

final class DeviceSetupRuntimeController {
    private static volatile boolean sRuntimeAuthorized;

    private DeviceSetupRuntimeController() {
    }

    static void authorize(final Context context) {
        sRuntimeAuthorized = true;
        if (context != null && ShellAccess.isReady()) {
            MagicDeskRuntime.startTools(context.getApplicationContext());
        }
    }

    static void revoke(final Context context) {
        sRuntimeAuthorized = false;
        stopServices(context);
    }

    static boolean isAuthorized() {
        return sRuntimeAuthorized;
    }

    private static void stopServices(final Context context) {
        PlatformDrivers.current().phoneUi().requestPhoneScreenRestore();
        if (context != null) {
            MagicDeskRuntime.retainIndependentServices(
                    context.getApplicationContext());
        }
    }
}

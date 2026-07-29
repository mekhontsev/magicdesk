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
        RuntimeAccess.configure(audit.sessionProfile, audit.backend);
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
                && RuntimeAccess.has(
                        RuntimeAccess.Capability.PUBLIC_APP_LAUNCH)) {
            MagicDeskRuntimeService.start(context.getApplicationContext());
        } else {
            stopServices(context);
        }
    }

    private static void stopServices(final Context context) {
        RootKeyboardShortcutWatcher.stop();
        ConsoleModeSwitcher.closeRootShell();
        if (context != null) {
            MagicDeskRuntimeService.stop(context.getApplicationContext());
        }
    }
}

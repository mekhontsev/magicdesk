package io.github.mekhontsev.magicdesk;

import org.json.JSONException;
import org.json.JSONObject;

/** Service prerequisites, distinct from client grants and desktop ownership. */
final class RuntimeCapabilities {
    static final int DESKTOP_MIN_SDK = 35;

    enum Service { AUTOMATION, BUILTIN_UI, SHELL, TERMUX, VIRTUAL_DISPLAY, DESKTOP }

    static boolean supportsDesktop(final int sdk) {
        return sdk >= DESKTOP_MIN_SDK;
    }

    static void requireDesktop() {
        if (!supportsDesktop(android.os.Build.VERSION.SDK_INT)) {
            throw new UnsupportedOperationException("Desktop requires Android 15 or newer");
        }
    }

    private final int mSdk;
    private final boolean mShell;
    private final boolean mTermuxInstalled;
    private final boolean mTermuxAuthorized;
    private final boolean mDesktopPrepared;

    RuntimeCapabilities(final int sdk, final boolean shell, final boolean termuxInstalled,
            final boolean termuxAuthorized, final boolean desktopPrepared) {
        mSdk = sdk;
        mShell = shell;
        mTermuxInstalled = termuxInstalled;
        mTermuxAuthorized = termuxAuthorized;
        mDesktopPrepared = desktopPrepared;
    }

    static RuntimeCapabilities current(final android.content.Context context) {
        final TermuxIntegration.Endpoint termux = TermuxIntegration.inspect(context);
        return new RuntimeCapabilities(android.os.Build.VERSION.SDK_INT, ShellAccess.isReady(),
                termux.installed, termux.available(),
                DeviceSetupManager.isRuntimeAuthorized());
    }

    String missing(final Service service) {
        return switch (service) {
            case AUTOMATION, BUILTIN_UI -> "";
            case SHELL, VIRTUAL_DISPLAY -> mShell ? "" : "privileged_service";
            case TERMUX -> !mTermuxInstalled ? "termux" : !mTermuxAuthorized ? "termux_run_command" : "";
            case DESKTOP -> !supportsDesktop(mSdk) ? "android_15"
                    : !mShell ? "privileged_service" : !mDesktopPrepared ? "desktop_setup" : "";
        };
    }

    JSONObject toJson() throws JSONException {
        final JSONObject result = new JSONObject();
        for (final Service service : Service.values()) {
            final JSONObject state = new JSONObject().put("ready", missing(service).isEmpty())
                    .put("missing", missing(service)).put("requiresDesktopSession", false);
            if (service == Service.DESKTOP) { state.put("minimumSdk", DESKTOP_MIN_SDK); }
            result.put(service.name().toLowerCase(java.util.Locale.ROOT), state);
        }
        return result;
    }
}

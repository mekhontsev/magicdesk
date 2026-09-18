package io.github.mekhontsev.magicdesk;

import org.json.JSONException;
import org.json.JSONObject;

/** Service prerequisites, distinct from client grants and desktop ownership. */
final class RuntimeCapabilities {
    static final int DESKTOP_MIN_SDK = 35;

    enum Service { AUTOMATION, BUILTIN_UI, DISPLAYS, SHELL, TERMUX, TERMINAL, X11, VIRTUAL_DISPLAY, DESKTOP }

    static boolean supportsDesktop(final int sdk) {
        return sdk >= DESKTOP_MIN_SDK;
    }

    static void requireDesktop() {
        if (!supportsDesktop(android.os.Build.VERSION.SDK_INT)) {
            throw new UnsupportedOperationException("Desktop requires Android 15 or newer");
        }
        if (!RuntimeLimits.active().desktopAllowed()) {
            throw new IllegalStateException("Desktop is disabled in Limits");
        }
    }

    static boolean allowsDesktop(int sdk) {
        return supportsDesktop(sdk) && RuntimeLimits.active().desktopAllowed();
    }

    private final int mSdk;
    private final boolean mShell;
    private final boolean mTermuxInstalled;
    private final boolean mTermuxAuthorized;
    private final DesktopSetupStatus.State mDesktopSetup;
    private final RuntimeLimits.Values mLimits;

    RuntimeCapabilities(final int sdk, final boolean shell, final boolean termuxInstalled,
            final boolean termuxAuthorized, final DesktopSetupStatus.State desktopSetup, final RuntimeLimits.Values limits) {
        mSdk = sdk;
        mShell = shell;
        mTermuxInstalled = termuxInstalled;
        mTermuxAuthorized = termuxAuthorized;
        mDesktopSetup = desktopSetup;
        mLimits = limits;
    }

    static RuntimeCapabilities current(final android.content.Context context) {
        final TermuxIntegration.Endpoint termux = TermuxIntegration.inspect(context);
        return new RuntimeCapabilities(android.os.Build.VERSION.SDK_INT, ShellAccess.isReady(),
                termux.installed, termux.available(), DesktopSetupStatus.current().state(), RuntimeLimits.active());
    }

    String missing(final Service service) {
        return switch (service) {
            case AUTOMATION, BUILTIN_UI, DISPLAYS -> "";
            case SHELL, VIRTUAL_DISPLAY -> !mLimits.privilegedAllowed() ? "privileged_disabled" : mShell ? "" : "privileged_service";
            case TERMUX -> !mLimits.termux() ? "termux_disabled" : !mTermuxInstalled ? "termux" : !mTermuxAuthorized ? "termux_run_command" : "";
            case X11 -> mLimits.privilegedAllowed() && mShell
                    || mLimits.termux() && mTermuxInstalled && mTermuxAuthorized ? "" : "x11_executor";
            case TERMINAL -> mLimits.privilegedAllowed() && mShell
                    || mLimits.termux() && mTermuxInstalled && mTermuxAuthorized ? "" : "terminal_backend";
            case DESKTOP -> !supportsDesktop(mSdk) ? "android_15"
                    : !mLimits.desktop() ? "desktop_disabled" : !mLimits.privilegedAllowed() ? "privileged_disabled"
                    : !mShell ? "privileged_service" : switch (mDesktopSetup) {
                        case READY -> "";
                        case CHECKING -> "desktop_setup_checking";
                        case UNKNOWN -> "desktop_setup_unknown";
                        case SETUP_REQUIRED -> "desktop_setup";
                        case RESTART_REQUIRED -> "device_restart";
                    };
        };
    }

    int unavailableMessage(Service service) {
        return switch (missing(service)) {
            case "" -> 0;
            case "android_15" -> R.string.capability_android_15_required;
            case "privileged_service" -> R.string.capability_access_required;
            case "privileged_disabled" -> R.string.limit_privileged_disabled;
            case "termux_disabled" -> R.string.limit_termux_disabled;
            case "desktop_disabled" -> R.string.limit_desktop_disabled;
            case "termux" -> R.string.capability_termux_required;
            case "termux_run_command" -> R.string.capability_termux_permission_required;
            case "terminal_backend" -> R.string.capability_terminal_required;
            case "x11_executor" -> R.string.capability_terminal_required;
            case "desktop_setup_checking" -> R.string.setup_status_checking;
            case "desktop_setup_unknown" -> R.string.control_desktop_unknown;
            case "desktop_setup" -> R.string.control_desktop_setup_required;
            case "device_restart" -> R.string.setup_status_reboot_required;
            default -> throw new IllegalStateException("Unknown service prerequisite");
        };
    }

    void require(android.content.Context context, Service service) {
        final int message = unavailableMessage(service);
        if (message != 0) throw new IllegalStateException(context.getString(message));
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

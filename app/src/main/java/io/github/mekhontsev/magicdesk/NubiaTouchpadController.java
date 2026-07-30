package io.github.mekhontsev.magicdesk;

import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

final class NubiaTouchpadController {
    private static final String TAG = "MagicDeskConsoleSwitcher";
    private static final String AM = "/system/bin/am";
    private static final String PM = "/system/bin/pm";
    private static final String MIRROR_INPUT_SERVICE =
            "cn.nubia.keymapcenter/.mirror.MirrorInputService";
    private static final String MIRROR_INPUT_ACTIVITY =
            "cn.nubia.keymapcenter/.mirror.MirrorInputActivity";
    private static final String MIRROR_INPUT_ACTION =
            "cn.nubia.keymapcenter.intent.action.MIRROR_INPUT";
    private static final String CONSOLE_CONTROL_COMMAND =
            "io.github.mekhontsev.magicdesk.ConsoleControlCommand";
    private static final String CONSOLE_DISPLAY_COMMAND =
            "io.github.mekhontsev.magicdesk.ConsoleDisplayCommand";
    private static final String MOUSE_VIEWPORT_COMMAND =
            "io.github.mekhontsev.magicdesk.MouseViewportCommand";
    private static final long TRANSITION_TIMEOUT_MS = 2_000L;
    private static final long POLL_MS = 100L;
    private static final AtomicBoolean OPEN_IN_PROGRESS =
            new AtomicBoolean();

    private NubiaTouchpadController() {
    }

    static void setMirrorInputProxyEnabled(
            final boolean enabled,
            final ConsoleModeSwitcher.ResultCallback callback) {
        ConsoleModeSwitcher.executeSerialized(() -> {
            boolean success = false;
            try {
                success = setMirrorInputProxyEnabledInternal(enabled);
            } finally {
                ConsoleModeSwitcher.closeRootShell();
                if (callback != null) {
                    callback.onComplete(success);
                }
            }
        });
    }

    static void open() {
        if (!OPEN_IN_PROGRESS.compareAndSet(false, true)) {
            Log.i(TAG, "Nubia touchpad activation is already in progress");
            return;
        }
        ConsoleModeSwitcher.executeSerialized(() -> {
            try {
                if (ConsoleModeSwitcher.getActiveConsoleDisplayId() <= 0) {
                    Log.w(TAG,
                            "cannot open Nubia touchpad: Console mode is inactive");
                    CompatibilityDiagnostics.record(
                            "NUBIA-TOUCHPAD-001",
                            "Cannot open the Nubia touchpad",
                            "Console mode is inactive");
                    return;
                }
                request();
            } finally {
                OPEN_IN_PROGRESS.set(false);
                ConsoleModeSwitcher.closeRootShell();
            }
        });
    }

    static boolean isVisible() {
        try {
            return ConsoleModeSwitcher.getActiveConsoleDisplayId() > 0
                    && isActivityPresent();
        } finally {
            ConsoleModeSwitcher.closeRootShell();
        }
    }

    static void restoreIfMissing(
            final ConsoleModeSwitcher.TouchpadRestoreCallback callback) {
        if (!OPEN_IN_PROGRESS.compareAndSet(false, true)) {
            Log.i(TAG, "Nubia touchpad activation is already in progress");
            if (callback != null) {
                callback.onComplete(false, false);
            }
            return;
        }
        ConsoleModeSwitcher.executeSerialized(() -> {
            boolean touchpadMissing = false;
            boolean restored = false;
            try {
                if (ConsoleModeSwitcher.getActiveConsoleDisplayId() <= 0) {
                    return;
                }
                if (isActivityPresent()) {
                    Log.i(TAG,
                            "Nubia touchpad remained visible after desktop transition");
                    return;
                }
                touchpadMissing = true;
                Log.i(TAG,
                        "restore Nubia touchpad after desktop transition");
                restored = request();
            } finally {
                OPEN_IN_PROGRESS.set(false);
                ConsoleModeSwitcher.closeRootShell();
                if (callback != null) {
                    callback.onComplete(touchpadMissing, restored);
                }
            }
        });
    }

    static void restorePrimaryPhoneHome() {
        ConsoleModeSwitcher.executeSerialized(() -> {
            try {
                runConsoleCommand(
                        PhoneHomeRecoveryController.primaryHomeCommand());
            } finally {
                ConsoleModeSwitcher.closeRootShell();
            }
        });
    }

    static boolean refreshOrOpen() {
        if (RuntimeAccess.allowsShizukuCommands()
                && !RuntimeAccess.allowsRootCommands()) {
            return requestWithShizuku();
        }
        if (!setMirrorInputProxyEnabledInternal(true)) {
            Log.w(TAG,
                    "cannot refresh Nubia touchpad: input proxy could not be enabled");
            return false;
        }
        ConsoleModeSwitcher.runRootCommand(
                PM + " enable --user 0 " + MIRROR_INPUT_ACTIVITY);
        if (!isActivityPresent()) {
            return request();
        }
        final String output = ConsoleModeSwitcher.runRootCommand(
                AppProcessCommand.run(
                        CONSOLE_DISPLAY_COMMAND,
                        "touchpad 0")).trim();
        final boolean touchpadReady =
                output.contains("display-command=touchpad");
        final String viewportOutput =
                ConsoleModeSwitcher.runRootCommand(
                        AppProcessCommand.run(
                                MOUSE_VIEWPORT_COMMAND)).trim();
        final boolean viewportUpdated =
                viewportOutput.contains("mouse-viewport=updated");
        final boolean success = touchpadReady && viewportUpdated;
        if (success) {
            Log.i(TAG,
                    "Nubia touchpad retained with refreshed viewport"
                            + " after Console startup");
        } else {
            Log.w(TAG,
                    "Nubia touchpad refresh failed touchpadOutput="
                            + output + " viewportOutput="
                            + viewportOutput);
        }
        return success;
    }

    static boolean setMirrorInputProxyEnabledInternal(
            final boolean enabled) {
        if (!enabled) {
            ConsoleModeSwitcher.runRootCommand(
                    touchpadServiceCommand("close_input_panel"));
        }
        final String output = ConsoleModeSwitcher.runRootCommand(
                AppProcessCommand.run(
                        CONSOLE_CONTROL_COMMAND,
                        "mirror-input-service " + enabled)).trim();
        final boolean success =
                output.contains("mirror-input-service=" + enabled);
        if (!success) {
            Log.w(TAG,
                    "cannot set Nubia mirror input service enabled="
                            + enabled + " output=" + output);
            return false;
        }
        if (!enabled) {
            ConsoleModeSwitcher.runRootCommand(
                    AM + " stop-service --user 0 -n "
                            + MIRROR_INPUT_SERVICE + " || true");
        }
        Log.i(TAG,
                "Nubia mirror input proxy enabled=" + enabled);
        return true;
    }

    private static boolean request() {
        if (RuntimeAccess.allowsShizukuCommands()
                && !RuntimeAccess.allowsRootCommands()) {
            return requestWithShizuku();
        }
        if (!setMirrorInputProxyEnabledInternal(true)) {
            Log.w(TAG,
                    "cannot open Nubia touchpad: input proxy could not be enabled");
            return false;
        }
        ConsoleModeSwitcher.runRootCommand(
                PM + " enable --user 0 " + MIRROR_INPUT_ACTIVITY);
        ConsoleModeSwitcher.runRootCommand(
                touchpadServiceCommand("close_touch_panel"));
        if (!waitForActivity(false)) {
            Log.w(TAG, "Nubia touchpad did not close before restart");
            return false;
        }
        final String output = ConsoleModeSwitcher.runRootCommand(
                AppProcessCommand.run(
                        CONSOLE_DISPLAY_COMMAND,
                        "touchpad 0")).trim();
        if (!output.contains("display-command=touchpad")) {
            Log.w(TAG,
                    "Nubia touchpad command failed output=" + output);
            return false;
        }
        if (!waitForActivity(true)) {
            Log.w(TAG, "Nubia touchpad activity did not appear");
            return false;
        }
        Log.i(TAG, "Nubia touchpad opened on phone display");
        return true;
    }

    private static boolean requestWithShizuku() {
        final String touchpadOutput = runConsoleCommand(
                AppProcessCommand.run(
                        CONSOLE_DISPLAY_COMMAND,
                        "touchpad 0")).trim();
        if (!touchpadOutput.contains("display-command=touchpad")) {
            Log.w(TAG,
                    "Shizuku touchpad command failed output="
                            + touchpadOutput);
            return false;
        }
        final String viewportOutput = runConsoleCommand(
                AppProcessCommand.run(
                        MOUSE_VIEWPORT_COMMAND)).trim();
        if (!viewportOutput.contains("mouse-viewport=updated")) {
            Log.w(TAG,
                    "Shizuku mouse viewport update failed output="
                            + viewportOutput);
            return false;
        }
        final boolean visible = waitForActivity(true);
        if (!visible) {
            Log.w(TAG,
                    "Nubia touchpad did not appear after Shizuku request");
        }
        return visible;
    }

    private static String touchpadServiceCommand(final String reason) {
        return AM + " start-service --user 0"
                + " -a " + MIRROR_INPUT_ACTION
                + " -n " + MIRROR_INPUT_SERVICE
                + " --es reason " + reason;
    }

    private static boolean waitForActivity(
            final boolean expectedPresent) {
        final long deadline =
                SystemClock.uptimeMillis() + TRANSITION_TIMEOUT_MS;
        do {
            if (isActivityPresent() == expectedPresent) {
                return true;
            }
            SystemClock.sleep(POLL_MS);
        } while (SystemClock.uptimeMillis() < deadline);
        return false;
    }

    private static boolean isActivityPresent() {
        final String output = runConsoleCommand(
                "/system/bin/dumpsys activity activities"
                        + " | /system/bin/grep -F -m 1 "
                        + shellQuote(MIRROR_INPUT_ACTIVITY));
        return output.contains(MIRROR_INPUT_ACTIVITY);
    }

    private static String runConsoleCommand(final String command) {
        if (RuntimeAccess.allowsRootCommands()) {
            return ConsoleModeSwitcher.runRootCommand(command);
        }
        if (!RuntimeAccess.allowsShizukuCommands()) {
            return "";
        }
        try {
            return PrivilegedCommandRunner.run(command);
        } catch (IOException error) {
            Log.w(TAG, "Console command failed: " + command, error);
            return "";
        }
    }

    private static String shellQuote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}

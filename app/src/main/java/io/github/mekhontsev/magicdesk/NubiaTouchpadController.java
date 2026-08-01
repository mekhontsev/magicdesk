package io.github.mekhontsev.magicdesk;

import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

final class NubiaTouchpadController {
    private static final String TAG = "MagicDeskConsoleSwitcher";
    private static final String MIRROR_INPUT_ACTIVITY =
            "cn.nubia.keymapcenter/.mirror.MirrorInputActivity";
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
            }
        });
    }

    static boolean isVisible() {
        return ConsoleModeSwitcher.getActiveConsoleDisplayId() > 0
                && isActivityPresent();
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
                if (callback != null) {
                    callback.onComplete(touchpadMissing, restored);
                }
            }
        });
    }

    static void restorePrimaryPhoneHome() {
        ConsoleModeSwitcher.executeSerialized(() -> runConsoleCommand(
                PhoneHomeRecoveryController.primaryHomeCommand()));
    }

    static boolean refreshOrOpen() {
        return requestTouchpad();
    }

    private static boolean request() {
        return requestTouchpad();
    }

    private static boolean requestTouchpad() {
        final String touchpadOutput = runConsoleCommand(
                AppProcessCommand.run(
                        CONSOLE_DISPLAY_COMMAND,
                        "touchpad 0")).trim();
        if (!touchpadOutput.contains("display-command=touchpad")) {
            Log.w(TAG,
                    "Shell touchpad command failed output="
                            + touchpadOutput);
            return false;
        }
        final String viewportOutput = runConsoleCommand(
                AppProcessCommand.run(
                        MOUSE_VIEWPORT_COMMAND)).trim();
        if (!viewportOutput.contains("mouse-viewport=updated")) {
            Log.w(TAG,
                    "Shell mouse viewport update failed output="
                            + viewportOutput);
            return false;
        }
        final boolean visible = waitForActivity(true);
        if (!visible) {
            Log.w(TAG,
                    "Nubia touchpad did not appear after the shell request");
        }
        return visible;
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
        if (!ShellAccess.isReady()) {
            return "";
        }
        try {
            return ShellAccess.run(command);
        } catch (IOException error) {
            Log.w(TAG, "Console command failed: " + command, error);
            return "";
        }
    }

    private static String shellQuote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}

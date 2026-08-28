package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.AppProcessCommand;
import io.github.mekhontsev.magicdesk.BoundedStateAwaiter;
import io.github.mekhontsev.magicdesk.CompatibilityDiagnostics;
import io.github.mekhontsev.magicdesk.ConsoleDisplayController;
import io.github.mekhontsev.magicdesk.ShellAccess;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;

/** Owns RedMagic's vendor expand/mirror transport lifecycle. */
final class NubiaConsoleModeController {
    private static final String TAG = "MagicDeskConsoleDisplay";
    private static final String SETTINGS = "/system/bin/settings";
    private static final String COMMAND_CLASS =
            "io.github.mekhontsev.magicdesk.platform.nubia.ConsoleDisplayCommand";
    private static final long START_TIMEOUT_MILLIS = 10_000L;
    private static final long STATE_POLL_MILLIS = 100L;

    private NubiaConsoleModeController() {
    }

    static int activeDisplayId(final Context context) {
        final int displayId = ConsoleModeState.activeDisplayId(context);
        return displayId > 0 && ConsoleDisplayController.displayExists(displayId)
                ? displayId : -1;
    }

    static boolean requestDesktopMode(final int externalDisplayId) {
        final String output = runCommand(AppProcessCommand.run(
                COMMAND_CLASS, "expand " + externalDisplayId)).trim();
        if (output.contains("display-command=expand")) {
            return true;
        }
        Log.w(TAG, "Console mode request failed output=" + output);
        CompatibilityDiagnostics.record(
                "NUBIA-CONSOLE-003",
                "The firmware rejected the external desktop request",
                output);
        return false;
    }

    static boolean requestMirrorMode() {
        final String output = runCommand(AppProcessCommand.run(
                COMMAND_CLASS, "mirror 0")).trim();
        if (output.contains("display-command=mirror")) {
            return true;
        }
        Log.w(TAG, "Mirror mode request failed output=" + output);
        return false;
    }

    static boolean isMirrorMode() {
        return "0".equals(runCommand(
                SETTINGS + " get global app_mirror_status").trim());
    }

    static int waitForDesktopDisplay(final Context context) {
        final long deadline =
                SystemClock.uptimeMillis() + START_TIMEOUT_MILLIS;
        while (SystemClock.uptimeMillis() < deadline) {
            final int displayId = activeDisplayId(context);
            if (displayId > 0) {
                return displayId;
            }
            BoundedStateAwaiter.pause(
                    BoundedStateAwaiter.Reason.VENDOR_STATE,
                    STATE_POLL_MILLIS);
        }
        return -1;
    }

    static boolean waitForDesktopStop(final Context context) {
        final long deadline =
                SystemClock.uptimeMillis() + START_TIMEOUT_MILLIS;
        while (SystemClock.uptimeMillis() < deadline) {
            if (activeDisplayId(context) <= 0) {
                return true;
            }
            BoundedStateAwaiter.pause(
                    BoundedStateAwaiter.Reason.VENDOR_STATE,
                    STATE_POLL_MILLIS);
        }
        return false;
    }

    private static String runCommand(final String command) {
        if (!ShellAccess.isReady()) {
            return "";
        }
        try {
            return ShellAccess.run(command);
        } catch (IOException error) {
            Log.w(TAG, "display command failed: " + command, error);
            return "";
        }
    }
}

package io.github.mekhontsev.magicdesk;

import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Focuses or creates the desktop after its target display is ready. */
final class DesktopSessionController {
    private static final String TAG = "MagicDeskDesktopSession";
    private static final String AM = "/system/bin/am";
    private static final String DESKTOP_COMPONENT =
            "io.github.mekhontsev.magicdesk/.DesktopActivity";
    private static final String TASK_CONTROL_COMMAND =
            "io.github.mekhontsev.magicdesk.TaskControlCommand";
    private static final Pattern DESKTOP_TASK_ID_PATTERN =
            Pattern.compile("desktop-task-id=(-?\\d+)");

    private DesktopSessionController() {
    }

    static boolean show(final DesktopDisplayTarget target)
            throws IOException {
        if (target == null) {
            throw new IllegalArgumentException("display target is required");
        }
        if (!ConsoleDisplayController.displayExists(target.displayId)) {
            throw new IOException(
                    "desktop display no longer exists: " + target.displayId);
        }

        final Boolean visibleTaskSnapshot =
                DesktopTaskController.hasVisibleAppTaskSnapshot(
                        target.displayId);
        final boolean restoreWindows = visibleTaskSnapshot != null
                && !visibleTaskSnapshot.booleanValue();
        final int desktopTaskId = findDesktopTask(target.displayId);
        if (desktopTaskId >= 0) {
            final String focusOutput = ShellAccess.run(
                    AM + " task focus " + desktopTaskId).trim();
            Log.i(TAG, "focused desktop kind=" + target.kind
                    + " display=" + target.displayId
                    + " task=" + desktopTaskId
                    + " output=" + focusOutput.replace('\n', ' '));
            if (restoreWindows) {
                DesktopRuntimeBridge.restoreLastVisibleWindows();
            }
            return true;
        }

        final String output = ShellAccess.run(
                AM + " start -W --display " + target.displayId
                        + " --windowingMode 5"
                        + " -f 0x18000000"
                        + " -a android.intent.action.MAIN"
                        + " -c android.intent.category.LAUNCHER"
                        + " --ei "
                        + DesktopShellActivity.EXTRA_EXPECTED_DISPLAY_ID
                        + " " + target.displayId
                        + (restoreWindows
                                ? " --es " + DesktopShellActivity.EXTRA_ACTION
                                        + " "
                                        + DesktopShellActivity
                                                .ACTION_RESTORE_WINDOWS
                                : "")
                        + " -n " + DESKTOP_COMPONENT)
                .trim();
        if (output.startsWith("Error:")
                || output.contains(
                        "Exception occurred while executing")) {
            throw new IOException(output);
        }
        Log.i(TAG, "launched desktop kind=" + target.kind
                + " display=" + target.displayId
                + " output=" + output.replace('\n', ' '));
        return waitForDesktopReady(target.displayId);
    }

    private static int findDesktopTask(final int displayId)
            throws IOException {
        final String output = ShellAccess.run(
                AppProcessCommand.run(
                        TASK_CONTROL_COMMAND,
                        "desktop-task-id " + displayId));
        final Matcher matcher = DESKTOP_TASK_ID_PATTERN.matcher(output);
        if (!matcher.find()) {
            throw new IOException(
                    "could not query MagicDesk desktop task: "
                            + output.trim());
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static boolean waitForDesktopReady(final int displayId)
            throws IOException {
        final long deadline = SystemClock.uptimeMillis()
                + ConsoleDisplayController.START_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            if (!ConsoleDisplayController.displayExists(displayId)) {
                return false;
            }
            if (findDesktopTask(displayId) >= 0) {
                return true;
            }
            SystemClock.sleep(ConsoleDisplayController.STATE_POLL_MS);
        }
        return false;
    }
}

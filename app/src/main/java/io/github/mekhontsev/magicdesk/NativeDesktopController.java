package io.github.mekhontsev.magicdesk;

import android.util.Log;

import java.io.IOException;

final class NativeDesktopController {
    private static final String TAG = "MagicDeskNativeDesktop";
    private static final String WMSHELL =
            "/system/bin/cmd statusbar wmshell-passthrough";
    private static final String HELP = WMSHELL + " help";
    private static final String DESKTOPMODE_HELP_ENTRY = "desktopmode";
    private static final String MOVE_TASK_TO_DESK = "moveTaskToDesk";
    private static final String MOVE_TO_DESKTOP = "moveToDesktop";

    private static boolean sProbed;
    private static String sMoveAction;

    private NativeDesktopController() {
    }

    static boolean shouldUse() {
        return ShellAccess.isReady() && isAvailable();
    }

    static boolean shouldUse(final boolean shizukuCommands,
            final boolean available) {
        return shizukuCommands && available;
    }

    static synchronized boolean isAvailable() {
        if (sProbed) {
            return sMoveAction != null;
        }
        try {
            final String output = runCommand(HELP);
            sMoveAction = selectMoveAction(output);
            sProbed = true;
        } catch (IOException e) {
            Log.w(TAG, "WMShell desktop-mode probe failed", e);
            sMoveAction = null;
        }
        return sMoveAction != null;
    }

    static void requireAvailable() throws IOException {
        if (!isAvailable()) {
            throw new IOException("WMShell desktop mode is unavailable");
        }
    }

    static void moveTaskToDesktop(final int taskId) throws IOException {
        if (taskId < 0) {
            throw new IOException("invalid task id");
        }
        requireAvailable();
        final String output = runCommand(
                WMSHELL + " desktopmode " + moveAction() + " " + taskId)
                .trim();
        if (output.startsWith("Error:")
                || output.startsWith("Invalid command:")
                || output.startsWith("Not supported.")) {
            throw new IOException(output);
        }
        Log.i(TAG, "requested native desktop mode task=" + taskId);
    }

    static String backendDescription() {
        return isAvailable()
                ? "wmshell-passthrough desktopmode " + moveAction()
                : "wmshell-passthrough desktopmode unavailable";
    }

    static String selectMoveAction(final String help) {
        if (help == null || !help.contains(DESKTOPMODE_HELP_ENTRY)) {
            return null;
        }
        if (help.contains(MOVE_TASK_TO_DESK + " <taskId>")) {
            return MOVE_TASK_TO_DESK;
        }
        if (help.contains(MOVE_TO_DESKTOP + " <taskId>")) {
            return MOVE_TO_DESKTOP;
        }
        return null;
    }

    private static synchronized String moveAction() {
        return sMoveAction;
    }

    private static String runCommand(final String command) throws IOException {
        return ShellAccess.run(command);
    }

}

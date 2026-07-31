package io.github.mekhontsev.magicdesk;

import android.util.Log;

import java.io.IOException;

final class NativeDesktopController {
    private static final String TAG = "MagicDeskNativeDesktop";
    private static final String WMSHELL =
            "/system/bin/cmd statusbar wmshell-passthrough";
    private static final String MOVE_TASK_TO_DESK =
            WMSHELL + " desktopmode moveTaskToDesk ";
    private static final String HELP = WMSHELL + " help";
    private static final String DESKTOPMODE_HELP_ENTRY = "desktopmode";
    private static final String MOVE_TASK_HELP_ENTRY = "moveTaskToDesk <taskId>";

    private static Boolean sAvailable;

    private NativeDesktopController() {
    }

    static boolean shouldUse() {
        final boolean rootCommands = RuntimeAccess.allowsRootCommands();
        final boolean shizukuCommands = RuntimeAccess.allowsShizukuCommands();
        final boolean available = (rootCommands || shizukuCommands) && isAvailable();
        return shouldUse(rootCommands, shizukuCommands, available);
    }

    static boolean shouldUse(
            final boolean rootCommands,
            final boolean shizukuCommands,
            final boolean available) {
        return (rootCommands || shizukuCommands) && available;
    }

    static synchronized boolean isAvailable() {
        if (Boolean.TRUE.equals(sAvailable)) {
            return true;
        }
        try {
            final String output = runRootCommand(HELP);
            sAvailable = Boolean.valueOf(
                    output.contains(DESKTOPMODE_HELP_ENTRY)
                            && output.contains(MOVE_TASK_HELP_ENTRY));
        } catch (IOException e) {
            Log.w(TAG, "WMShell desktop-mode probe failed", e);
            sAvailable = Boolean.FALSE;
        }
        return sAvailable.booleanValue();
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
        final String output = runRootCommand(MOVE_TASK_TO_DESK + taskId).trim();
        if (output.startsWith("Error:")
                || output.startsWith("Invalid command:")
                || output.startsWith("Not supported.")) {
            throw new IOException(output);
        }
        Log.i(TAG, "requested native desktop mode task=" + taskId);
    }

    private static String runRootCommand(final String command) throws IOException {
        return PrivilegedCommandRunner.run(command);
    }

}

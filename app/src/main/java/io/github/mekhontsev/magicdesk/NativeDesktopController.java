package io.github.mekhontsev.magicdesk;

import android.util.Log;

import java.io.IOException;

final class NativeDesktopController {
    private static final String TAG = "MagicDeskNativeDesktop";

    private static boolean sProbed;
    private static FrameworkDesktopShellApi sProtocol = FrameworkDesktopShellApi.fromHelp(null);

    private NativeDesktopController() {
    }

    static boolean shouldUse() {
        return ShellAccess.isReady() && isAvailable();
    }

    static boolean shouldUse(final boolean privilegedCommands,
            final boolean available) {
        return privilegedCommands && available;
    }

    static synchronized boolean isAvailable() {
        if (sProbed) {
            return sProtocol.canEnterDesktop();
        }
        try {
            final String output = runCommand(FrameworkDesktopShellApi.helpCommand());
            sProtocol = FrameworkDesktopShellApi.fromHelp(output);
            sProbed = true;
        } catch (IOException e) {
            Log.w(TAG, "WMShell desktop-mode probe failed", e);
            sProtocol = FrameworkDesktopShellApi.fromHelp(null);
        }
        return sProtocol.canEnterDesktop();
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
                protocol().enterDesktopCommand(FrameworkDesktopShellApi.Transport.STATUS_BAR, taskId))
                .trim();
        if (output.startsWith("Error:")
                || output.startsWith("Invalid command:")
                || output.startsWith("Not supported.")) {
            throw new IOException(output);
        }
        Log.i(TAG, "requested native desktop mode task=" + taskId);
    }

    static String backendDescription() {
        isAvailable();
        return protocol().entryDescription();
    }

    private static synchronized FrameworkDesktopShellApi protocol() {
        return sProtocol;
    }

    private static String runCommand(final String command) throws IOException {
        return ShellAccess.run(command);
    }

}

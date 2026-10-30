package io.github.mekhontsev.magicdesk;

import android.content.Context;

import java.io.File;
import java.io.IOException;

/** Starts the APK's native relay under the selected Termux identity. */
final class TermuxPtyBridgeLauncher {
    private static final String HELPER_NAME = "libmagicdesk_pty_bridge.so";

    private TermuxPtyBridgeLauncher() {
    }

    static void launch(
            final Context context,
            final TermuxIntegration.Endpoint endpoint,
            final int port,
            final String token,
            final int rows,
            final int columns,
            final String workingDirectory,
            final String startupCommand,
            final java.util.Map<String, String> environment) throws IOException {
        TermuxIntegration.runPtyBridge(context, endpoint, port, token, rows, columns,
                workingDirectory, startupCommand, path(context), environment);
    }

    static String path(Context context) throws IOException {
        final File helper = new File(
                context.getApplicationInfo().nativeLibraryDir,
                HELPER_NAME);
        if (!helper.isFile() || !helper.canExecute()) {
            throw new IOException("Packaged Termux PTY helper is unavailable");
        }
        return helper.getAbsolutePath();
    }
}

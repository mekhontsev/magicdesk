package io.github.mekhontsev.magicdesk;

import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;

final class ConsoleRootShell {
    private static final String TAG = "MagicDeskConsoleShell";

    private Process mProcess;
    private BufferedReader mReader;
    private BufferedWriter mWriter;
    private int mCommandId;

    synchronized String run(final String command) {
        if (!RuntimeAccess.allowsRootCommands()) {
            Log.d(TAG, "skip root command for backend="
                    + RuntimeAccess.backendName());
            return "";
        }
        if (!ensureStarted()) {
            return "";
        }

        final String marker = "__MAGICDESK_EXIT_" + (++mCommandId) + "__";
        final StringBuilder output = new StringBuilder();
        try {
            mWriter.write(command);
            mWriter.newLine();
            mWriter.write("echo " + marker + "$?");
            mWriter.newLine();
            mWriter.flush();

            String line;
            while ((line = mReader.readLine()) != null) {
                if (line.startsWith(marker)) {
                    final String exitCodeText =
                            line.substring(marker.length()).trim();
                    if (!"0".equals(exitCodeText)) {
                        Log.w(TAG, "root command failed code=" + exitCodeText
                                + " cmd=" + command + " output=" + output);
                        CompatibilityDiagnostics.record(
                                "ROOT-COMMAND-001",
                                "A MagicDesk root command failed",
                                "exit=" + exitCodeText + " command=" + command
                                        + " output=" + output);
                    }
                    return output.toString();
                }
                output.append(line).append('\n');
            }
            Log.w(TAG, "root shell closed cmd=" + command + " output=" + output);
            CompatibilityDiagnostics.record(
                    "ROOT-SHELL-002",
                    "Root shell closed unexpectedly",
                    "command=" + command + " output=" + output);
        } catch (IOException error) {
            Log.w(TAG, "root shell io error cmd=" + command
                    + " output=" + output, error);
            CompatibilityDiagnostics.record(
                    "ROOT-SHELL-003",
                    "Root shell communication failed",
                    "command=" + command + " output=" + output,
                    error);
        }

        close();
        return output.toString();
    }

    private boolean ensureStarted() {
        if (!RuntimeAccess.allowsRootCommands()) {
            return false;
        }
        if (mProcess != null && mProcess.isAlive()
                && mReader != null && mWriter != null) {
            return true;
        }

        close();
        try {
            mProcess = new ProcessBuilder("su")
                    .redirectErrorStream(true)
                    .start();
            mReader = new BufferedReader(
                    new InputStreamReader(mProcess.getInputStream()));
            mWriter = new BufferedWriter(
                    new OutputStreamWriter(mProcess.getOutputStream()));
            Log.i(TAG, "root shell started");
            return true;
        } catch (IOException error) {
            Log.w(TAG, "failed to start root shell", error);
            CompatibilityDiagnostics.record(
                    "ROOT-SHELL-001",
                    "Root shell is unavailable",
                    "",
                    error);
            close();
            return false;
        }
    }

    synchronized void close() {
        closeQuietly(mWriter);
        closeQuietly(mReader);
        if (mProcess != null) {
            mProcess.destroy();
        }
        mWriter = null;
        mReader = null;
        mProcess = null;
    }

    private static void closeQuietly(final Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Ignore close failures while recovering the shell.
        }
    }
}

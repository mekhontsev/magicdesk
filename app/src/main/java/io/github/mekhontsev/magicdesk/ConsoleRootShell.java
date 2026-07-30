package io.github.mekhontsev.magicdesk;

import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class ConsoleRootShell {
    private static final String TAG = "MagicDeskConsoleShell";
    private static final long COMMAND_TIMEOUT_MILLIS = 30_000L;
    private static final int MAX_OUTPUT_CHARS = 384 * 1024;
    private static final ExecutorService READ_EXECUTOR =
            Executors.newCachedThreadPool(new ThreadFactory() {
                @Override
                public Thread newThread(final Runnable runnable) {
                    final Thread thread =
                            new Thread(runnable, "MagicDeskRootShellRead");
                    thread.setDaemon(true);
                    return thread;
                }
            });

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
        final ShellOutput output = new ShellOutput();
        try {
            mWriter.write(command);
            mWriter.newLine();
            mWriter.write("echo " + marker + "$?");
            mWriter.newLine();
            mWriter.flush();
            final BufferedReader reader = mReader;
            final Future<String> read = READ_EXECUTOR.submit(
                    () -> readCommandOutput(reader, marker, output));
            try {
                final String exitCodeText =
                        read.get(COMMAND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
                if (exitCodeText == null) {
                    Log.w(TAG,
                            "root shell closed cmd=" + command
                                    + " output=" + output.snapshot());
                    CompatibilityDiagnostics.record(
                            "ROOT-SHELL-002",
                            "Root shell closed unexpectedly",
                            "command=" + command
                                    + " output=" + output.snapshot());
                } else if (!"0".equals(exitCodeText)) {
                    Log.w(TAG, "root command failed code=" + exitCodeText
                            + " cmd=" + command
                            + " output=" + output.snapshot());
                    CompatibilityDiagnostics.record(
                            "ROOT-COMMAND-001",
                            "A MagicDesk root command failed",
                            "exit=" + exitCodeText + " command=" + command
                                    + " output=" + output.snapshot());
                    return output.snapshot();
                } else {
                    return output.snapshot();
                }
            } catch (TimeoutException error) {
                read.cancel(true);
                Log.w(TAG, "root command timed out cmd=" + command);
                CompatibilityDiagnostics.record(
                        "ROOT-SHELL-004",
                        "A root command timed out",
                        "timeout=" + COMMAND_TIMEOUT_MILLIS
                                + " command=" + command
                                + " output=" + output.snapshot());
            } catch (ExecutionException error) {
                final Throwable cause = error.getCause();
                if (cause instanceof IOException) {
                    throw (IOException) cause;
                }
                throw new IOException("root shell reader failed", cause);
            } catch (InterruptedException error) {
                read.cancel(true);
                Thread.currentThread().interrupt();
                throw new IOException("root shell interrupted", error);
            }
        } catch (IOException error) {
            Log.w(TAG, "root shell io error cmd=" + command
                    + " output=" + output.snapshot(), error);
            CompatibilityDiagnostics.record(
                    "ROOT-SHELL-003",
                    "Root shell communication failed",
                    "command=" + command
                            + " output=" + output.snapshot(),
                    error);
        }

        close();
        return output.snapshot();
    }

    private String readCommandOutput(
            final BufferedReader reader,
            final String marker,
            final ShellOutput output) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith(marker)) {
                return line.substring(marker.length()).trim();
            }
            output.append(line);
        }
        return null;
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
        if (mProcess != null) {
            mProcess.destroyForcibly();
        }
        closeQuietly(mWriter);
        closeQuietly(mReader);
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

    private static final class ShellOutput {
        private final StringBuilder mText = new StringBuilder();
        private boolean mTruncated;

        synchronized void append(final String line) {
            if (mText.length() >= MAX_OUTPUT_CHARS) {
                mTruncated = true;
                return;
            }
            final int available = MAX_OUTPUT_CHARS - mText.length();
            if (line.length() < available) {
                mText.append(line).append('\n');
                return;
            }
            mText.append(line, 0, available);
            mTruncated = true;
        }

        synchronized String snapshot() {
            return mTruncated
                    ? mText + "\n[MagicDesk: command output truncated]"
                    : mText.toString();
        }
    }
}

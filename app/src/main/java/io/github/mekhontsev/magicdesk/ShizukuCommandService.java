package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.system.Os;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public final class ShizukuCommandService extends IShizukuCommandService.Stub {
    private static final String TAG = "MagicDeskShizuku";
    private static final int MAX_OUTPUT_CHARS = 384 * 1024;

    public ShizukuCommandService() {
        Log.i(TAG, "command service started uid=" + Os.getuid());
    }

    public ShizukuCommandService(final Context context) {
        this();
    }

    @Override
    public int uid() {
        return Os.getuid();
    }

    @Override
    public String execute(final String command) {
        if (command == null || command.isEmpty()) {
            return "-1\nempty command";
        }
        Process process = null;
        final StringBuilder output = new StringBuilder();
        boolean truncated = false;
        try {
            process = new ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                final char[] buffer = new char[8192];
                int read;
                while ((read = reader.read(buffer)) >= 0) {
                    final int available = MAX_OUTPUT_CHARS - output.length();
                    if (available > 0) {
                        output.append(buffer, 0, Math.min(available, read));
                    }
                    truncated |= read > available;
                }
            }
            final int exitCode = process.waitFor();
            if (truncated) {
                output.append("\n[MagicDesk: command output truncated]");
            }
            return exitCode + "\n" + output;
        } catch (IOException error) {
            return "-1\n" + usefulMessage(error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return "-1\ncommand interrupted";
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    @Override
    public void destroy() {
        Log.i(TAG, "command service stopped");
        System.exit(0);
    }

    private static String usefulMessage(final Throwable error) {
        final String message = error.getMessage();
        return message == null || message.isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}

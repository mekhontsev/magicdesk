package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.system.Os;
import android.util.Log;

import java.io.IOException;

public final class ShizukuCommandService extends IShizukuCommandService.Stub {
    private static final String TAG = "MagicDeskShizuku";

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
        try {
            process = new ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            final BoundedProcessRunner.Result result =
                    BoundedProcessRunner.run(process);
            return result.exitCode + "\n" + result.output;
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

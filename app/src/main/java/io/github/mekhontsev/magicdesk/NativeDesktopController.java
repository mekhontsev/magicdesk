package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

final class NativeDesktopController {
    private static final String TAG = "MagicDeskNativeDesktop";
    private static final String WMSHELL =
            "/system/bin/cmd statusbar wmshell-passthrough";
    private static final String MOVE_TASK_TO_DESK =
            WMSHELL + " desktopmode moveTaskToDesk ";
    private static final String HELP = WMSHELL + " help";
    private static final String DESKTOPMODE_HELP_ENTRY = "desktopmode";
    private static final String MOVE_TASK_HELP_ENTRY = "moveTaskToDesk <taskId>";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(final Runnable runnable) {
                    final Thread thread = new Thread(runnable, "MagicDeskNativeDesktop");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private static Boolean sAvailable;

    private NativeDesktopController() {
    }

    interface Callback {
        void onComplete(boolean success, String message);
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

    static void moveTaskToDesktop(final TaskRepository.TaskEntry task,
            final Rect restoreBounds, final Callback callback) {
        EXECUTOR.execute(() -> {
            final boolean restoreTouchpad = ConsoleModeSwitcher.isTouchpadVisible();
            try {
                moveTaskToDesktop(task.taskId);
                ExistingTaskController.waitForNativeDesktopTask(
                        task.taskId, task.displayId);
                runRootCommand(TaskRepository.createCaptionInsetsCommand(
                        task.displayId, task.taskId, false));
                if (restoreBounds == null || restoreBounds.isEmpty()) {
                    restoreTouchpadIfNeeded(restoreTouchpad);
                    complete(callback, true, "");
                    return;
                }
                TaskRepository.resizeTaskBounds(task, restoreBounds,
                        result -> {
                            if (result.success) {
                                restoreTouchpadIfNeeded(restoreTouchpad);
                            }
                            complete(callback, result.success, result.message);
                        });
            } catch (IOException e) {
                complete(callback, false, usefulMessage(e));
            }
        });
    }

    private static void restoreTouchpadIfNeeded(final boolean restoreTouchpad) {
        if (restoreTouchpad
                && RuntimeAccess.has(
                        RuntimeAccess.Capability.PHONE_SCREEN_CONTROL)) {
            ConsoleModeSwitcher.restoreTouchpadIfMissing();
        }
    }

    private static void complete(final Callback callback, final boolean success,
            final String message) {
        if (callback != null) {
            callback.onComplete(success, message == null ? "" : message);
        }
    }

    private static String runRootCommand(final String command) throws IOException {
        return PrivilegedCommandRunner.run(command);
    }

    private static String usefulMessage(final IOException error) {
        final String message = error.getMessage();
        return message == null || message.isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}

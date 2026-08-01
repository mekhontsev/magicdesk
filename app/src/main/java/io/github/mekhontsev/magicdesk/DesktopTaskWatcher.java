package io.github.mekhontsev.magicdesk;

import android.os.Handler;
import android.util.Log;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class DesktopTaskWatcher {
    interface Listener {
        boolean isActive(int generation);
        void onReady(int generation);
        void onChanged(int generation);
        void onImmersiveRequest(int generation, int taskId, boolean requesting);
        void onTaskGone(int generation, int taskId);
        void onNativeMaximizeEvent(int generation, String event, int taskId);
        void onDisconnected(int generation);
    }

    private static final String TAG = "MagicDeskTasks";

    private final Handler mHandler;
    private final Listener mListener;
    private final ExecutorService mExecutor =
            Executors.newSingleThreadExecutor();
    private final Map<Long, TaskRepository.ActionCallback> mFocusCallbacks =
            new HashMap<>();

    private long mNextFocusSequence;
    private ShizukuAccess.StreamHandle mShizukuStream;

    DesktopTaskWatcher(final Handler handler, final Listener listener) {
        mHandler = handler;
        mListener = listener;
    }

    void start(final int generation) {
        mExecutor.execute(() -> run(generation));
    }

    synchronized void stop() {
        failPendingFocusCallbacks("task watcher stopped");
        closeQuietly(mShizukuStream);
        mShizukuStream = null;
    }

    synchronized boolean sendCommand(final String command) {
        if (mShizukuStream == null) {
            return false;
        }
        try {
            writeCommand(command);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "failed to send task watcher command: " + command, e);
            closeQuietly(mShizukuStream);
            mShizukuStream = null;
            return false;
        }
    }

    synchronized void sendFocusStack(
            final int displayId,
            final List<Integer> taskIds,
            final TaskRepository.ActionCallback callback) {
        if (mShizukuStream == null) {
            completeFocusCallback(callback, false, "task watcher unavailable");
            return;
        }
        final long sequence = ++mNextFocusSequence;
        if (callback != null) {
            mFocusCallbacks.put(Long.valueOf(sequence), callback);
        }
        try {
            final StringBuilder command =
                    new StringBuilder("focus-stack ")
                            .append(sequence)
                            .append(' ')
                            .append(displayId);
            for (final Integer taskId : taskIds) {
                command.append(' ').append(taskId.intValue());
            }
            writeCommand(command.toString());
        } catch (IOException e) {
            mFocusCallbacks.remove(Long.valueOf(sequence));
            completeFocusCallback(callback, false, "task watcher write failed");
            Log.w(TAG, "failed to send task stack focus", e);
            closeQuietly(mShizukuStream);
            mShizukuStream = null;
        }
    }

    synchronized void failPendingFocusCallbacks(final String message) {
        if (mFocusCallbacks.isEmpty()) {
            return;
        }
        final List<TaskRepository.ActionCallback> callbacks =
                new ArrayList<>(mFocusCallbacks.values());
        mFocusCallbacks.clear();
        for (final TaskRepository.ActionCallback callback : callbacks) {
            completeFocusCallback(callback, false, message);
        }
    }

    private void run(final int generation) {
        final String command = AppProcessCommand.exec(
                "io.github.mekhontsev.magicdesk.TaskStackWatcherCommand");
        ShizukuAccess.StreamHandle shizukuStream = null;
        try {
            shizukuStream = ShizukuAccess.openStream(command);
            final InputStream input = shizukuStream.inputStream();
            synchronized (this) {
                if (!mListener.isActive(generation)) {
                    closeQuietly(shizukuStream);
                    return;
                }
                mShizukuStream = shizukuStream;
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    handleLine(line, generation);
                }
            }
        } catch (IOException e) {
            if (mListener.isActive(generation)) {
                Log.w(TAG, "task watcher failed", e);
            }
        } finally {
            synchronized (this) {
                if (mShizukuStream == shizukuStream) {
                    mShizukuStream = null;
                }
            }
            closeQuietly(shizukuStream);
            if (mListener.isActive(generation)) {
                mHandler.post(() -> {
                    if (mListener.isActive(generation)) {
                        failPendingFocusCallbacks("task watcher disconnected");
                        mListener.onDisconnected(generation);
                    }
                });
            }
        }
    }

    private void writeCommand(final String command) throws IOException {
        if (mShizukuStream != null) {
            mShizukuStream.writeLine(command);
            return;
        }
        throw new IOException("task watcher unavailable");
    }

    private static void closeQuietly(final Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Stream shutdown is best effort.
        }
    }

    private void handleLine(final String line, final int generation) {
        if ("ready".equals(line)) {
            postIfActive(generation, () -> mListener.onReady(generation));
            return;
        }
        if ("changed".equals(line)) {
            postIfActive(generation, () -> mListener.onChanged(generation));
            return;
        }
        final String[] fields = line.trim().split("\\s+");
        if (fields.length == 4 && "immersive-request".equals(fields[0])) {
            try {
                final int taskId = Integer.parseInt(fields[1]);
                final boolean requesting = Integer.parseInt(fields[2]) != 0;
                postIfActive(generation, () ->
                        mListener.onImmersiveRequest(
                                generation, taskId, requesting));
                return;
            } catch (NumberFormatException e) {
                Log.w(TAG, "invalid immersive request: " + line, e);
                return;
            }
        }
        if (fields.length == 2 && "task-gone".equals(fields[0])) {
            try {
                final int taskId = Integer.parseInt(fields[1]);
                postIfActive(generation, () ->
                        mListener.onTaskGone(generation, taskId));
                return;
            } catch (NumberFormatException e) {
                Log.w(TAG, "invalid removed task: " + line, e);
                return;
            }
        }
        if (fields.length == 2
                && ("native-maximize".equals(fields[0])
                        || "native-maximize-exit".equals(fields[0]))) {
            try {
                final int taskId = Integer.parseInt(fields[1]);
                postIfActive(generation, () ->
                        mListener.onNativeMaximizeEvent(
                                generation, fields[0], taskId));
                return;
            } catch (NumberFormatException e) {
                Log.w(TAG, "invalid native maximize event: " + line, e);
                return;
            }
        }
        final boolean applied = fields.length == 3
                && "focus-stack-applied".equals(fields[0]);
        final boolean failed = fields.length == 3
                && "focus-stack-failed".equals(fields[0]);
        if (applied || failed) {
            try {
                final long sequence = Long.parseLong(fields[1]);
                final int taskCount = Integer.parseInt(fields[2]);
                postIfActive(generation, () -> {
                    final TaskRepository.ActionCallback callback;
                    synchronized (DesktopTaskWatcher.this) {
                        callback = mFocusCallbacks.remove(
                                Long.valueOf(sequence));
                    }
                    completeFocusCallback(
                            callback,
                            applied,
                            applied
                                    ? "focused " + taskCount + " tasks"
                                    : "task stack focus failed");
                });
                return;
            } catch (NumberFormatException e) {
                Log.w(TAG,
                        "invalid task watcher stack focus result: " + line,
                        e);
                return;
            }
        }
        if (!line.trim().isEmpty()) {
            Log.w(TAG, "task watcher: " + line);
        }
    }

    private void postIfActive(
            final int generation,
            final Runnable action) {
        mHandler.post(() -> {
            if (mListener.isActive(generation)) {
                action.run();
            }
        });
    }

    private static void completeFocusCallback(
            final TaskRepository.ActionCallback callback,
            final boolean success,
            final String message) {
        if (callback != null) {
            callback.onComplete(
                    new TaskRepository.ActionResult(success, message));
        }
    }
}

package io.github.mekhontsev.magicdesk;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Serializes TaskRepository commands with phone-desktop recovery. */
final class TaskCommandQueue {
    private static volatile Thread sWorkerThread;
    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(() -> {
                    sWorkerThread = Thread.currentThread();
                    runnable.run();
                }, "MagicDeskTasks");
                thread.setDaemon(true);
                return thread;
            });

    private TaskCommandQueue() {
    }

    static void execute(final Runnable operation) {
        EXECUTOR.execute(operation);
    }

    static <T> T call(final Operation<T> operation) {
        if (Thread.currentThread() == sWorkerThread) {
            return operation.run();
        }
        final Future<T> result = EXECUTOR.submit(operation::run);
        try {
            return result.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "task command queue interrupted", error);
        } catch (ExecutionException error) {
            final Throwable cause = error.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException(
                    "task command queue failed", cause);
        }
    }

    interface Operation<T> {
        T run();
    }
}

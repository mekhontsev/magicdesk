package io.github.mekhontsev.magicdesk;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/** Process-lifetime queue for ordered desktop and shell operations. */
final class SerializedDesktopOperationQueue {
    private final ExecutorService mExecutor =
            Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(final Runnable runnable) {
                    final Thread thread = new Thread(
                            runnable, "MagicDeskConsoleSwitcher");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    void execute(final Runnable action) {
        mExecutor.execute(action);
    }
}

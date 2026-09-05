package io.github.mekhontsev.magicdesk;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Android's bounded transition/input barrier; never a task-state poller. */
final class FrameworkWindowCommitBarrier {
    private static final AtomicLong CALLS = new AtomicLong();
    private static final AtomicLong FAILURES = new AtomicLong();
    private static volatile long sLastDurationMillis;
    private static volatile String sLastError = "none";
    private static Object sWindowManager;

    private FrameworkWindowCommitBarrier() {
    }

    static void awaitSystemTransitions() throws ReflectiveOperationException {
        CALLS.incrementAndGet();
        EventDrivenWaits.noteFrameworkWait(
                EventDrivenWaits.Reason.WINDOW_TRANSITION_COMMIT);
        final long started = System.nanoTime();
        try {
            // WM waits on animation/transition completion, then on SF's
            // input-window acknowledgement. Its deadline is framework-owned
            // and global, not a guessed delay or a per-display token callback.
            final Object windowManager = windowManager();
            windowManager.getClass().getMethod("syncInputTransactions", Boolean.TYPE)
                    .invoke(windowManager, Boolean.TRUE);
            sLastError = "none";
        } catch (ReflectiveOperationException | RuntimeException error) {
            FAILURES.incrementAndGet();
            sLastError = error.getClass().getSimpleName();
            throw error;
        } finally {
            sLastDurationMillis = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - started);
        }
    }

    static String diagnostics() {
        return "mechanism=framework-bounded-global, calls=" + CALLS.get()
                + ", failures=" + FAILURES.get()
                + ", lastDurationMs=" + sLastDurationMillis
                + ", lastError=" + sLastError;
    }

    private static synchronized Object windowManager()
            throws ReflectiveOperationException {
        if (sWindowManager == null) {
            sWindowManager = Class.forName("android.view.WindowManagerGlobal")
                    .getMethod("getWindowManagerService").invoke(null);
            if (sWindowManager == null) {
                throw new IllegalStateException("WindowManager service is unavailable");
            }
        }
        return sWindowManager;
    }
}

package io.github.mekhontsev.magicdesk;

import java.util.concurrent.atomic.AtomicBoolean;

/** Carries one user cancellation request across the active self-test run. */
final class DesktopSelfTestCancellation {
    private static final AtomicBoolean ACTIVE = new AtomicBoolean();
    private static final AtomicBoolean REQUESTED = new AtomicBoolean();

    private DesktopSelfTestCancellation() {
    }

    static void beginRun() {
        REQUESTED.set(false);
        ACTIVE.set(true);
    }

    static boolean request() {
        return ACTIVE.get() && !REQUESTED.getAndSet(true);
    }

    static boolean isRequested() {
        return ACTIVE.get() && REQUESTED.get();
    }

    static void checkpoint() {
        if (isRequested()) {
            throw new Cancelled();
        }
    }

    static void finishRun() {
        ACTIVE.set(false);
        REQUESTED.set(false);
    }

    static final class Cancelled extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}

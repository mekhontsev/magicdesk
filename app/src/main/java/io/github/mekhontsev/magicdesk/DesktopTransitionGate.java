package io.github.mekhontsev.magicdesk;

import java.util.concurrent.atomic.AtomicReference;

/** Guards overlapping desktop activation, close, and mirror transitions. */
final class DesktopTransitionGate {
    enum Operation {
        START,
        MODE_TRANSITION,
        CLOSE
    }

    private final AtomicReference<Operation> mActive =
            new AtomicReference<>();

    boolean begin(final Operation operation) {
        if (operation == null) {
            throw new IllegalArgumentException(
                    "desktop transition operation is required");
        }
        return mActive.compareAndSet(null, operation);
    }

    boolean finish(final Operation operation) {
        return mActive.compareAndSet(operation, null);
    }

    boolean isActive(final Operation operation) {
        return mActive.get() == operation;
    }
}

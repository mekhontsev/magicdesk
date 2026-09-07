package io.github.mekhontsev.magicdesk;

import java.util.function.Consumer;

/** Enables panel attachment only after its host task can accept input. */
final class DesktopPanelFocusGate {
    interface Backend {
        void apply(boolean focusable, TaskRepository.ActionCallback callback);
    }

    private final Backend mBackend;
    private final Runnable mReadyCallback;
    private final Consumer<String> mFailureCallback;
    private boolean mRequested;
    private boolean mReady;
    private int mGeneration;

    DesktopPanelFocusGate(final Backend backend, final Runnable readyCallback,
            final Consumer<String> failureCallback) {
        mBackend = backend;
        mReadyCallback = readyCallback;
        mFailureCallback = failureCallback;
    }

    boolean require(final boolean focusable) {
        if (mRequested == focusable) {
            return !focusable || mReady;
        }
        mRequested = focusable;
        mReady = false;
        final int generation = ++mGeneration;
        mBackend.apply(focusable, result -> {
            // Closing/replacing a panel or its host invalidates an older ack.
            if (generation != mGeneration) {
                return;
            }
            if (result == null || !result.success) {
                mFailureCallback.accept(result == null
                        ? "chrome focus returned no result" : result.message);
                return;
            }
            mReady = focusable;
            if (focusable) {
                mReadyCallback.run();
            }
        });
        return !focusable || mReady;
    }

    void reset() {
        ++mGeneration;
        mRequested = false;
        mReady = false;
    }
}

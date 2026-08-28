package io.github.mekhontsev.magicdesk;

import android.util.Log;
import android.view.InputWindowHandle;
import android.window.WindowInfosListener;

import java.io.Closeable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Event source for SurfaceFlinger's committed input-window topology. */
final class FrameworkInputWindowObservationSource implements Closeable,
        InputFocusCommitAwaiter.EventSource {
    private static final String TAG = "MagicDeskInputWindows";
    private static final AtomicLong EVENTS = new AtomicLong();
    private static final AtomicLong WAITS = new AtomicLong();
    private static final AtomicLong TIMEOUTS = new AtomicLong();

    private static volatile String sState = "not-started";
    private static volatile String sLastError = "none";

    private final Object mLock = new Object();
    private final WindowInfosListener mListener = new WindowInfosListener() {
        @Override
        public void onWindowInfosChanged(
                final InputWindowHandle[] inputWindowHandles,
                final WindowInfosListener.DisplayInfo[] displayInfos) {
            synchronized (mLock) {
                if (mClosed) {
                    return;
                }
                mGeneration++;
                EVENTS.incrementAndGet();
                mLock.notifyAll();
            }
        }
    };

    private boolean mRegistered;
    private boolean mClosed;
    private long mGeneration;

    void start() {
        synchronized (mLock) {
            if (mClosed || mRegistered) {
                return;
            }
        }
        try {
            mListener.register();
            synchronized (mLock) {
                if (mClosed) {
                    mListener.unregister();
                    return;
                }
                mRegistered = true;
                // Registration returns the initial topology synchronously.
                mGeneration++;
                mLock.notifyAll();
            }
            sState = "registered";
            sLastError = "none";
        } catch (RuntimeException | LinkageError error) {
            sState = "unavailable";
            sLastError = usefulMessage(error);
            Log.w(TAG, "input-window events unavailable", error);
        }
    }

    @Override
    public long checkpoint() {
        synchronized (mLock) {
            return mGeneration;
        }
    }

    @Override
    public boolean isAvailable() {
        synchronized (mLock) {
            return mRegistered && !mClosed;
        }
    }

    @Override
    public boolean awaitChangeAfter(
            final long checkpoint,
            final long timeoutMillis) throws InterruptedException {
        if (timeoutMillis <= 0L) {
            throw new IllegalArgumentException(
                    "input-window event timeout must be positive");
        }
        final long deadlineNanos = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        synchronized (mLock) {
            if (!mRegistered || mClosed) {
                return false;
            }
            WAITS.incrementAndGet();
            while (mRegistered && !mClosed && mGeneration <= checkpoint) {
                final long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0L) {
                    TIMEOUTS.incrementAndGet();
                    return false;
                }
                EventDrivenWaits.await(
                        mLock,
                        EventDrivenWaits.Reason.INPUT_WINDOW_COMMIT,
                        Math.max(1L,
                                TimeUnit.NANOSECONDS.toMillis(
                                        remainingNanos)));
            }
            return mGeneration > checkpoint;
        }
    }

    @Override
    public void close() {
        final boolean unregister;
        synchronized (mLock) {
            if (mClosed) {
                return;
            }
            mClosed = true;
            unregister = mRegistered;
            mRegistered = false;
            mLock.notifyAll();
        }
        if (unregister) {
            try {
                mListener.unregister();
            } catch (RuntimeException | LinkageError error) {
                Log.w(TAG, "could not unregister input-window events", error);
            }
        }
        sState = "closed";
    }

    static String diagnostics() {
        return "state=" + sState
                + ", events=" + EVENTS.get()
                + ", waits=" + WAITS.get()
                + ", timeouts=" + TIMEOUTS.get()
                + ", lastError=" + sLastError;
    }

    private static String usefulMessage(final Throwable error) {
        if (error == null) {
            return "unknown";
        }
        final String message = error.getMessage();
        return message == null || message.isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}

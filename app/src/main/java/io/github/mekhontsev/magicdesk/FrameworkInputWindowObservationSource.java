package io.github.mekhontsev.magicdesk;

import android.util.Log;
import android.util.Pair;
import android.view.InputWindowHandle;
import android.window.WindowInfosListener;

import java.io.Closeable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Event source for SurfaceFlinger's committed input-window topology. */
final class FrameworkInputWindowObservationSource implements Closeable,
        InputFocusCommitAwaiter.EventSource {
    interface Listener {
        void onInputWindowsChanged(FrameworkInputWindowState.Snapshot snapshot);
    }

    private static final String TAG = "MagicDeskInputWindows";
    private static final AtomicLong EVENTS = new AtomicLong();
    private static final AtomicLong WAITS = new AtomicLong();
    private static final AtomicLong TIMEOUTS = new AtomicLong();
    private static final InputWindowHandleAdapter HANDLE_ADAPTER =
            InputWindowHandleAdapter.create();
    private static final AtomicBoolean READ_ERROR_REPORTED =
            new AtomicBoolean();

    private static volatile String sState = "not-started";
    private static volatile String sLastError = "none";

    private final Object mLock = new Object();
    private final Listener mObservationListener;
    private final WindowInfosListener mWindowInfosListener =
            new WindowInfosListener() {
        @Override
        public void onWindowInfosChanged(
                final InputWindowHandle[] inputWindowHandles,
                final WindowInfosListener.DisplayInfo[] displayInfos) {
            publish(inputWindowHandles);
        }
    };

    private boolean mRegistered;
    private boolean mClosed;
    private long mGeneration;
    private volatile FrameworkInputWindowState.Snapshot mLatestSnapshot =
            FrameworkInputWindowState.Snapshot.unavailable();

    FrameworkInputWindowObservationSource(final Listener listener) {
        mObservationListener = listener;
    }

    void start() {
        synchronized (mLock) {
            if (mClosed || mRegistered) {
                return;
            }
        }
        try {
            final Pair<InputWindowHandle[], WindowInfosListener.DisplayInfo[]>
                    initial = mWindowInfosListener.register();
            synchronized (mLock) {
                if (mClosed) {
                    mWindowInfosListener.unregister();
                    return;
                }
                mRegistered = true;
            }
            if (initial != null && initial.first != null) {
                publish(initial.first);
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
                mWindowInfosListener.unregister();
            } catch (RuntimeException | LinkageError error) {
                Log.w(TAG, "could not unregister input-window events", error);
            }
        }
        sState = "closed";
    }

    FrameworkInputWindowState.Snapshot latestSnapshot() {
        return mLatestSnapshot;
    }

    static String diagnostics() {
        return "state=" + sState
                + ", events=" + EVENTS.get()
                + ", waits=" + WAITS.get()
                + ", timeouts=" + TIMEOUTS.get()
                + ", lastError=" + sLastError;
    }

    private void publish(final InputWindowHandle[] handles) {
        final FrameworkInputWindowState.Snapshot snapshot =
                snapshotFromHandles(handles);
        synchronized (mLock) {
            if (mClosed) {
                return;
            }
            mLatestSnapshot = snapshot;
            mGeneration++;
            EVENTS.incrementAndGet();
            mLock.notifyAll();
        }
        if (mObservationListener != null) {
            try {
                mObservationListener.onInputWindowsChanged(snapshot);
            } catch (RuntimeException error) {
                Log.w(TAG, "input-window observer failed", error);
            }
        }
    }

    private static String usefulMessage(final Throwable error) {
        if (error == null) {
            return "unknown";
        }
        final String message = error.getMessage();
        return message == null || message.isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private static FrameworkInputWindowState.Snapshot snapshotFromHandles(
            final InputWindowHandle[] handles) {
        if (!HANDLE_ADAPTER.available || handles == null) {
            return FrameworkInputWindowState.Snapshot.unavailable();
        }
        final List<FrameworkInputWindowState.Window> windows =
                new ArrayList<>(handles.length);
        for (final InputWindowHandle handle : handles) {
            if (handle == null) {
                continue;
            }
            try {
                windows.add(HANDLE_ADAPTER.read(handle));
            } catch (ReflectiveOperationException | RuntimeException error) {
                if (READ_ERROR_REPORTED.compareAndSet(false, true)) {
                    Log.w(TAG, "could not read input-window state", error);
                }
                return FrameworkInputWindowState.Snapshot.unavailable();
            }
        }
        return FrameworkInputWindowState.fromWindows(windows);
    }

    private static final class InputWindowHandleAdapter {
        final Field displayId;
        final Field packageName;
        final Field name;
        final Field ownerUid;
        final Field inputConfig;
        final boolean available;

        private InputWindowHandleAdapter(
                final Field displayId,
                final Field packageName,
                final Field name,
                final Field ownerUid,
                final Field inputConfig) {
            this.displayId = displayId;
            this.packageName = packageName;
            this.name = name;
            this.ownerUid = ownerUid;
            this.inputConfig = inputConfig;
            available = true;
        }

        private InputWindowHandleAdapter() {
            displayId = null;
            packageName = null;
            name = null;
            ownerUid = null;
            inputConfig = null;
            available = false;
        }

        static InputWindowHandleAdapter create() {
            try {
                final Class<?> type = InputWindowHandle.class;
                return new InputWindowHandleAdapter(
                        type.getField("displayId"),
                        type.getField("packageName"),
                        type.getField("name"),
                        type.getField("ownerUid"),
                        type.getField("inputConfig"));
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.w(TAG, "typed input-window fields unavailable", error);
                return new InputWindowHandleAdapter();
            }
        }

        FrameworkInputWindowState.Window read(final InputWindowHandle handle)
                throws ReflectiveOperationException {
            return new FrameworkInputWindowState.Window(
                    displayId.getInt(handle),
                    stringValue(packageName.get(handle)),
                    stringValue(name.get(handle)),
                    ownerUid.getInt(handle),
                    inputConfig.getInt(handle));
        }

        private static String stringValue(final Object value) {
            return value instanceof String ? (String) value : "";
        }
    }
}

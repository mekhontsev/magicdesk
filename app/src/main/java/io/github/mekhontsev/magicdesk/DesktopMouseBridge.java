package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

final class DesktopMouseBridge {
    private static final String TAG = "MagicDeskMouse";
    private static final String HELPER_NAME =
            "libmagicdesk_uinput_bridge.so";
    private static final long RESTART_DELAY_MILLIS = 1_000L;
    private static final long CAPTURE_STOP_TIMEOUT_MILLIS = 1_000L;
    private final Object mLock = new Object();
    private final Context mContext;
    private final Runnable mStateChanged;
    private final NativeInputBridgeStatsClient mStatsClient =
            new NativeInputBridgeStatsClient("MAGICDESK_MOUSE_STATS");

    private boolean mRequested;
    private boolean mReady;
    private boolean mCaptureRequested;
    private boolean mCaptureStopPending;
    private boolean mPointerRestoreArmed;
    private int mGeneration;
    private Thread mSupervisorThread;
    private ShellStreamHandle mStream;
    private float mMoveRemainderX;
    private float mMoveRemainderY;
    private float mScrollRemainder;
    private boolean mPrimaryButtonPressed;

    DesktopMouseBridge(
            final Context context,
            final Runnable stateChanged) {
        mContext = context.getApplicationContext();
        mStateChanged = stateChanged;
    }

    void start() {
        final int generation;
        synchronized (mLock) {
            if (mRequested) {
                return;
            }
            mRequested = true;
            mReady = false;
            generation = ++mGeneration;
            mSupervisorThread = new Thread(
                    () -> runSupervisor(generation),
                    "MagicDeskMouseBridge");
            mSupervisorThread.setDaemon(true);
            mSupervisorThread.start();
        }
    }

    void stop() {
        setPrimaryButtonPressed(false);
        setCaptureEnabled(false);
        final ShellStreamHandle stream;
        final Thread supervisor;
        final boolean notifyStateChanged;
        synchronized (mLock) {
            if (!mRequested && mStream == null) {
                return;
            }
            mRequested = false;
            notifyStateChanged = mReady;
            mReady = false;
            mCaptureRequested = false;
            mCaptureStopPending = false;
            mPointerRestoreArmed = false;
            mMoveRemainderX = 0.0f;
            mMoveRemainderY = 0.0f;
            mScrollRemainder = 0.0f;
            mPrimaryButtonPressed = false;
            ++mGeneration;
            stream = mStream;
            supervisor = mSupervisorThread;
            mStream = null;
            mSupervisorThread = null;
            mLock.notifyAll();
        }
        closeQuietly(stream);
        if (supervisor != null) {
            supervisor.interrupt();
        }
        if (notifyStateChanged) {
            mStateChanged.run();
        }
    }

    boolean isReady() {
        synchronized (mLock) {
            return mRequested && mReady && mStream != null;
        }
    }

    boolean isRunning() {
        synchronized (mLock) {
            return mRequested;
        }
    }

    void setCaptureEnabled(final boolean enabled) {
        final ShellStreamHandle stream;
        synchronized (mLock) {
            if (mCaptureRequested == enabled) {
                return;
            }
            mCaptureRequested = enabled;
            stream = mRequested && mReady ? mStream : null;
            mCaptureStopPending = !enabled && stream != null;
        }
        Log.i(TAG, "physical capture enabled=" + enabled);
        if (stream == null) {
            return;
        }
        if (!writeControl(stream, enabled ? "start" : "stop")) {
            synchronized (mLock) {
                if (mStream == stream) {
                    mCaptureStopPending = false;
                    mLock.notifyAll();
                }
            }
            return;
        }
        if (!enabled) {
            awaitCaptureStopped(stream);
        }
    }

    private void awaitCaptureStopped(final ShellStreamHandle stream) {
        final long deadline = SystemClock.uptimeMillis()
                + CAPTURE_STOP_TIMEOUT_MILLIS;
        synchronized (mLock) {
            while (mStream == stream && mCaptureStopPending) {
                final long remaining = deadline
                        - SystemClock.uptimeMillis();
                if (remaining <= 0L) {
                    Log.w(TAG,
                            "Timed out waiting for physical capture release");
                    return;
                }
                try {
                    EventDrivenWaits.await(
                            mLock,
                            EventDrivenWaits.Reason.INPUT_CAPTURE_RELEASE,
                            remaining);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    void refreshSources(final List<DesktopMouseDevice> mice) {
        final ShellStreamHandle stream;
        synchronized (mLock) {
            if (!mRequested) {
                return;
            }
            stream = mStream;
        }
        if (stream == null) {
            return;
        }
        final StringBuilder command = new StringBuilder("sources");
        for (final DesktopMouseDevice mouse : mice) {
            command.append(' ').append(mouse.path);
        }
        try {
            stream.writeLine(command.toString());
        } catch (IOException error) {
            Log.w(TAG, "Could not refresh mouse sources", error);
        }
    }

    void restorePointerPositionIfDisplacedOnNextMotion() {
        final ShellStreamHandle stream;
        synchronized (mLock) {
            if (!mRequested) {
                return;
            }
            mPointerRestoreArmed = true;
            stream = mStream;
        }
        if (stream != null) {
            writeControl(stream, "restore-pointer-on-motion");
        }
    }

    boolean movePointer(final float deltaX, final float deltaY) {
        final ShellStreamHandle stream;
        final int moveX;
        final int moveY;
        synchronized (mLock) {
            if (!mRequested || !mReady || mStream == null) {
                return false;
            }
            mMoveRemainderX += deltaX;
            mMoveRemainderY += deltaY;
            moveX = (int) mMoveRemainderX;
            moveY = (int) mMoveRemainderY;
            mMoveRemainderX -= moveX;
            mMoveRemainderY -= moveY;
            stream = mStream;
        }
        return moveX == 0 && moveY == 0
                || writePointerControl(
                        stream, "move " + moveX + " " + moveY);
    }

    boolean clickPointer(final int button) {
        if (button != MotionEvent.BUTTON_PRIMARY) {
            return false;
        }
        final ShellStreamHandle stream = readyStream();
        return stream != null
                && writePointerControl(stream, "click-primary");
    }

    boolean setPrimaryButtonPressed(final boolean pressed) {
        final ShellStreamHandle stream;
        synchronized (mLock) {
            if (!mRequested || !mReady || mStream == null) {
                return false;
            }
            if (mPrimaryButtonPressed == pressed) {
                return true;
            }
            stream = mStream;
        }
        if (!writePointerControl(
                stream, pressed ? "primary-down" : "primary-up")) {
            return false;
        }
        synchronized (mLock) {
            if (mRequested && mReady && mStream == stream) {
                mPrimaryButtonPressed = pressed;
                return true;
            }
        }
        return false;
    }

    boolean scrollPointer(final float amount) {
        final ShellStreamHandle stream;
        final int steps;
        synchronized (mLock) {
            if (!mRequested || !mReady || mStream == null) {
                return false;
            }
            mScrollRemainder += amount;
            steps = (int) mScrollRemainder;
            mScrollRemainder -= steps;
            stream = mStream;
        }
        return steps == 0
                || writePointerControl(stream, "scroll " + steps);
    }

    InputRelayRuntimeDiagnostics.BridgeSnapshot captureDiagnostics() {
        final ShellStreamHandle stream;
        final boolean running;
        final boolean ready;
        final int generation;
        synchronized (mLock) {
            running = mRequested;
            ready = mReady && mStream != null;
            generation = mGeneration;
            stream = ready ? mStream : null;
        }
        final NativeInputBridgeStatsClient.Result stats =
                mStatsClient.request(stream);
        synchronized (mLock) {
            return new InputRelayRuntimeDiagnostics.BridgeSnapshot(
                    running,
                    mRequested && mReady && mStream == stream,
                    stream != null && mCaptureRequested,
                    generation,
                    stats.detail,
                    running ? stats.error : "not running");
        }
    }

    private ShellStreamHandle readyStream() {
        synchronized (mLock) {
            return mRequested && mReady ? mStream : null;
        }
    }

    private void runSupervisor(final int generation) {
        while (isActive(generation)) {
            try {
                runOnce(generation);
            } catch (IOException error) {
                if (isActive(generation)) {
                    Log.w(TAG, "Mouse bridge failed", error);
                    CompatibilityDiagnostics.record(
                            "INPUT-MOUSE-001",
                            "The global right-click bridge stopped",
                            "shell=" + ShellAccess.statusLabel(),
                            error);
                }
            }
            if (!isActive(generation)) {
                break;
            }
            try {
                RuntimeDelays.pauseInterruptibly(
                        RuntimeDelays.Reason.SUPERVISOR_BACKOFF,
                        RESTART_DELAY_MILLIS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        synchronized (mLock) {
            if (mGeneration == generation) {
                mSupervisorThread = null;
            }
        }
    }

    private void runOnce(final int generation) throws IOException {
        final String inputDump = FrameworkInputSnapshotSource.readRemote();
        final List<DesktopMouseDevice> mice =
                DesktopInputDeviceDiscovery.findMice(inputDump);
        final File helper = new File(
                mContext.getApplicationInfo().nativeLibraryDir,
                HELPER_NAME);
        if (!helper.isFile()) {
            throw new IOException(
                    "packaged uinput bridge is missing: " + helper);
        }

        final StringBuilder command =
                new StringBuilder("exec ").append(ShellCommandLine.quote(
                        helper.getAbsolutePath()));
        for (final DesktopMouseDevice mouse : mice) {
            command.append(' ').append(ShellCommandLine.quote(mouse.path));
        }

        final ShellStreamHandle stream =
                ShellAccess.openOwnedStream(command.toString());
        synchronized (mLock) {
            if (!isActiveLocked(generation)) {
                closeQuietly(stream);
                return;
            }
            mStream = stream;
            mReady = false;
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream.inputStream()))) {
            String line;
            while (isActive(generation)
                    && (line = reader.readLine()) != null) {
                handleLine(line, stream, generation);
            }
            if (isActive(generation)) {
                throw new IOException("uinput bridge exited unexpectedly");
            }
        } finally {
            final boolean notifyStateChanged;
            synchronized (mLock) {
                if (mStream == stream) {
                    mStream = null;
                    notifyStateChanged = mReady;
                    mReady = false;
                    mCaptureRequested = false;
                    mCaptureStopPending = false;
                    mPrimaryButtonPressed = false;
                    mLock.notifyAll();
                } else {
                    notifyStateChanged = false;
                }
            }
            closeQuietly(stream);
            if (notifyStateChanged) {
                mStateChanged.run();
            }
        }
    }

    private void handleLine(
            final String line,
            final ShellStreamHandle stream,
            final int generation) {
        if (line.startsWith("MAGICDESK_MOUSE_READY")) {
            final boolean restorePointer;
            final boolean capturePointer;
            final boolean notifyStateChanged;
            synchronized (mLock) {
                if (isActiveLocked(generation) && mStream == stream) {
                    notifyStateChanged = !mReady;
                    mReady = true;
                } else {
                    notifyStateChanged = false;
                }
                restorePointer = mPointerRestoreArmed;
                capturePointer = mCaptureRequested;
            }
            if (capturePointer) {
                writeControl(stream, "start");
            }
            if (restorePointer) {
                writeControl(stream, "restore-pointer-on-motion");
            }
            Log.i(TAG, line);
            if (notifyStateChanged) {
                mStateChanged.run();
            }
            return;
        }
        if (line.startsWith("MAGICDESK_MOUSE_POINTER_MOTION")) {
            synchronized (mLock) {
                if (!isActiveLocked(generation) || mStream != stream
                        || !mPointerRestoreArmed) {
                    return;
                }
                mPointerRestoreArmed = false;
            }
            ShellAccess.restorePointerPositionIfDisplaced();
            return;
        }
        if (line.startsWith("MAGICDESK_MOUSE_CAPTURE_STOPPED")) {
            synchronized (mLock) {
                if (mStream == stream) {
                    mCaptureStopPending = false;
                    mLock.notifyAll();
                }
            }
            return;
        }
        if (line.startsWith("MAGICDESK_MOUSE_STATS")) {
            synchronized (mLock) {
                if (isActiveLocked(generation) && mStream == stream) {
                    mStatsClient.accept(line);
                }
            }
            return;
        }
        if (line.startsWith("MAGICDESK_MOUSE_SECONDARY_CLICK")) {
            final int displayId = DesktopRuntimeBridge
                    .getActiveDesktopDisplayId();
            if (displayId > 0) {
                ShellAccess.injectPointerClick(
                        displayId,
                        android.view.MotionEvent.BUTTON_SECONDARY);
            }
            return;
        }
        if (line.startsWith("MAGICDESK_MOUSE_ERROR")) {
            Log.w(TAG, line);
        } else if (!line.isEmpty()) {
            Log.d(TAG, line);
        }
    }

    private boolean isActive(final int generation) {
        synchronized (mLock) {
            return isActiveLocked(generation);
        }
    }

    private boolean isActiveLocked(final int generation) {
        return mRequested && mGeneration == generation;
    }

    private static boolean writeControl(
            final ShellStreamHandle stream,
            final String command) {
        try {
            stream.writeLine(command);
            return true;
        } catch (IOException error) {
            Log.w(TAG, "Could not configure mouse bridge", error);
            return false;
        }
    }

    private static boolean writePointerControl(
            final ShellStreamHandle stream,
            final String command) {
        try {
            stream.writeLine(command);
            return true;
        } catch (IOException error) {
            Log.w(TAG, "Could not send pointer input", error);
            return false;
        }
    }

    private static void closeQuietly(final Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Closing an already disconnected stream is complete.
        }
    }
}

package io.github.mekhontsev.magicdesk;

import android.content.Context;
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
    private static final String DUMPSYS_INPUT =
            "/system/bin/dumpsys input";
    private static final long RESTART_DELAY_MILLIS = 1_000L;
    private final Object mLock = new Object();
    private final Context mContext;

    private boolean mRequested;
    private boolean mReady;
    private boolean mPointerRestoreArmed;
    private int mGeneration;
    private Thread mSupervisorThread;
    private ShellStreamHandle mStream;
    private float mMoveRemainderX;
    private float mMoveRemainderY;
    private float mScrollRemainder;
    private boolean mPrimaryButtonPressed;

    DesktopMouseBridge(final Context context) {
        mContext = context.getApplicationContext();
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
        final ShellStreamHandle stream;
        final Thread supervisor;
        synchronized (mLock) {
            if (!mRequested && mStream == null) {
                return;
            }
            mRequested = false;
            mReady = false;
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
        }
        closeQuietly(stream);
        if (supervisor != null) {
            supervisor.interrupt();
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
                Thread.sleep(RESTART_DELAY_MILLIS);
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
        final String inputDump = ShellAccess.run(DUMPSYS_INPUT);
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
                new StringBuilder("exec ").append(shellQuote(
                        helper.getAbsolutePath()));
        for (final DesktopMouseDevice mouse : mice) {
            command.append(' ').append(shellQuote(mouse.path));
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
            synchronized (mLock) {
                if (mStream == stream) {
                    mStream = null;
                    mReady = false;
                    mPrimaryButtonPressed = false;
                }
            }
            closeQuietly(stream);
        }
    }

    private void handleLine(
            final String line,
            final ShellStreamHandle stream,
            final int generation) {
        if (line.startsWith("MAGICDESK_MOUSE_READY")) {
            final boolean restorePointer;
            synchronized (mLock) {
                if (isActiveLocked(generation) && mStream == stream) {
                    mReady = true;
                }
                restorePointer = mPointerRestoreArmed;
            }
            if (restorePointer) {
                writeControl(stream, "restore-pointer-on-motion");
            }
            Log.i(TAG, line);
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
        if (line.startsWith("MAGICDESK_MOUSE_SECONDARY_CLICK")) {
            final int displayId = DesktopRuntimeBridge
                    .getActiveDesktopDisplayId();
            if (displayId > 0) {
                ShellAccess.injectSecondaryClick(displayId);
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

    private static String shellQuote(final String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static void writeControl(
            final ShellStreamHandle stream,
            final String command) {
        try {
            stream.writeLine(command);
        } catch (IOException error) {
            Log.w(TAG, "Could not configure mouse bridge", error);
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

package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

final class ShizukuMouseBridge {
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
    private int mGeneration;
    private Thread mSupervisorThread;
    private ShizukuAccess.StreamHandle mStream;

    ShizukuMouseBridge(final Context context) {
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
                    "MagicDeskShizukuMouse");
            mSupervisorThread.setDaemon(true);
            mSupervisorThread.start();
        }
    }

    void restart() {
        stop();
        start();
    }

    void stop() {
        final ShizukuAccess.StreamHandle stream;
        final Thread supervisor;
        synchronized (mLock) {
            if (!mRequested && mStream == null) {
                return;
            }
            mRequested = false;
            mReady = false;
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

    private void runSupervisor(final int generation) {
        while (isActive(generation)) {
            try {
                runOnce(generation);
            } catch (IOException error) {
                if (isActive(generation)) {
                    Log.w(TAG, "Shizuku mouse bridge failed", error);
                    CompatibilityDiagnostics.record(
                            "INPUT-MOUSE-001",
                            "The Shizuku right-click bridge stopped",
                            "backend=" + RuntimeAccess.backendName(),
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
        final String inputDump = ShizukuAccess.run(DUMPSYS_INPUT);
        final List<ConsoleMouseDevice> mice =
                ConsoleInputDeviceDiscovery.findMice(inputDump);
        if (mice.isEmpty()) {
            throw new IOException("no external cursor device was found");
        }

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
        for (final ConsoleMouseDevice mouse : mice) {
            command.append(' ').append(shellQuote(mouse.path));
        }

        final ShizukuAccess.StreamHandle stream =
                ShizukuAccess.openHeartbeatStream(command.toString());
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
                }
            }
            closeQuietly(stream);
        }
    }

    private void handleLine(
            final String line,
            final ShizukuAccess.StreamHandle stream,
            final int generation) {
        if (line.startsWith("MAGICDESK_SHIZUKU_MOUSE_READY")) {
            synchronized (mLock) {
                if (isActiveLocked(generation) && mStream == stream) {
                    mReady = true;
                }
            }
            Log.i(TAG, line);
            return;
        }
        if (line.startsWith("MAGICDESK_SHIZUKU_MOUSE_ERROR")) {
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

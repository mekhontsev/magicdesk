package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.app.WallpaperManager;
import android.content.Context;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.system.Os;
import android.util.Log;

import java.io.Closeable;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public final class ShizukuCommandService extends IShizukuCommandService.Stub {
    private static final String TAG = "MagicDeskShizuku";
    private static final long HEARTBEAT_INTERVAL_MILLIS = 1_000L;
    private static final long STREAM_STOP_GRACE_MILLIS = 1_000L;
    private final Context mContext;
    private final Map<Long, StreamSession> mStreams =
            new ConcurrentHashMap<>();

    public ShizukuCommandService() {
        this(null);
    }

    public ShizukuCommandService(final Context context) {
        mContext = context;
        Log.i(TAG, "command service started uid=" + Os.getuid());
    }

    @Override
    public int uid() {
        return Os.getuid();
    }

    @Override
    public String execute(final String command) {
        if (command == null || command.isEmpty()) {
            return "-1\nempty command";
        }
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            final BoundedProcessRunner.Result result =
                    BoundedProcessRunner.run(process);
            return result.exitCode + "\n" + result.output;
        } catch (IOException error) {
            return "-1\n" + usefulMessage(error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return "-1\ncommand interrupted";
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    @Override
    public String probeCapabilities() {
        return ShizukuCapabilityProbe.run(mContext);
    }

    @Override
    public String updateHardwareKeyboardLayout(
            final String mode,
            final String currentDescriptor) {
        try {
            final HardwareKeyboardLayoutCommand.Result result =
                    HardwareKeyboardLayoutCommand.execute(
                            mode, currentDescriptor);
            if (!"catalog".equals(mode)) {
                persistHardwareKeyboardLayout(result);
            }
            return result.format();
        } catch (ReflectiveOperationException
                | IOException
                | RuntimeException error) {
            throw new IllegalStateException(
                    "cannot update hardware keyboard layout: "
                            + usefulMessage(error),
                    error);
        }
    }

    @Override
    @SuppressLint("MissingPermission")
    public ParcelFileDescriptor openSystemWallpaper() {
        if (mContext == null) {
            throw new IllegalStateException("Shizuku service context is unavailable");
        }
        // This method runs in the Shizuku UserService. Android's shell UID
        // holds READ_WALLPAPER_INTERNAL; the ordinary APK process never calls
        // WallpaperManager.getWallpaperFile directly.
        final ParcelFileDescriptor descriptor = WallpaperManager
                .getInstance(mContext)
                .getWallpaperFile(WallpaperManager.FLAG_SYSTEM);
        if (descriptor == null) {
            throw new IllegalStateException("system wallpaper is unavailable");
        }
        return descriptor;
    }

    private static void persistHardwareKeyboardLayout(
            final HardwareKeyboardLayoutCommand.Result result)
            throws IOException {
        final String command =
                "/system/bin/settings put global "
                        + HardwareKeyboardLayoutController.LAYOUT_LABEL_STATE
                        + " " + shellQuote(result.code) + "; "
                        + "/system/bin/settings put global "
                        + HardwareKeyboardLayoutController.LAYOUT_NAME_STATE
                        + " " + shellQuote(result.name) + "; "
                        + "/system/bin/settings put global "
                        + HardwareKeyboardLayoutController.LAYOUT_STATE
                        + " " + shellQuote(result.descriptor);
        Process process = null;
        try {
            process = new ProcessBuilder(
                    "/system/bin/sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            final BoundedProcessRunner.Result output =
                    BoundedProcessRunner.run(process);
            if (output.exitCode != 0) {
                throw new IOException(
                        "settings command failed "
                                + output.exitCode + ": "
                                + output.output.trim());
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException(
                    "settings command interrupted", error);
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String shellQuote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    @Override
    public ParcelFileDescriptor openStream(
            final String command, final long requestId) {
        return openStream(command, requestId, null);
    }

    @Override
    public ParcelFileDescriptor openHeartbeatStream(
            final String command,
            final long requestId,
            final IBinder ownerToken) {
        if (ownerToken == null) {
            throw new IllegalArgumentException("missing stream owner token");
        }
        return openStream(command, requestId, ownerToken);
    }

    private ParcelFileDescriptor openStream(
            final String command,
            final long requestId,
            final IBinder ownerToken) {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("empty stream command");
        }
        closeStream(requestId);

        ParcelFileDescriptor readSide = null;
        ParcelFileDescriptor writeSide = null;
        Process process = null;
        try {
            final ParcelFileDescriptor[] pipe =
                    ParcelFileDescriptor.createPipe();
            readSide = pipe[0];
            writeSide = pipe[1];
            process = new ProcessBuilder("/system/bin/sh", "-c", command)
                    .redirectErrorStream(true)
                    .start();
            final StreamSession session = new StreamSession(
                    requestId, process, writeSide, ownerToken);
            mStreams.put(Long.valueOf(requestId), session);
            try {
                session.start();
            } catch (RemoteException error) {
                mStreams.remove(Long.valueOf(requestId), session);
                session.stop();
                throw error;
            }
            Log.i(TAG, "stream opened id=" + requestId);
            return readSide;
        } catch (IOException | RemoteException error) {
            if (process != null) {
                process.destroyForcibly();
            }
            closeQuietly(writeSide);
            closeQuietly(readSide);
            throw new IllegalStateException(
                    "cannot open command stream: " + usefulMessage(error),
                    error);
        }
    }

    @Override
    public void closeStream(final long requestId) {
        final StreamSession session =
                mStreams.remove(Long.valueOf(requestId));
        if (session != null) {
            session.stop();
            Log.i(TAG, "stream closed id=" + requestId);
        }
    }

    @Override
    public void writeStream(final long requestId, final String line) {
        final StreamSession session =
                mStreams.get(Long.valueOf(requestId));
        if (session == null) {
            throw new IllegalStateException(
                    "Shizuku stream is not active: " + requestId);
        }
        try {
            session.writeLine(line);
        } catch (IOException error) {
            throw new IllegalStateException(
                    "cannot write Shizuku stream: "
                            + usefulMessage(error),
                    error);
        }
    }

    @Override
    public void destroy() {
        Log.i(TAG, "command service stopped");
        for (final StreamSession session
                : new ArrayList<>(mStreams.values())) {
            closeStream(session.requestId);
        }
        System.exit(0);
    }

    private static void closeQuietly(final Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Stream shutdown is best effort.
        }
    }

    private static String usefulMessage(final Throwable error) {
        final String message = error.getMessage();
        return message == null || message.isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private final class StreamSession implements Runnable {
        final long requestId;
        final Process process;
        final ParcelFileDescriptor writeSide;
        final Thread thread;
        final BufferedWriter commandWriter;
        final IBinder ownerToken;
        final IBinder.DeathRecipient ownerDeathRecipient;
        final Thread heartbeatThread;
        volatile boolean stopped;
        boolean ownerLinked;

        StreamSession(
                final long requestId,
                final Process process,
                final ParcelFileDescriptor writeSide,
                final IBinder ownerToken) {
            this.requestId = requestId;
            this.process = process;
            this.writeSide = writeSide;
            this.ownerToken = ownerToken;
            commandWriter = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8));
            thread = new Thread(this, "MagicDeskShizukuStream-" + requestId);
            thread.setDaemon(true);
            if (ownerToken == null) {
                ownerDeathRecipient = null;
                heartbeatThread = null;
            } else {
                ownerDeathRecipient = () -> {
                    Log.i(TAG, "stream owner died id=" + requestId);
                    closeStream(requestId);
                };
                heartbeatThread = new Thread(
                        this::runHeartbeat,
                        "MagicDeskShizukuHeartbeat-" + requestId);
                heartbeatThread.setDaemon(true);
            }
        }

        synchronized void start() throws RemoteException {
            if (ownerToken != null) {
                ownerToken.linkToDeath(ownerDeathRecipient, 0);
                ownerLinked = true;
            }
            thread.start();
            if (heartbeatThread != null) {
                heartbeatThread.start();
            }
        }

        synchronized void stop() {
            if (stopped) {
                return;
            }
            stopped = true;
            if (ownerLinked) {
                ownerToken.unlinkToDeath(ownerDeathRecipient, 0);
                ownerLinked = false;
            }
            if (heartbeatThread != null) {
                heartbeatThread.interrupt();
            }
            closeQuietly(commandWriter);
            if (ownerToken != null) {
                awaitProcessExit();
            }
            closeQuietly(writeSide);
            if (process.isAlive()) {
                process.destroy();
            }
            thread.interrupt();
        }

        private void awaitProcessExit() {
            try {
                process.waitFor(
                        STREAM_STOP_GRACE_MILLIS,
                        TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }

        synchronized void writeLine(final String line) throws IOException {
            if (stopped) {
                throw new IOException("stream is stopped");
            }
            commandWriter.write(line == null ? "" : line);
            commandWriter.newLine();
            commandWriter.flush();
        }

        private void runHeartbeat() {
            while (!stopped) {
                try {
                    writeLine("ping");
                    Thread.sleep(HEARTBEAT_INTERVAL_MILLIS);
                } catch (IOException error) {
                    if (!stopped) {
                        Log.w(TAG,
                                "stream heartbeat failed id=" + requestId,
                                error);
                        closeStream(requestId);
                    }
                    return;
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        @Override
        public void run() {
            try (InputStream input = process.getInputStream();
                    OutputStream output =
                            new ParcelFileDescriptor.AutoCloseOutputStream(
                                    writeSide)) {
                final byte[] buffer = new byte[8192];
                int count;
                while (!stopped && (count = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, count);
                }
            } catch (IOException error) {
                if (!stopped) {
                    Log.w(TAG,
                            "stream failed id=" + requestId,
                            error);
                }
            } finally {
                mStreams.remove(Long.valueOf(requestId), this);
                stop();
            }
        }
    }
}

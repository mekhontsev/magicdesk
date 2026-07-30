package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.ParcelFileDescriptor;
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

public final class ShizukuCommandService extends IShizukuCommandService.Stub {
    private static final String TAG = "MagicDeskShizuku";
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
    public ParcelFileDescriptor openStream(
            final String command, final long requestId) {
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
                    requestId, process, writeSide);
            mStreams.put(Long.valueOf(requestId), session);
            session.start();
            Log.i(TAG, "stream opened id=" + requestId);
            return readSide;
        } catch (IOException error) {
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
        volatile boolean stopped;

        StreamSession(
                final long requestId,
                final Process process,
                final ParcelFileDescriptor writeSide) {
            this.requestId = requestId;
            this.process = process;
            this.writeSide = writeSide;
            commandWriter = new BufferedWriter(new OutputStreamWriter(
                    process.getOutputStream(), StandardCharsets.UTF_8));
            thread = new Thread(this, "MagicDeskShizukuStream-" + requestId);
            thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        void stop() {
            stopped = true;
            closeQuietly(commandWriter);
            closeQuietly(writeSide);
            process.destroy();
            thread.interrupt();
        }

        synchronized void writeLine(final String line) throws IOException {
            if (stopped) {
                throw new IOException("stream is stopped");
            }
            commandWriter.write(line == null ? "" : line);
            commandWriter.newLine();
            commandWriter.flush();
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
                process.destroy();
                mStreams.remove(Long.valueOf(requestId), this);
            }
        }
    }
}

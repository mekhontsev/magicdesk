package io.github.mekhontsev.magicdesk;

import android.content.Context;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Authenticated PTY byte stream hosted by the installed Termux application. */
final class TermuxPtyTransport implements TerminalTransport {
    private static final long CONNECT_TIMEOUT_MILLIS = 15_000L;
    private static final long CLIENT_HANDSHAKE_MILLIS = 1_000L;
    private static final long CWD_TIMEOUT_MILLIS = 1_500L;
    private static final long PROCESS_TIMEOUT_MILLIS = 1_500L;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static final AtomicInteger OPENED = new AtomicInteger();
    private static final AtomicInteger FAILURES = new AtomicInteger();
    private static volatile String sLastError = "";

    private final Object mOutputLock = new Object();
    private final Object mDirectoryLock = new Object();
    private final Object mProcessLock = new Object();
    private final Object mProcessQueryLock = new Object();
    private final Socket mSocket;
    private final DataInputStream mInput;
    private final DataOutputStream mOutput;
    private final InputStream mTerminalInput = new FramedInput();
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final long mProcessId;

    private String mWorkingDirectory;
    private long mDirectoryGeneration;
    private TerminalProcessInfo mForegroundProcess =
            TerminalProcessInfo.unknown();
    private long mProcessGeneration;

    private TermuxPtyTransport(
            final Socket socket,
            final DataInputStream input,
            final long processId,
            final String workingDirectory) throws IOException {
        mSocket = socket;
        mInput = input;
        mOutput = new DataOutputStream(socket.getOutputStream());
        mProcessId = processId;
        mWorkingDirectory = workingDirectory;
        OPENED.incrementAndGet();
        ACTIVE.incrementAndGet();
    }

    static TermuxPtyTransport open(
            final Context context,
            final String workingDirectory,
            final int rows,
            final int columns,
            final String startupCommand) throws IOException {
        try {
            return openInternal(
                    context,
                    workingDirectory,
                    rows,
                    columns,
                    startupCommand);
        } catch (IOException | RuntimeException error) {
            FAILURES.incrementAndGet();
            sLastError = ShellAccess.usefulMessage(error);
            if (TermuxIntegration.isAutoLaunchBlocked(error)) {
                final String message = context.getString(
                        R.string.console_termux_autolaunch_blocked);
                CompatibilityDiagnostics.record(
                        "TERMUX-PTY-001",
                        "Termux PTY launch was blocked by firmware",
                        sLastError,
                        error);
                throw new IOException(message, error);
            }
            if (error instanceof IOException) {
                throw (IOException) error;
            }
            throw new IOException("Termux PTY launch failed: "
                    + sLastError, error);
        }
    }

    private static TermuxPtyTransport openInternal(
            final Context context,
            final String workingDirectory,
            final int rows,
            final int columns,
            final String startupCommand) throws IOException {
        if (!TermuxIntegration.isAvailable(context)) {
            throw new IOException("Termux command integration is unavailable");
        }
        final String token = newToken();
        final long deadline = android.os.SystemClock.uptimeMillis()
                + CONNECT_TIMEOUT_MILLIS;
        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(false);
            server.bind(new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"), 0), 4);
            TermuxPtyBridgeLauncher.launch(
                    context,
                    server.getLocalPort(),
                    token,
                    rows,
                    columns,
                    workingDirectory,
                    startupCommand);
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                final int remaining = (int) Math.max(
                        1L,
                        deadline - android.os.SystemClock.uptimeMillis());
                server.setSoTimeout(remaining);
                final Socket socket;
                try {
                    socket = server.accept();
                } catch (SocketTimeoutException error) {
                    break;
                }
                final TermuxPtyTransport accepted = acceptClient(
                        socket, token, workingDirectory, remaining);
                if (accepted != null) {
                    return accepted;
                }
            }
        }
        throw new IOException("Termux PTY connection timed out");
    }

    static String diagnostics(final Context context) {
        return "installed=" + TermuxIntegration.isInstalled(context)
                + ", permission=" + TermuxIntegration.isAvailable(context)
                + ", active=" + ACTIVE.get()
                + ", opened=" + OPENED.get()
                + ", failures=" + FAILURES.get()
                + (sLastError.isEmpty() ? "" : ", lastError=" + sLastError);
    }

    private static TermuxPtyTransport acceptClient(
            final Socket socket,
            final String token,
            final String workingDirectory,
            final int remainingMillis) {
        try {
            if (!socket.getInetAddress().isLoopbackAddress()) {
                socket.close();
                return null;
            }
            socket.setTcpNoDelay(true);
            socket.setSoTimeout((int) Math.min(
                    CLIENT_HANDSHAKE_MILLIS,
                    Math.max(1, remainingMillis)));
            final DataInputStream input = new DataInputStream(
                    socket.getInputStream());
            final TermuxPtyProtocol.Hello hello =
                    TermuxPtyProtocol.parseHello(
                            TermuxPtyProtocol.readFrame(input), token);
            socket.setSoTimeout(0);
            return new TermuxPtyTransport(
                    socket, input, hello.processId, workingDirectory);
        } catch (IOException | RuntimeException error) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // A rejected local connection owns no further resources.
            }
            return null;
        }
    }

    @Override
    public InputStream inputStream() {
        return mTerminalInput;
    }

    @Override
    public void write(final byte[] data) throws IOException {
        ensureOpen();
        synchronized (mOutputLock) {
            PtyControlProtocol.writeData(mOutput, data);
            mOutput.flush();
        }
    }

    @Override
    public void resize(final int rows, final int columns) throws IOException {
        ensureOpen();
        synchronized (mOutputLock) {
            PtyControlProtocol.writeResize(mOutput, rows, columns);
            mOutput.flush();
        }
    }

    @Override
    public String workingDirectory() throws IOException {
        ensureOpen();
        final long previousGeneration;
        synchronized (mDirectoryLock) {
            previousGeneration = mDirectoryGeneration;
        }
        synchronized (mOutputLock) {
            PtyControlProtocol.writeWorkingDirectoryRequest(mOutput);
            mOutput.flush();
        }
        final long deadline = android.os.SystemClock.uptimeMillis()
                + CWD_TIMEOUT_MILLIS;
        synchronized (mDirectoryLock) {
            while (!mClosed.get()
                    && mDirectoryGeneration == previousGeneration) {
                final long remaining = deadline
                        - android.os.SystemClock.uptimeMillis();
                if (remaining <= 0L) {
                    throw new IOException("Termux PTY directory timed out");
                }
                try {
                    mDirectoryLock.wait(remaining);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException(
                            "Termux PTY directory lookup interrupted", error);
                }
            }
            if (mClosed.get()) {
                throw new IOException("Termux PTY is closed");
            }
            return mWorkingDirectory;
        }
    }

    @Override
    public long processId() throws IOException {
        ensureOpen();
        return mProcessId;
    }

    @Override
    public boolean supportsForegroundProcess() {
        return true;
    }

    @Override
    public TerminalProcessInfo foregroundProcess() throws IOException {
        synchronized (mProcessQueryLock) {
            ensureOpen();
            final long previousGeneration;
            synchronized (mProcessLock) {
                previousGeneration = mProcessGeneration;
            }
            synchronized (mOutputLock) {
                PtyControlProtocol.writeForegroundProcessRequest(mOutput);
                mOutput.flush();
            }
            final long deadline = android.os.SystemClock.uptimeMillis()
                    + PROCESS_TIMEOUT_MILLIS;
            synchronized (mProcessLock) {
                while (!mClosed.get()
                        && mProcessGeneration == previousGeneration) {
                    final long remaining = deadline
                            - android.os.SystemClock.uptimeMillis();
                    if (remaining <= 0L) {
                        throw new IOException(
                                "Termux foreground process lookup timed out");
                    }
                    try {
                        mProcessLock.wait(remaining);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IOException(
                                "Termux foreground process lookup interrupted",
                                error);
                    }
                }
                if (mClosed.get()) {
                    throw new IOException("Termux PTY is closed");
                }
                return mForegroundProcess;
            }
        }
    }

    @Override
    public boolean consumesStartupCommand() {
        return true;
    }

    @Override
    public void close() {
        if (!mClosed.compareAndSet(false, true)) {
            return;
        }
        try {
            mSocket.close();
        } catch (IOException ignored) {
            // Closing the socket is the PTY bridge ownership signal.
        }
        synchronized (mDirectoryLock) {
            mDirectoryLock.notifyAll();
        }
        synchronized (mProcessLock) {
            mProcessLock.notifyAll();
        }
        ACTIVE.decrementAndGet();
    }

    private void ensureOpen() throws IOException {
        if (mClosed.get()) {
            throw new IOException("Termux PTY is closed");
        }
    }

    private void updateWorkingDirectory(final byte[] payload)
            throws IOException {
        final String directory = new String(
                payload, StandardCharsets.UTF_8);
        if (!directory.startsWith("/")
                || directory.indexOf('\0') >= 0
                || directory.indexOf('\n') >= 0
                || directory.indexOf('\r') >= 0) {
            throw new IOException("invalid Termux PTY directory");
        }
        synchronized (mDirectoryLock) {
            mWorkingDirectory = directory;
            mDirectoryGeneration++;
            mDirectoryLock.notifyAll();
        }
    }

    private void updateForegroundProcess(final TermuxPtyProtocol.Frame frame)
            throws IOException {
        final TerminalProcessInfo process =
                TermuxPtyProtocol.parseForegroundProcess(frame);
        synchronized (mProcessLock) {
            mForegroundProcess = process;
            mProcessGeneration++;
            mProcessLock.notifyAll();
        }
    }

    private static String newToken() {
        final byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        final StringBuilder token = new StringBuilder(bytes.length * 2);
        for (final byte value : bytes) {
            token.append(Character.forDigit((value >> 4) & 0x0F, 16));
            token.append(Character.forDigit(value & 0x0F, 16));
        }
        return token.toString();
    }

    private final class FramedInput extends InputStream {
        private byte[] mPayload = new byte[0];
        private int mOffset;

        @Override
        public int read() throws IOException {
            final byte[] value = new byte[1];
            final int count = read(value, 0, 1);
            return count < 0 ? -1 : value[0] & 0xFF;
        }

        @Override
        public int read(
                final byte[] target,
                final int offset,
                final int length) throws IOException {
            if (target == null) {
                throw new NullPointerException("target");
            }
            if (offset < 0 || length < 0
                    || length > target.length - offset) {
                throw new IndexOutOfBoundsException();
            }
            if (length == 0) {
                return 0;
            }
            while (mOffset >= mPayload.length) {
                final TermuxPtyProtocol.Frame frame =
                        TermuxPtyProtocol.readFrame(mInput);
                if (frame == null) {
                    return -1;
                }
                if (frame.type == TermuxPtyProtocol.FRAME_CWD) {
                    updateWorkingDirectory(frame.payload);
                    continue;
                }
                if (frame.type
                        == TermuxPtyProtocol.FRAME_FOREGROUND_PROCESS) {
                    updateForegroundProcess(frame);
                    continue;
                }
                if (frame.type != TermuxPtyProtocol.FRAME_OUTPUT) {
                    throw new IOException("unexpected Termux PTY frame");
                }
                if (frame.payload.length == 0) {
                    continue;
                }
                mPayload = frame.payload;
                mOffset = 0;
            }
            final int count = Math.min(length, mPayload.length - mOffset);
            System.arraycopy(mPayload, mOffset, target, offset, count);
            mOffset += count;
            return count;
        }
    }
}

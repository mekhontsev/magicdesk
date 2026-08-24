package io.github.mekhontsev.magicdesk;

import android.os.Handler;
import android.os.Looper;

import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** One interactive PTY and terminal state for one Console window. */
final class ConsoleTerminalSession {
    interface Listener {
        void onScreenChanged();

        void onReady();

        void onFinished();

        void onError(IOException error);

        void onTitleChanged(String title);

        void onCopyRequested(String text);

        void onPasteRequested();

        void onBell();
    }

    interface DirectoryListener {
        void onDirectory(String directory, IOException error);
    }

    private static final int DEFAULT_TRANSCRIPT_ROWS = 4_000;
    private static final int MAX_PENDING_INPUT_BYTES = 64 * 1024;

    private final Object mLock = new Object();
    private final Object mOutputLock = new Object();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mWriter =
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(
                        runnable, "MagicDeskConsolePtyWriter");
                thread.setDaemon(true);
                return thread;
            });
    private final ByteArrayOutputStream mPendingInput =
            new ByteArrayOutputStream();
    private final ByteArrayOutputStream mPendingOutput =
            new ByteArrayOutputStream();
    private final Listener mListener;
    private final TerminalEmulator mEmulator;
    private final TerminalTransport.Factory mTransportFactory;
    private final DesktopExecBackend mBackend;
    private final String mStartupCommand;

    private String mWorkingDirectory;
    private String mTitle = "";
    private TerminalTransport mTransport;
    private long mProcessId = -1L;
    private int mColumns;
    private int mRows;
    private boolean mStarted;
    private boolean mClosed;
    private boolean mReady;
    private boolean mReceivedOutput;
    private boolean mStartupCommandSent;
    private boolean mOutputPosted;

    ConsoleTerminalSession(
            final String initialDirectory,
            final int columns,
            final int rows,
            final int cellWidth,
            final int cellHeight,
            final DesktopExecBackend backend,
            final String startupCommand,
            final TerminalTransport.Factory transportFactory,
            final Listener listener) {
        if (initialDirectory == null || !initialDirectory.startsWith("/")) {
            throw new IllegalArgumentException(
                    "terminal working directory must be absolute");
        }
        mWorkingDirectory = initialDirectory;
        mColumns = columns;
        mRows = rows;
        mBackend = backend == null
                ? DesktopExecBackend.SHELL : backend;
        mStartupCommand = startupCommand == null ? "" : startupCommand;
        if (transportFactory == null) {
            throw new IllegalArgumentException("missing terminal transport");
        }
        mTransportFactory = transportFactory;
        mListener = listener;
        mEmulator = new TerminalEmulator(
                new SessionOutput(),
                columns,
                rows,
                cellWidth,
                cellHeight,
                Integer.valueOf(DEFAULT_TRANSCRIPT_ROWS),
                null);
    }

    TerminalEmulator emulator() {
        return mEmulator;
    }

    DesktopExecBackend backend() {
        return mBackend;
    }

    String workingDirectory() {
        synchronized (mLock) {
            return mWorkingDirectory;
        }
    }

    boolean isReady() {
        synchronized (mLock) {
            return mReady && !mClosed;
        }
    }

    long processId() {
        synchronized (mLock) {
            return mProcessId;
        }
    }

    int columns() {
        synchronized (mLock) {
            return mColumns;
        }
    }

    int rows() {
        synchronized (mLock) {
            return mRows;
        }
    }

    String title() {
        synchronized (mLock) {
            return mTitle;
        }
    }

    void start() {
        final int rows;
        final int columns;
        final String directory;
        synchronized (mLock) {
            if (mStarted || mClosed) {
                return;
            }
            mStarted = true;
            rows = mEmulator.mRows;
            columns = mEmulator.mColumns;
            directory = mWorkingDirectory;
        }
        executeWriter(() -> openPty(directory, rows, columns));
    }

    void write(final String text) {
        if (text != null && !text.isEmpty()) {
            write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    void write(final byte[] data) {
        if (data == null || data.length == 0) {
            return;
        }
        final byte[] copy = Arrays.copyOf(data, data.length);
        synchronized (mLock) {
            if (mClosed) {
                return;
            }
            if (!mReady) {
                if (mPendingInput.size() + copy.length
                        <= MAX_PENDING_INPUT_BYTES) {
                    mPendingInput.write(copy, 0, copy.length);
                }
                return;
            }
        }
        executeWriter(() -> writeNow(copy));
    }

    void resize(
            final int columns,
            final int rows,
            final int cellWidth,
            final int cellHeight) {
        if (columns < 2 || rows < 2) {
            return;
        }
        synchronized (mLock) {
            mColumns = columns;
            mRows = rows;
        }
        mEmulator.resize(columns, rows, cellWidth, cellHeight);
        mListener.onScreenChanged();
        synchronized (mLock) {
            if (!mReady || mClosed) {
                return;
            }
        }
        executeWriter(() -> resizeNow(rows, columns));
    }

    void clear() {
        mEmulator.getScreen().clearTranscript();
        write(new byte[]{0x0C});
        mListener.onScreenChanged();
    }

    String transcript() {
        return mEmulator.getScreen().getTranscriptTextWithoutJoinedLines();
    }

    void paste(final String text) {
        if (text != null && !text.isEmpty()) {
            mEmulator.paste(text);
        }
    }

    void requestWorkingDirectory(final DirectoryListener listener) {
        if (listener == null) {
            return;
        }
        executeWriter(() -> {
            String resolved;
            IOException error = null;
            try {
                resolved = resolveWorkingDirectory();
            } catch (IOException lookupError) {
                synchronized (mLock) {
                    resolved = mWorkingDirectory;
                }
                error = lookupError;
            }
            final IOException failure = error;
            final String result = resolved;
            mMainHandler.post(() -> listener.onDirectory(result, failure));
        });
    }

    String resolveWorkingDirectory() throws IOException {
        final TerminalTransport transport;
        synchronized (mLock) {
            transport = mTransport;
            if (transport == null) {
                return mWorkingDirectory;
            }
        }
        final String resolved = transport.workingDirectory();
        synchronized (mLock) {
            if (!mClosed && mTransport == transport) {
                mWorkingDirectory = resolved;
            }
            return mWorkingDirectory;
        }
    }

    private void executeWriter(final Runnable operation) {
        synchronized (mLock) {
            if (mClosed) {
                return;
            }
        }
        try {
            mWriter.execute(operation);
        } catch (RejectedExecutionException ignored) {
            // Activity teardown may race an already posted UI callback.
        }
    }

    void close() {
        final TerminalTransport transport;
        synchronized (mLock) {
            if (mClosed) {
                return;
            }
            mClosed = true;
            mReady = false;
            mProcessId = -1L;
            transport = mTransport;
            mTransport = null;
            mPendingInput.reset();
        }
        if (transport != null) {
            transport.close();
        }
        mWriter.shutdownNow();
    }

    private void openPty(
            final String directory, final int rows, final int columns) {
        final TerminalTransport transport;
        try {
            transport = mTransportFactory.open(
                    directory, rows, columns, mStartupCommand);
        } catch (IOException error) {
            postError(error);
            return;
        }
        final byte[] pending;
        long processId = -1L;
        try {
            processId = transport.processId();
        } catch (IOException ignored) {
            // Process metadata is useful to automation but not required for
            // an otherwise healthy interactive terminal.
        }
        synchronized (mLock) {
            if (mClosed) {
                transport.close();
                return;
            }
            mTransport = transport;
            mProcessId = processId;
            mReady = true;
            mStartupCommandSent = mStartupCommand.isEmpty()
                    || transport.consumesStartupCommand();
            pending = mPendingInput.toByteArray();
            mPendingInput.reset();
        }
        if (pending.length > 0) {
            writeNow(pending);
        }
        mMainHandler.post(mListener::onReady);
        final Thread reader = new Thread(
                () -> readTransport(transport),
                "MagicDeskConsolePtyReader");
        reader.setDaemon(true);
        reader.start();
    }

    private void readTransport(final TerminalTransport transport) {
        IOException failure = null;
        try {
            final InputStream input = transport.inputStream();
            final byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) {
                    queueOutput(buffer, count);
                }
            }
        } catch (IOException error) {
            synchronized (mLock) {
                if (!mClosed) {
                    failure = error;
                }
            }
        } finally {
            synchronized (mLock) {
                if (mTransport == transport) {
                    mTransport = null;
                    mReady = false;
                    mProcessId = -1L;
                }
            }
            transport.close();
        }
        if (failure != null) {
            postError(failure);
        } else {
            mMainHandler.post(mListener::onFinished);
        }
    }

    private void queueOutput(final byte[] bytes, final int count) {
        synchronized (mOutputLock) {
            mPendingOutput.write(bytes, 0, count);
            if (mOutputPosted) {
                return;
            }
            mOutputPosted = true;
        }
        mMainHandler.post(this::drainOutput);
    }

    private void drainOutput() {
        final byte[] output;
        synchronized (mOutputLock) {
            output = mPendingOutput.toByteArray();
            mPendingOutput.reset();
            mOutputPosted = false;
        }
        synchronized (mLock) {
            if (mClosed) {
                return;
            }
            mReceivedOutput = true;
        }
        if (output.length > 0) {
            mEmulator.append(output, output.length);
            mListener.onScreenChanged();
            sendStartupCommandIfReady();
        }
    }

    private void sendStartupCommandIfReady() {
        final byte[] command;
        synchronized (mLock) {
            if (mClosed || !mReady || !mReceivedOutput
                    || mStartupCommandSent) {
                return;
            }
            mStartupCommandSent = true;
            command = (mStartupCommand + "\r").getBytes(
                    StandardCharsets.UTF_8);
        }
        executeWriter(() -> writeNow(command));
    }

    private void writeNow(final byte[] data) {
        final TerminalTransport transport;
        synchronized (mLock) {
            transport = mClosed ? null : mTransport;
        }
        if (transport == null) {
            return;
        }
        try {
            transport.write(data);
        } catch (IOException error) {
            postError(error);
        }
    }

    private void resizeNow(final int rows, final int columns) {
        final TerminalTransport transport;
        synchronized (mLock) {
            transport = mClosed ? null : mTransport;
        }
        if (transport == null) {
            return;
        }
        try {
            transport.resize(rows, columns);
        } catch (IOException error) {
            postError(error);
        }
    }

    private void postError(final IOException error) {
        mMainHandler.post(() -> {
            synchronized (mLock) {
                if (mClosed) {
                    return;
                }
            }
            mListener.onError(error);
        });
    }

    private final class SessionOutput extends TerminalOutput {
        @Override
        public void write(
                final byte[] data, final int offset, final int count) {
            if (data == null || count <= 0) {
                return;
            }
            ConsoleTerminalSession.this.write(
                    Arrays.copyOfRange(data, offset, offset + count));
        }

        @Override
        public void titleChanged(
                final String oldTitle, final String newTitle) {
            synchronized (mLock) {
                mTitle = newTitle == null ? "" : newTitle;
            }
            mListener.onTitleChanged(newTitle);
        }

        @Override
        public void onCopyTextToClipboard(final String text) {
            mListener.onCopyRequested(text);
        }

        @Override
        public void onPasteTextFromClipboard() {
            mListener.onPasteRequested();
        }

        @Override
        public void onBell() {
            mListener.onBell();
        }

        @Override
        public void onColorsChanged() {
            mListener.onScreenChanged();
        }
    }
}

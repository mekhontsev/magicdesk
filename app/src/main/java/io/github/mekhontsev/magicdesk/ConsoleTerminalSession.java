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

    private String mWorkingDirectory;
    private ShellPtyHandle mPty;
    private boolean mStarted;
    private boolean mClosed;
    private boolean mReady;
    private boolean mOutputPosted;

    ConsoleTerminalSession(
            final String initialDirectory,
            final int columns,
            final int rows,
            final int cellWidth,
            final int cellHeight,
            final Listener listener) {
        if (initialDirectory == null || !initialDirectory.startsWith("/")) {
            throw new IllegalArgumentException(
                    "terminal working directory must be absolute");
        }
        mWorkingDirectory = initialDirectory;
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
            final String directory;
            final IOException failure;
            final ShellPtyHandle pty;
            synchronized (mLock) {
                pty = mPty;
                directory = mWorkingDirectory;
            }
            String resolved = directory;
            IOException error = null;
            if (pty != null) {
                try {
                    resolved = pty.workingDirectory();
                    synchronized (mLock) {
                        mWorkingDirectory = resolved;
                    }
                } catch (IOException lookupError) {
                    error = lookupError;
                }
            }
            failure = error;
            final String result = resolved;
            mMainHandler.post(() -> listener.onDirectory(result, failure));
        });
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
        final ShellPtyHandle pty;
        synchronized (mLock) {
            if (mClosed) {
                return;
            }
            mClosed = true;
            mReady = false;
            pty = mPty;
            mPty = null;
            mPendingInput.reset();
        }
        if (pty != null) {
            pty.close();
        }
        mWriter.shutdownNow();
    }

    private void openPty(
            final String directory, final int rows, final int columns) {
        final ShellPtyHandle pty;
        try {
            pty = ShellAccess.openPty(directory, rows, columns);
        } catch (IOException error) {
            postError(error);
            return;
        }
        final byte[] pending;
        synchronized (mLock) {
            if (mClosed) {
                pty.close();
                return;
            }
            mPty = pty;
            mReady = true;
            pending = mPendingInput.toByteArray();
            mPendingInput.reset();
        }
        if (pending.length > 0) {
            writeNow(pending);
        }
        mMainHandler.post(mListener::onReady);
        final Thread reader = new Thread(
                () -> readPty(pty), "MagicDeskConsolePtyReader");
        reader.setDaemon(true);
        reader.start();
    }

    private void readPty(final ShellPtyHandle pty) {
        IOException failure = null;
        try {
            final InputStream input = pty.inputStream();
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
                if (mPty == pty) {
                    mPty = null;
                    mReady = false;
                }
            }
            pty.close();
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
        }
        if (output.length > 0) {
            mEmulator.append(output, output.length);
            mListener.onScreenChanged();
        }
    }

    private void writeNow(final byte[] data) {
        final ShellPtyHandle pty;
        synchronized (mLock) {
            pty = mClosed ? null : mPty;
        }
        if (pty == null) {
            return;
        }
        try {
            pty.write(data);
        } catch (IOException error) {
            postError(error);
        }
    }

    private void resizeNow(final int rows, final int columns) {
        final ShellPtyHandle pty;
        synchronized (mLock) {
            pty = mClosed ? null : mPty;
        }
        if (pty == null) {
            return;
        }
        try {
            pty.resize(rows, columns);
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

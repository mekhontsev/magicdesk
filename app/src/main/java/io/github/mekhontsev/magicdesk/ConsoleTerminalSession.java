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

/** One interactive PTY and emulator, independent of display and Activity lifetime. */
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

        void onNotification(String message);

        void onMetadataChanged();
    }

    interface DirectoryListener {
        void onDirectory(String directory, IOException error);
    }

    interface ProcessListener {
        void onProcess(
                TerminalProcessInfo process,
                boolean changed,
                IOException error);
    }

    private static final int DEFAULT_TRANSCRIPT_ROWS = 4_000;
    private static final int MAX_PENDING_INPUT_BYTES = 64 * 1024;
    private static final int MAX_PENDING_OUTPUT_BYTES = 256 * 1024;
    private static final long PROCESS_REFRESH_DELAY_MILLIS = 250L;
    private static final long MIN_PROCESS_REFRESH_INTERVAL_MILLIS = 1_000L;

    private final Object mLock = new Object();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mWriter =
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(
                        runnable, "MagicDeskConsolePtyWriter");
                thread.setDaemon(true);
                return thread;
            });
    private final TerminalRequestScope mRequests = new TerminalRequestScope(mWriter);
    private final ByteArrayOutputStream mPendingInput =
            new ByteArrayOutputStream();
    private final TerminalOutputBuffer mPendingOutput =
            new TerminalOutputBuffer(MAX_PENDING_OUTPUT_BYTES);
    private final Runnable mDrainOutput = this::drainOutput;
    private final Listener mListener;
    private final ConsoleTerminalInput mInput =
            new ConsoleTerminalInput(android.view.KeyCharacterMap::getDeadChar);
    private final TerminalEmulator mEmulator;
    private final TerminalTransport.Factory mTransportFactory;
    private final DesktopExecBackend mBackend;
    private final String mStartupCommand;
    private String mWorkingDirectory;
    private String mTitle = "";
    private String mNotification = "";
    private long mNotificationSequence;
    private int mProgressState;
    private int mProgressPercent = -1;
    private boolean mTitleChanged;
    private boolean mMetadataChanged;
    private boolean mNotificationChanged;

    record Metadata(String notification, long notificationSequence, int progressState,
            int progressPercent, String shellState) { }

    // Emulator metadata is read on the same main thread that consumes PTY output.
    Metadata metadata() {
        return new Metadata(mNotification, mNotificationSequence, mProgressState,
                mProgressPercent, mEmulator.getCommandHistory().state());
    }
    private TerminalProcessInfo mForegroundProcess =
            TerminalProcessInfo.unknown();
    private TerminalTransport mTransport;
    private long mProcessId = -1L;
    private int mColumns;
    private int mRows;
    private boolean mStarted;
    private boolean mClosed;
    private boolean mReady;
    private boolean mReceivedOutput;
    private boolean mStartupCommandSent;
    private boolean mProcessRefreshPosted;
    private boolean mProcessRefreshInProgress;
    private long mLastProcessRefreshMillis;
    private final Runnable mScheduledProcessRefresh = () -> {
        synchronized (mLock) {
            mProcessRefreshPosted = false;
            if (mClosed || mProcessRefreshInProgress) {
                return;
            }
            mProcessRefreshInProgress = true;
        }
        requestForegroundProcess((process, changed, error) -> {
            synchronized (mLock) {
                mProcessRefreshInProgress = false;
                mLastProcessRefreshMillis =
                        android.os.SystemClock.uptimeMillis();
            }
        });
    };

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
        mEmulator.setImageFactory(com.termux.terminal.AndroidTerminalImages.FACTORY);
        mEmulator.getGraphics().setByteBudget(Runtime.getRuntime().maxMemory() / 4);
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

    TerminalProcessInfo foregroundProcess() {
        synchronized (mLock) {
            return mForegroundProcess;
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

    boolean sendKey(final int keyCode, final int metaState) {
        final long now = android.os.SystemClock.uptimeMillis();
        final String sequence = mInput.key(new android.view.KeyEvent(now, now,
                android.view.KeyEvent.ACTION_DOWN, keyCode, 0, metaState), mEmulator);
        if (sequence == null) { return false; }
        write(sequence);
        return true;
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
        scheduleForegroundProcessRefresh();
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
        mEmulator.getCommandHistory().clear();
        mEmulator.getScreen().clearTranscript();
        write(new byte[]{0x0C});
        mListener.onScreenChanged();
    }

    String transcript() {
        return mEmulator.getScreen().getTranscriptTextWithoutJoinedLines();
    }

    void appendLocalMessage(final String message) {
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        final byte[] bytes = ("\r\nMagicDesk: " + message.trim() + "\r\n")
                .getBytes(StandardCharsets.UTF_8);
        mEmulator.append(bytes, bytes.length);
        mListener.onScreenChanged();
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
        mRequests.submit(this::resolveWorkingDirectory).whenComplete((directory, failure) ->
                mMainHandler.post(() -> listener.onDirectory(
                        failure == null ? directory : workingDirectory(),
                        requestError(failure))));
    }

    void requestForegroundProcess(final ProcessListener listener) {
        final TerminalTransport transport;
        synchronized (mLock) {
            transport = mClosed ? null : mTransport;
        }
        if (transport == null || !transport.supportsForegroundProcess()) {
            if (listener != null) {
                final TerminalProcessInfo current = foregroundProcess();
                mMainHandler.post(() ->
                        listener.onProcess(current, false, null));
            }
            return;
        }
        mRequests.submit(() -> readForegroundProcess(transport))
                .whenComplete((result, failure) -> {
                    if (listener != null) {
                        mMainHandler.post(() -> listener.onProcess(
                                failure == null ? result.process : foregroundProcess(),
                                failure == null && result.changed,
                                requestError(failure)));
                    }
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

    TerminalProcessInfo resolveForegroundProcess() throws IOException {
        final TerminalTransport transport;
        synchronized (mLock) {
            transport = mTransport;
            if (transport == null || !transport.supportsForegroundProcess()) {
                return mForegroundProcess;
            }
        }
        return readForegroundProcess(transport).process;
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
            mForegroundProcess = TerminalProcessInfo.unknown();
            transport = mTransport;
            mTransport = null;
            mPendingInput.reset();
            mProcessRefreshPosted = false;
            mProcessRefreshInProgress = false;
        }
        mMainHandler.removeCallbacks(mScheduledProcessRefresh);
        mPendingOutput.close();
        mMainHandler.removeCallbacks(mDrainOutput);
        mRequests.close();
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
        final int currentRows;
        final int currentColumns;
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
            currentRows = mRows;
            currentColumns = mColumns;
            pending = mPendingInput.toByteArray();
            mPendingInput.reset();
        }
        // Layout may have changed while the transport was opening. Publish
        // its latest dimensions before input queued during startup is sent.
        if (currentRows != rows || currentColumns != columns) {
            resizeNow(currentRows, currentColumns);
        }
        if (pending.length > 0) {
            writeNow(pending);
        }
        mMainHandler.post(mListener::onReady);
        scheduleForegroundProcessRefresh();
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
                    mForegroundProcess = TerminalProcessInfo.unknown();
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

    private void queueOutput(final byte[] bytes, final int count) throws IOException {
        try {
            if (mPendingOutput.append(bytes, count)) {
                mMainHandler.post(mDrainOutput);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("terminal output delivery interrupted", error);
        }
    }

    private void drainOutput() {
        final byte[] output = mPendingOutput.drain();
        synchronized (mLock) {
            if (mClosed) {
                return;
            }
            mReceivedOutput = true;
        }
        if (output.length > 0) {
            mEmulator.append(output, output.length);
            // Apply only the final metadata in this output batch to Android UI.
            if (mTitleChanged) { mTitleChanged = false; mListener.onTitleChanged(mTitle); }
            if (mMetadataChanged) { mMetadataChanged = false; mListener.onMetadataChanged(); }
            if (mNotificationChanged) { mNotificationChanged = false; mListener.onNotification(mNotification); }
            mListener.onScreenChanged();
            sendStartupCommandIfReady();
            scheduleForegroundProcessRefresh();
        }
    }

    private void scheduleForegroundProcessRefresh() {
        synchronized (mLock) {
            if (mClosed || mTransport == null
                    || !mTransport.supportsForegroundProcess()
                    || mProcessRefreshPosted) {
                return;
            }
            mProcessRefreshPosted = true;
            final long now = android.os.SystemClock.uptimeMillis();
            final long delay = Math.max(
                    PROCESS_REFRESH_DELAY_MILLIS,
                    mLastProcessRefreshMillis
                            + MIN_PROCESS_REFRESH_INTERVAL_MILLIS - now);
            mMainHandler.postDelayed(mScheduledProcessRefresh, delay);
        }
    }

    private ProcessRefresh readForegroundProcess(final TerminalTransport transport)
            throws IOException {
        final TerminalProcessInfo process = transport.foregroundProcess();
        synchronized (mLock) {
            boolean changed = false;
            if (!mClosed && mTransport == transport
                    && process != null && process.isKnown()) {
                changed = !process.equals(mForegroundProcess);
                mForegroundProcess = process;
            }
            return new ProcessRefresh(mForegroundProcess, changed);
        }
    }

    private record ProcessRefresh(TerminalProcessInfo process, boolean changed) {
    }

    private static IOException requestError(final Throwable failure) {
        if (failure == null || failure instanceof IOException) {
            return (IOException) failure;
        }
        return new IOException("terminal metadata request failed", failure);
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
        @Override public void onNotification(final String message) {
            mNotification = message;
            mNotificationSequence++;
            mNotificationChanged = true;
        }

        @Override public void onProgressChanged(final int state, final int percentage) {
            mProgressState = state;
            mProgressPercent = percentage;
            mMetadataChanged = true;
        }

        @Override public void onShellIntegrationChanged() { mMetadataChanged = true; }

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
            mTitleChanged = true;
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

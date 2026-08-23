package io.github.mekhontsev.magicdesk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Executes marker-delimited commands in one MCP automation shell. */
final class PersistentAutomationCommandExecutor
        implements PersistentAutomationShellSession.CommandExecutor {
    private static final int MAX_COMPLETION_BYTES = 16 * 1024;

    private final Object mCommandLock = new Object();
    private final Object mStateLock = new Object();
    private final String mMarker;
    private final byte[] mDelimiter;
    private ShellStreamHandle mStream;
    private boolean mClosed;
    private boolean mCommandActive;
    private boolean mCancelNextCommand;

    PersistentAutomationCommandExecutor(final String marker) {
        mMarker = marker;
        mDelimiter = ("\n" + marker).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public ShellAccess.CommandResult execute(final String command)
            throws IOException {
        return execute(command, null);
    }

    @Override
    public ShellAccess.CommandResult execute(
            final String command,
            final PersistentAutomationShellSession.OutputListener outputListener)
            throws IOException {
        synchronized (mCommandLock) {
            final ShellStreamHandle stream = beginCommand();
            final ReadState readState = new ReadState(
                    mDelimiter, outputListener);
            try {
                stream.writeLine(command);
                final Completion completion = readState.read(
                        stream.inputStream());
                return new ShellAccess.CommandResult(
                        completion.exitCode,
                        readState.output()
                                + "\n" + mMarker
                                + completion.exitCode + "\t"
                                + completion.workingDirectory + "\n");
            } catch (IOException | RuntimeException error) {
                reset(stream);
                throw error;
            } finally {
                endCommand();
            }
        }
    }

    @Override
    public void cancelCurrent() {
        final ShellStreamHandle stream;
        synchronized (mStateLock) {
            if (mClosed) {
                return;
            }
            if (!mCommandActive) {
                mCancelNextCommand = true;
                return;
            }
            stream = mStream;
            mStream = null;
        }
        if (stream != null) {
            stream.close();
        }
    }

    @Override
    public void close() {
        final ShellStreamHandle stream;
        synchronized (mStateLock) {
            mClosed = true;
            stream = mStream;
            mStream = null;
        }
        if (stream != null) {
            stream.close();
        }
    }

    private ShellStreamHandle beginCommand() throws IOException {
        synchronized (mStateLock) {
            if (mClosed) {
                throw new IOException("console shell is closed");
            }
            if (mCancelNextCommand) {
                mCancelNextCommand = false;
                throw new IOException("console command was stopped");
            }
            if (mStream == null) {
                mStream = ShellAccess.openOwnedStream(
                        "exec /system/bin/sh");
            }
            mCommandActive = true;
            return mStream;
        }
    }

    private void endCommand() {
        synchronized (mStateLock) {
            mCommandActive = false;
        }
    }

    private void reset(final ShellStreamHandle expected) {
        final ShellStreamHandle stream;
        synchronized (mStateLock) {
            if (mStream != expected) {
                return;
            }
            stream = mStream;
            mStream = null;
        }
        stream.close();
    }

    static final class ReadState {
        private final byte[] mDelimiter;
        private final ByteArrayOutputStream mOutput =
                new ByteArrayOutputStream();
        private final ByteArrayOutputStream mPendingOutput =
                new ByteArrayOutputStream();
        private final PersistentAutomationShellSession.OutputListener mOutputListener;
        private int mMatched;
        private boolean mTruncated;
        private boolean mStreamingFinished;

        ReadState(final byte[] delimiter) {
            this(delimiter, null);
        }

        ReadState(
                final byte[] delimiter,
                final PersistentAutomationShellSession.OutputListener outputListener) {
            mDelimiter = delimiter;
            mOutputListener = outputListener;
        }

        Completion read(final InputStream input) throws IOException {
            try {
                int value;
                while ((value = input.read()) >= 0) {
                    if (consume(value)) {
                        mMatched = 0;
                        final byte[] candidate = readCompletionLine(input);
                        final Completion completion = parseCompletion(candidate);
                        if (completion != null) {
                            finishStreaming();
                            return completion;
                        }
                        append(mDelimiter, 0, mDelimiter.length);
                        append(candidate, 0, candidate.length);
                        append('\n');
                    }
                }
                flushMatched();
                finishStreaming();
                throw new IOException(
                        "console shell exited before command completion"
                                + outputSuffix());
            } catch (IOException error) {
                flushMatched();
                finishStreaming();
                throw error;
            }
        }

        String output() {
            final String output = new String(
                    mOutput.toByteArray(), StandardCharsets.UTF_8);
            return mTruncated
                    ? output + "\n[MagicDesk: command output truncated]"
                    : output;
        }

        private boolean consume(final int value) {
            while (true) {
                if (value == (mDelimiter[mMatched] & 0xff)) {
                    if (mMatched == 0) {
                        // A line-ending also starts the service marker. Show
                        // the completed text now instead of waiting for the
                        // first byte produced after a long-running command.
                        flushPendingOutput();
                    }
                    mMatched++;
                    return mMatched == mDelimiter.length;
                }
                if (mMatched > 0) {
                    append(mDelimiter, 0, mMatched);
                    mMatched = 0;
                    continue;
                }
                append(value);
                return false;
            }
        }

        private byte[] readCompletionLine(final InputStream input)
                throws IOException {
            final ByteArrayOutputStream line = new ByteArrayOutputStream();
            int value;
            while ((value = input.read()) >= 0 && value != '\n') {
                if (line.size() >= MAX_COMPLETION_BYTES) {
                    throw new IOException(
                            "console shell completion record is too long");
                }
                line.write(value);
            }
            if (value < 0) {
                throw new IOException(
                        "console shell exited during command completion"
                                + outputSuffix());
            }
            return line.toByteArray();
        }

        private Completion parseCompletion(final byte[] encoded) {
            final String completion = new String(
                    encoded, StandardCharsets.UTF_8);
            final int separator = completion.indexOf('\t');
            if (separator <= 0 || separator == completion.length() - 1) {
                return null;
            }
            final int exitCode;
            try {
                exitCode = Integer.parseInt(
                        completion.substring(0, separator));
            } catch (NumberFormatException error) {
                return null;
            }
            return new Completion(
                    exitCode, completion.substring(separator + 1));
        }

        private void flushMatched() {
            if (mMatched > 0) {
                append(mDelimiter, 0, mMatched);
                mMatched = 0;
            }
        }

        private void append(final int value) {
            if (mOutput.size()
                    < BoundedProcessRunner.DEFAULT_MAX_OUTPUT_BYTES) {
                mOutput.write(value);
                if (mOutputListener != null) {
                    mPendingOutput.write(value);
                    if (value == '\n'
                            || (mPendingOutput.size() >= 4096
                                    && value < 0x80)) {
                        flushPendingOutput();
                    }
                }
            } else {
                mTruncated = true;
            }
        }

        private void append(
                final byte[] bytes, final int offset, final int count) {
            for (int index = 0; index < count; index++) {
                append(bytes[offset + index] & 0xff);
            }
        }

        private void finishStreaming() {
            if (mStreamingFinished) {
                return;
            }
            mStreamingFinished = true;
            flushPendingOutput();
            if (mTruncated && mOutputListener != null) {
                mOutputListener.onOutput(
                        "\n[MagicDesk: command output truncated]");
            }
        }

        private void flushPendingOutput() {
            if (mOutputListener == null || mPendingOutput.size() == 0) {
                return;
            }
            mOutputListener.onOutput(new String(
                    mPendingOutput.toByteArray(), StandardCharsets.UTF_8));
            mPendingOutput.reset();
        }

        private String outputSuffix() {
            final String output = output().trim();
            return output.isEmpty() ? "" : ": " + output;
        }
    }

    static final class Completion {
        final int exitCode;
        final String workingDirectory;

        Completion(final int exitCode, final String workingDirectory) {
            this.exitCode = exitCode;
            this.workingDirectory = workingDirectory;
        }
    }
}

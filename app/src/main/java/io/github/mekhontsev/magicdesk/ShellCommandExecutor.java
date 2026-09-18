package io.github.mekhontsev.magicdesk;

import java.io.File;
import java.io.IOException;

/** Owns one cancellable non-PTY shell, preserving its environment between commands. */
final class ShellCommandExecutor
        implements ShellCommandSession.CommandExecutor {
    private final Object mStateLock = new Object();
    private final String mMarker;
    private ShellStreamHandle mStream;
    private boolean mClosed;
    private boolean mCommandActive;
    private boolean mCancelNextCommand;

    ShellCommandExecutor(String marker) { mMarker = marker; }

    @Override public ShellCommandOutput.Result execute(String command, ShellCommandOutput.Sink stdout)
            throws IOException {
        final ShellStreamHandle stream = beginCommand();
        try {
            // Parse the whole request before executing it, so a large request
            // cannot deadlock its writer against output from an earlier line.
            stream.writeLine("eval " + ShellCommandLine.quote(command));
            return ShellCommandOutput.read(stream.inputStream(), mMarker, stdout);
        } catch (IOException | RuntimeException error) {
            synchronized (mStateLock) {
                if (mStream == stream) mStream = null;
            }
            stream.close();
            throw error;
        } finally {
            synchronized (mStateLock) { mCommandActive = false; }
        }
    }

    @Override public void cancelCurrent() {
        final ShellStreamHandle stream;
        synchronized (mStateLock) {
            if (mClosed) return;
            if (!mCommandActive) { mCancelNextCommand = true; return; }
            stream = mStream;
            mStream = null;
        }
        if (stream != null) stream.close();
    }

    @Override public void close() {
        final ShellStreamHandle stream;
        synchronized (mStateLock) {
            mClosed = true;
            stream = mStream;
            mStream = null;
        }
        if (stream != null) stream.close();
    }

    private ShellStreamHandle beginCommand() throws IOException {
        synchronized (mStateLock) {
            if (mClosed) throw new IOException("console shell is closed");
            if (mCancelNextCommand) {
                mCancelNextCommand = false;
                throw new IOException("console command was stopped");
            }
            if (mStream == null) {
                final File helper = new File(MagicDeskApplication.applicationContext()
                        .getApplicationInfo().nativeLibraryDir, "libmagicdesk_pty_bridge.so");
                mStream = ShellAccess.openOwnedStream("exec " + ShellCommandLine.quote(helper.getPath())
                        + " --pipe-shell /system/bin/sh");
            }
            mCommandActive = true;
            return mStream;
        }
    }
}

package io.github.mekhontsev.magicdesk;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

/** Bidirectional PTY transport consumed by the shared terminal emulator. */
interface TerminalTransport extends Closeable {
    InputStream inputStream();

    void write(byte[] data) throws IOException;

    void resize(int rows, int columns) throws IOException;

    String workingDirectory() throws IOException;

    long processId() throws IOException;

    default boolean supportsForegroundProcess() {
        return false;
    }

    default TerminalProcessInfo foregroundProcess() throws IOException {
        return TerminalProcessInfo.unknown();
    }

    default boolean consumesStartupCommand() {
        return false;
    }

    @Override
    void close();

    @FunctionalInterface
    interface Factory {
        TerminalTransport open(
                String workingDirectory,
                int rows,
                int columns,
                String startupCommand) throws IOException;
    }
}

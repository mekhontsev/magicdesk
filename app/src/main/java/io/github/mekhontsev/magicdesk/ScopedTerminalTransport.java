package io.github.mekhontsev.magicdesk;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Auxiliary launch resources follow the retained PTY, never its Android window. */
final class ScopedTerminalTransport implements TerminalTransport {
    private final TerminalTransport terminal;
    private final Closeable scope;
    final CommandExecution execution;
    final boolean managedTmux;
    private final AtomicBoolean closed = new AtomicBoolean();
    ScopedTerminalTransport(TerminalTransport terminal, Closeable scope, CommandExecution execution, boolean managedTmux) {
        this.terminal = terminal; this.scope = scope; this.execution=execution; this.managedTmux=managedTmux;
    }
    @Override public InputStream inputStream() { return terminal.inputStream(); }
    @Override public void write(byte[] data) throws IOException { terminal.write(data); }
    @Override public void resize(int rows, int columns) throws IOException { terminal.resize(rows, columns); }
    @Override public String workingDirectory() throws IOException { return terminal.workingDirectory(); }
    @Override public long processId() throws IOException { return terminal.processId(); }
    @Override public PtyEndpoint outputEndpoint() throws IOException { return terminal.outputEndpoint(); }
    @Override public boolean supportsForegroundProcess() { return terminal.supportsForegroundProcess(); }
    @Override public TerminalProcessInfo foregroundProcess() throws IOException { return terminal.foregroundProcess(); }
    @Override public boolean consumesStartupCommand() { return terminal.consumesStartupCommand(); }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try { terminal.close(); }
        finally { try { scope.close(); } catch (IOException ignored) { } }
    }
}

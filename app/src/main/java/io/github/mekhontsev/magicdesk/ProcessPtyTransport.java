package io.github.mekhontsev.magicdesk;

import android.content.Context;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;

/** Direct process adapter to the shared native PTY protocol; no privileged service. */
final class ProcessPtyTransport implements TerminalTransport {
    private final DataOutputStream control;
    private final InputStream input;
    private final PtyEndpoint endpoint;
    private boolean closed;

    static ProcessBuilder builder(Context context, String directory, int rows, int columns,
            Map<String, String> environment) throws IOException {
        if (directory == null || !directory.startsWith("/") || directory.indexOf('\0') >= 0
                || directory.indexOf('\n') >= 0 || directory.indexOf('\r') >= 0)
            throw new IllegalArgumentException("Invalid PTY working directory");
        if (rows < 2 || rows > 65535 || columns < 2 || columns > 65535)
            throw new IllegalArgumentException("Invalid PTY dimensions");
        String shell = AndroidShell.path(context);
        var builder = ShellExecutionEnvironment.processBuilder(context, true,
                context.getApplicationInfo().nativeLibraryDir + "/libmagicdesk_pty_bridge.so",
                Integer.toString(rows), Integer.toString(columns), directory, shell);
        builder.environment().putAll(environment);
        builder.environment().put("SHELL", shell);
        builder.environment().put(AndroidShell.PATH_ENV, shell);
        return builder.redirectErrorStream(true);
    }

    ProcessPtyTransport(Context context, String directory, int rows, int columns,
            Map<String, String> environment) throws IOException {
        AutomationCommandRuntime.get(context).prepareLocal();
        Process process = builder(context, directory, rows, columns, environment).start();
        input = process.getInputStream();
        control = new DataOutputStream(process.getOutputStream());
        var ready = new java.util.concurrent.CompletableFuture<PtyEndpoint>();
        new Thread(() -> {
            try { ready.complete(PtyControlProtocol.readEndpoint(input)); }
            catch (IOException error) { ready.completeExceptionally(error); }
        }, "LocalPtyHandshake").start();
        try {
            // EVENT_WAIT: native PTY header; expiry closes the relay and its child process tree.
            endpoint = ready.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); close(); throw new IOException("PTY startup interrupted", error);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException error) {
            close(); throw new IOException("PTY startup failed", error);
        }
    }

    @Override public InputStream inputStream() { return input; }
    @Override public synchronized void write(byte[] data) throws IOException {
        if (closed) throw new IOException("PTY is closed");
        PtyControlProtocol.writeData(control, data); control.flush();
    }
    @Override public synchronized void resize(int rows, int columns) throws IOException {
        if (closed) throw new IOException("PTY is closed");
        PtyControlProtocol.writeResize(control, rows, columns); control.flush();
    }
    @Override public long processId() { return endpoint.processId(); }
    @Override public PtyEndpoint outputEndpoint() { return endpoint; }
    @Override public String workingDirectory() throws IOException {
        return Path.of("/proc/" + endpoint.processId() + "/cwd").toRealPath().toString();
    }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        try { control.close(); } catch (IOException ignored) { }
        try { input.close(); } catch (IOException ignored) { }
    }
}

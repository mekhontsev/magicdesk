package io.github.mekhontsev.magicdesk;

import android.content.Context;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;

/** Direct process adapter to the shared native PTY protocol; no privileged service. */
final class ProcessPtyTransport implements TerminalTransport {
    private final NativePtyProcess pty;

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
        pty = new NativePtyProcess(builder(context, directory, rows, columns, environment));
    }

    @Override public InputStream inputStream() { return pty.inputStream(); }
    @Override public void write(byte[] data) throws IOException { pty.write(data); }
    @Override public void resize(int rows, int columns) throws IOException { pty.resize(rows, columns); }
    @Override public long processId() { return pty.endpoint().processId(); }
    @Override public PtyEndpoint outputEndpoint() { return pty.endpoint(); }
    @Override public String workingDirectory() throws IOException {
        return Path.of("/proc/" + processId() + "/cwd").toRealPath().toString();
    }
    @Override public void close() { pty.close(); }
}

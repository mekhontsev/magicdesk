package io.github.mekhontsev.magicdesk;

import android.content.Context;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** App-owned transport of the same native framed pipe relay used by Shell and Termux. */
final class LocalCommandStream implements CommandStream {
    private final Process process;

    LocalCommandStream(Context context, boolean inheritCommandChannel) throws IOException {
        AutomationCommandRuntime.get(context).prepareLocal();
        var builder = ShellExecutionEnvironment.processBuilder(context, false,
                context.getApplicationInfo().nativeLibraryDir + "/libmagicdesk_pty_bridge.so",
                "--pipe-shell", AndroidShell.path(context)).redirectError(ProcessBuilder.Redirect.INHERIT);
        if (!inheritCommandChannel) builder.environment().remove(MagicDeskCli.ENDPOINT_ENV);
        process = builder.start();
    }

    @Override public InputStream inputStream() { return process.getInputStream(); }
    @Override public void writeLine(String line) throws IOException {
        var output = process.getOutputStream();
        output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }
    @Override public void close() {
        // Relay EOF owns child-tree teardown, including descendants holding the output pipe.
        try { process.getOutputStream().close(); } catch (IOException ignored) { }
        try { process.getInputStream().close(); } catch (IOException ignored) { }
    }
}

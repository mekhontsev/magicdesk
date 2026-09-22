package io.github.mekhontsev.magicdesk;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** App-UID server process, bounded diagnostics and its private directory have one owner. */
final class HostedServerProcess {
    static Closeable start(List<String> arguments, Map<String, String> environment, Path directory,
            String label, CommandExecution.Completion completion) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(arguments).redirectErrorStream(true);
        builder.environment().remove("LD_PRELOAD");
        builder.environment().remove("LD_LIBRARY_PATH");
        builder.environment().putAll(environment);
        Process process = builder.start();
        try { process.getOutputStream().close(); }
        catch (IOException error) { process.destroyForcibly(); throw error; }
        AtomicBoolean stopped = new AtomicBoolean();
        Thread reader = new Thread(() -> {
            String output = "";
            try (var input = process.getInputStream()) {
                var log = new java.io.ByteArrayOutputStream();
                byte[] bytes = new byte[8192];
                for (int n; (n = input.read(bytes)) >= 0;) {
                    if (log.size() + n > 16384) log.reset();
                    log.write(bytes, 0, n);
                }
                output = log.toString(java.nio.charset.StandardCharsets.UTF_8);
                int code = process.waitFor();
                if (!stopped.get()) completion.complete(code, output, null);
            } catch (IOException | InterruptedException error) {
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                if (!stopped.get()) completion.complete(-1, output, error);
            } finally {
                process.destroyForcibly();
                try { FileTreeDeletion.deleteIfExists(directory); } catch (IOException ignored) { }
            }
        }, label);
        reader.start();
        return () -> { stopped.set(true); process.destroy(); };
    }

    private HostedServerProcess() { }
}

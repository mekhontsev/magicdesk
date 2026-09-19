package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Process;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

/** X server bootstrap, separate from the identity used to enter a Linux environment. */
final class X11Execution {
    final CommandExecution commands;
    final int serverUid;
    final String serverPackage;
    private final Context context;
    private final String keyboardSource;

    X11Execution(Context context, DesktopExecBackend backend, String keyboardSource) {
        this.context = context.getApplicationContext();
        this.keyboardSource = DesktopExecWorkingDirectory.normalize(keyboardSource);
        commands = new CommandExecution(context, backend);
        serverUid = commands.termux == null ? Process.myUid() : commands.uid;
        serverPackage = commands.termux == null ? context.getPackageName() : commands.termux.packageName;
        if (commands.termux == null && this.keyboardSource.isEmpty())
            throw new IllegalArgumentException("An XKB data directory is required for the Shell X11 executor");
    }

    X11LaunchSpec spec(int dpi, boolean application, String fileEnvironment) {
        String prefix = commands.termux == null ? "" : new java.io.File(commands.home).getParent() + "/usr";
        String parent = commands.termux == null ? context.getCacheDir() + "/x" : commands.home + "/.cache/magicdesk/x11";
        // The local copy is prepared off the UI thread before spawning the server.
        String keyboard = commands.termux == null ? keyboardSource
                : keyboardSource.isEmpty() ? prefix + "/share/X11/xkb" : keyboardSource;
        return new X11LaunchSpec(context.getApplicationInfo().sourceDir, context.getApplicationInfo().nativeLibraryDir,
                context.getPackageName(), serverPackage, parent, commands.termux == null ? "" : prefix + "/tmp",
                keyboard, commands.termux == null, dpi, application, fileEnvironment);
    }

    Closeable startServer(X11LaunchSpec spec, CommandExecution.Completion completion) throws IOException {
        if (commands.termux != null) return commands.start(spec.serverCommand, "", spec.id, spec.stdin, completion);
        String keyboard = X11KeyboardData.prepare(context, keyboardSource);
        Path directory = Path.of(spec.directory);
        Files.createDirectories(directory.getParent());
        Files.createDirectory(directory);
        java.lang.Process process = null;
        try {
            Files.write(Path.of(spec.authorityFile), Base64.getDecoder().decode(spec.stdin.trim()));
            // Only an explicitly privileged guest can reach this private app subtree or bind it into its namespace.
            // Once bound, an unprivileged guest user can read the cookie and connect to its session socket.
            android.system.Os.chmod(spec.directory, 0755);
            android.system.Os.chmod(spec.authorityFile, 0444);
            Files.createDirectory(directory.resolve("content"));
            android.system.Os.chmod(directory.resolve("content").toString(), 01777);
            ProcessBuilder builder = new ProcessBuilder(spec.arguments).redirectErrorStream(true);
            builder.environment().remove("LD_PRELOAD");
            builder.environment().remove("LD_LIBRARY_PATH");
            builder.environment().putAll(spec.environment);
            builder.environment().put("XKB_CONFIG_ROOT", keyboard);
            process = builder.start();
            process.getOutputStream().close();
            java.lang.Process child = process;
            AtomicBoolean stopped = new AtomicBoolean();
            Thread reader = new Thread(() -> {
                String output = "";
                try (var input = child.getInputStream()) {
                    var log = new java.io.ByteArrayOutputStream();
                    byte[] bytes = new byte[8192];
                    for (int n; (n = input.read(bytes)) >= 0;) {
                        if (log.size() + n > 16384) log.reset();
                        log.write(bytes, 0, n);
                    }
                    output = log.toString(java.nio.charset.StandardCharsets.UTF_8);
                    int code = child.waitFor();
                    if (!stopped.get()) completion.complete(code, output, null);
                } catch (IOException | InterruptedException error) {
                    if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                    if (!stopped.get()) completion.complete(-1, output, error);
                } finally {
                    child.destroyForcibly();
                    try { FileTreeDeletion.deleteIfExists(directory); } catch (IOException ignored) { }
                }
            }, "X11Server-" + spec.id);
            reader.start();
            return () -> { stopped.set(true); child.destroy(); };
        } catch (IOException | android.system.ErrnoException | RuntimeException error) {
            if (process != null) process.destroyForcibly();
            FileTreeDeletion.deleteIfExists(directory);
            throw new IOException("Cannot start the local X11 server", error);
        }
    }
}

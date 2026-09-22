package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Process;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

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
        String keyboard = HostedKeyboardData.prepare(context, keyboardSource);
        Path directory = Path.of(spec.directory);
        Files.createDirectories(directory.getParent());
        Files.createDirectory(directory);
        try {
            Files.write(Path.of(spec.authorityFile), Base64.getDecoder().decode(spec.stdin.trim()));
            // Only an explicitly privileged guest can reach this private app subtree or bind it into its namespace.
            // Once bound, an unprivileged guest user can read the cookie and connect to its session socket.
            android.system.Os.chmod(spec.directory, 0755);
            android.system.Os.chmod(spec.authorityFile, 0444);
            Files.createDirectory(directory.resolve("content"));
            android.system.Os.chmod(directory.resolve("content").toString(), 01777);
            var environment = new java.util.LinkedHashMap<>(spec.environment);
            environment.put("XKB_CONFIG_ROOT", keyboard);
            return HostedServerProcess.start(spec.arguments, environment, directory, "X11Server-" + spec.id, completion);
        } catch (IOException | android.system.ErrnoException | RuntimeException error) {
            FileTreeDeletion.deleteIfExists(directory);
            throw new IOException("Cannot start the local X11 server", error);
        }
    }
}

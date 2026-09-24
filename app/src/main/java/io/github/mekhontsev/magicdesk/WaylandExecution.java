package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Process;
import io.github.mekhontsev.magicdesk.wayland.WaylandClientLaunch;
import io.github.mekhontsev.magicdesk.wayland.WaylandServer;
import io.github.mekhontsev.magicdesk.wayland.WaylandBroker;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.UUID;

/** Selected client identity and an unprivileged compositor, with independent process ownership. */
final class WaylandExecution {
    final String id = "wayland-" + UUID.randomUUID();
    final String token;
    final CommandExecution commands;
    final int serverUid;
    private final Context context;
    private final String keyboard;
    private final String directory;
    private final String executorPackage;
    private final HostedGuestFiles guestFiles;
    private final String fileEnvironment;

    WaylandExecution(Context context, DesktopExecBackend backend, String keyboardDirectory, String fileEnvironment) {
        this.context = context.getApplicationContext();
        commands = new CommandExecution(context, backend);
        this.fileEnvironment = fileEnvironment;
        guestFiles = new HostedGuestFiles(context.getApplicationInfo().nativeLibraryDir, !fileEnvironment.isEmpty());
        serverUid = commands.termux == null ? Process.myUid() : commands.uid;
        executorPackage = commands.termux == null ? context.getPackageName() : commands.termux.packageName;
        keyboard = DesktopExecWorkingDirectory.normalize(keyboardDirectory);
        if (commands.termux == null && keyboard.isEmpty())
            throw new IllegalArgumentException("An XKB data directory is required for the Shell graphical executor");
        byte[] secret = new byte[32];
        new java.security.SecureRandom().nextBytes(secret);
        token = java.util.HexFormat.of().formatHex(secret);
        String parent = commands.termux == null ? context.getCacheDir() + "/w" : commands.home + "/.cache/w";
        directory = parent + "/" + UUID.randomUUID().toString().replace("-", "");
        String endpoint = commands.uid == 0 ? "/wayland-2147483647" : "/wayland-0";
        if ((directory + endpoint).getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= 108)
            throw new IllegalArgumentException("Wayland runtime directory exceeds Unix socket path limit");
    }

    Closeable startServer(CommandExecution.Completion completion) throws IOException {
        var info = context.getApplicationInfo();
        var environment = new LinkedHashMap<String, String>();
        environment.put("CLASSPATH", info.sourceDir);
        environment.put("MAGICDESK_WAYLAND_PACKAGE", context.getPackageName());
        environment.put("MAGICDESK_WAYLAND_EXECUTOR", executorPackage);
        environment.put("MAGICDESK_WAYLAND_SESSION", id);
        environment.put("MAGICDESK_WAYLAND_TOKEN", token);
        environment.put("MAGICDESK_WAYLAND_LIBRARY", info.nativeLibraryDir + "/libmagicdesk_wayland_executor.so");
        environment.put("XDG_RUNTIME_DIR", directory);
        if (commands.uid == 0) environment.put("MAGICDESK_WAYLAND_GUEST_SOCKET", "1");
        guestFiles.configure(environment);
        if (!fileEnvironment.isEmpty()) environment.put("MAGICDESK_WAYLAND_GUEST_CONTENT", "/tmp/magicdesk-wayland/content");
        environment.put("XKB_CONFIG_ROOT", commands.termux == null ? HostedKeyboardData.prepare(context, keyboard)
                : keyboard.isEmpty() ? new java.io.File(commands.home).getParent() + "/usr/share/X11/xkb" : keyboard);
        var arguments = List.of("/system/bin/app_process", "-Xnoimage-dex2oat", "/", "--nice-name=" + id,
                WaylandServer.class.getName());
        if (commands.termux != null) {
            StringBuilder invocation = new StringBuilder("env -u LD_PRELOAD -u LD_LIBRARY_PATH");
            environment.forEach((key, value) -> invocation.append(' ').append(key).append('=').append(q(value)));
            arguments.forEach(value -> invocation.append(' ').append(q(value)));
            String command = "set -eu\numask 077\nmkdir -p " + q(Path.of(directory).getParent().toString())
                    + "\nmkdir " + q(directory) + "\ntrap " + q("rm -rf -- " + q(directory + "/content") + "; rm -f -- " + q(directory + "/wayland-0")
                            + " " + q(directory + "/wayland-0.lock") + "; rmdir -- " + q(directory))
                    + " EXIT\n" + invocation;
            return commands.start(command, "", id, null, completion);
        }
        Path path = Path.of(directory);
        Files.createDirectories(path.getParent());
        Files.createDirectory(path);
        try {
            android.system.Os.chmod(path.getParent().toString(), 0700);
            android.system.Os.chmod(directory, 0700);
            return HostedServerProcess.start(arguments, environment, path, id, completion);
        } catch (IOException | android.system.ErrnoException | RuntimeException error) {
            FileTreeDeletion.deleteIfExists(path);
            throw new IOException("Cannot start Wayland compositor", error);
        }
    }

    Closeable startClient(WaylandClientLaunch handoff, String command, String workingDirectory,
            CommandExecution.Completion completion) {
        var info = context.getApplicationInfo();
        String identity = commands.termux == null ? "com.android.shell" : commands.termux.packageName;
        String shell = commands.termux == null ? "/system/bin/sh" : new java.io.File(commands.home).getParent() + "/usr/bin/sh";
        // WAYLAND_SOCKET is assigned only after app_process hands the connection to the native helper.
        String script = "unset DISPLAY WAYLAND_DISPLAY\nexport XDG_SESSION_TYPE=wayland\n" + command;
        String invocation = "env -u LD_PRELOAD -u LD_LIBRARY_PATH CLASSPATH=" + q(info.sourceDir) + " "
                + String.join(" ", handoff.arguments(identity, info.nativeLibraryDir + "/libmagicdesk_wayland_client.so",
                        shell, "-c", script).stream().map(WaylandExecution::q).toList());
        return commands.start(invocation, workingDirectory, id + "-client", null, completion);
    }

    boolean hasNamedEndpoint() { return commands.uid == serverUid || commands.uid == 0; }

    Closeable startBroker(WaylandBroker broker, String socket, String memoryLabel, CommandExecution.Completion completion) {
        if (commands.uid != 0 || socket == null || !socket.matches("wayland-[0-9]+"))
            throw new IllegalStateException("Wayland broker requires the captured root executor");
        var arguments = broker.arguments(context.getApplicationInfo().nativeLibraryDir + "/libmagicdesk_wayland_client.so",
                directory + "/" + socket, serverUid, memoryLabel);
        String command = "unset LD_PRELOAD LD_LIBRARY_PATH\nexec "
                + String.join(" ", arguments.stream().map(WaylandExecution::q).toList());
        return commands.start(command, "", id + "-broker", null, completion);
    }

    Closeable startNamedClient(String socket, String command, String workingDirectory, CommandExecution.Completion completion) {
        if (!hasNamedEndpoint() || socket == null || !socket.matches("wayland-[0-9]+"))
            throw new IllegalArgumentException("No accessible Wayland socket");
        String script = "unset DISPLAY WAYLAND_SOCKET\nexport XDG_SESSION_TYPE=wayland XDG_RUNTIME_DIR=" + q(directory)
                + " WAYLAND_DISPLAY=" + q(socket) + " MAGICDESK_WAYLAND_RUNTIME=" + q(directory)
                + guestFiles.exports() + "\n" + command;
        return commands.start(script, workingDirectory, id + "-client", null, completion);
    }

    private static String q(String value) { return ShellCommandLine.quote(value); }
    boolean canExecuteHostCommand() { return fileEnvironment.isEmpty(); }
}

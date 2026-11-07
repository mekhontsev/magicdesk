package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Stable Android shell environment shared by every MagicDesk exec transport. */
final class ShellExecutionEnvironment {
    private static final String SYSTEM_PATH = String.join(":",
            "/system/bin",
            "/system/xbin",
            "/product/bin",
            "/system_ext/bin",
            "/vendor/bin",
            "/vendor/xbin",
            "/odm/bin",
            "/apex/com.android.runtime/bin",
            "/apex/com.android.art/bin");

    private ShellExecutionEnvironment() {
    }

    static ProcessBuilder processBuilder(
            final android.content.Context context,
            final boolean interactive,
            final String... command) throws IOException {
        final String shell = AndroidShell.requireExecutable(context.getApplicationInfo().nativeLibraryDir);
        final ProcessBuilder builder = new ProcessBuilder(command);
        final int uid = android.system.Os.getuid();
        final RuntimeDataPaths paths = RuntimeDataPaths.selected(uid, context.getDataDir().toPath());
        RuntimeDataPaths.preparePrivilegedParent(uid);
        paths.prepare();
        AndroidShell.installAliases(paths.tools(), shell);
        apply(builder.environment(), uid, interactive, paths, shell);
        if (interactive) {
            prepareInteractiveShell(Path.of(builder.environment().get("ENV")), uid);
        }
        return builder;
    }

    static ProcessBuilder command(android.content.Context context, String command) throws IOException {
        return processBuilder(context, false, AndroidShell.path(context), "-c", command);
    }

    static String interactiveShellStartup(final int uid) {
        return TerminalShellIntegration.androidPrompt(uid == ShellAccess.ROOT_UID);
    }

    static void prepareInteractiveShell(final Path target, final int uid) throws IOException {
        final byte[] contents = interactiveShellStartup(uid).getBytes(StandardCharsets.UTF_8);
        if (Files.isRegularFile(target) && Files.size(target) == contents.length
                && Arrays.equals(Files.readAllBytes(target), contents)) {
            return;
        }
        final Path temporary = Files.createTempFile(target.getParent(), ".shellrc-", ".tmp");
        try {
            Files.write(temporary, contents);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static void apply(
            final Map<String, String> environment,
            final int uid,
            final boolean interactive,
            final RuntimeDataPaths paths,
            final String shell) {
        if (environment == null) {
            throw new IllegalArgumentException("missing process environment");
        }
        removeHostEnvironment(environment);
        AndroidShell.apply(environment, shell);
        environment.put("HOME", paths.root().toString());
        environment.put("TMPDIR", paths.temporary().toString());
        environment.put("XDG_CONFIG_HOME", paths.config().toString());
        environment.put("XDG_DATA_HOME", paths.data().toString());
        environment.put("XDG_CACHE_HOME", paths.cache().toString());
        environment.put("XDG_STATE_HOME", paths.state().toString());
        environment.put("MAGICDESK_RUNTIME", paths.runtime().toString());
        environment.put("MAGICDESK_TOOLS", paths.tools().toString());
        environment.put("MAGICDESK_GUEST_HOME", paths.linux().toString());
        environment.put("PATH", paths.tools() + ":" + SYSTEM_PATH);
        environment.put("USER", uid == 0 ? "root" : uid == 2000 ? "shell" : "u" + uid / 100000 + "_a" + (uid % 100000 - 10000));
        environment.put("LOGNAME", environment.get("USER"));
        environment.put("LANG", "C.UTF-8");
        environment.put("LC_ALL", "C.UTF-8");
        environment.put("TERM", interactive ? "xterm-256color" : "dumb");
        if (interactive) {
            environment.put("COLORTERM", "truecolor");
            environment.put("ENV", paths.config() + "/shellrc");
        } else {
            environment.remove("COLORTERM");
            environment.remove("ENV");
        }
        CommandShellEnvironment.apply(environment);
    }

    static String prepareToolsDirectory() throws IOException {
        int uid = android.system.Os.getuid();
        RuntimeDataPaths.preparePrivilegedParent(uid);
        var paths = RuntimeDataPaths.privileged(uid);
        paths.prepare();
        return paths.tools().toString();
    }

    static String diagnostics(final int uid) {
        final RuntimeDataPaths paths = RuntimeDataPaths.privileged(uid);
        return "uid=" + uid
                + ", home=" + paths.root()
                + ", tools=" + paths.tools()
                + ", TERM=xterm-256color";
    }

    private static void removeHostEnvironment(
            final Map<String, String> environment) {
        final List<String> names = new ArrayList<>(environment.keySet());
        for (final String name : names) {
            if (name.startsWith("TERMUX_")
                    || name.startsWith("SHELL_CMD__")
                    || name.equals("PREFIX")
                    || name.equals("TMUX_TMPDIR")
                    || name.equals("PWD")
                    || name.equals("OLDPWD")
                    || name.equals("LD_PRELOAD")
                    || name.equals("LD_LIBRARY_PATH")) {
                environment.remove(name);
            }
        }
    }

}

package io.github.mekhontsev.magicdesk;

import android.system.ErrnoException;
import android.system.Os;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Stable Android shell environment shared by every MagicDesk exec transport. */
final class ShellExecutionEnvironment {
    private static final String RUNTIME_PREFIX =
            "/data/local/tmp/magicdesk-";
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
            final boolean interactive,
            final String... command) {
        final ProcessBuilder builder = new ProcessBuilder(command);
        apply(builder.environment(), android.system.Os.getuid(), interactive);
        return builder;
    }

    static void apply(
            final Map<String, String> environment,
            final int uid,
            final boolean interactive) {
        apply(environment, uid, interactive, prepareRuntime(uid));
    }

    static void apply(
            final Map<String, String> environment,
            final int uid,
            final boolean interactive,
            final String runtimeRoot) {
        if (runtimeRoot == null || !runtimeRoot.startsWith("/")) {
            throw new IllegalArgumentException("invalid shell runtime root");
        }
        apply(environment, uid, interactive, new RuntimePaths(runtimeRoot));
    }

    private static void apply(
            final Map<String, String> environment,
            final int uid,
            final boolean interactive,
            final RuntimePaths paths) {
        if (environment == null) {
            throw new IllegalArgumentException("missing process environment");
        }
        removeHostEnvironment(environment);
        environment.put("HOME", paths.home);
        environment.put("TMPDIR", paths.tmp);
        environment.put("XDG_CONFIG_HOME", paths.config);
        environment.put("XDG_DATA_HOME", paths.data);
        environment.put("XDG_CACHE_HOME", paths.cache);
        environment.put("XDG_STATE_HOME", paths.state);
        environment.put("MAGICDESK_RUNTIME", paths.root);
        environment.put("MAGICDESK_TOOLS", paths.bin);
        environment.put("PATH", paths.bin + ":" + SYSTEM_PATH);
        environment.put("SHELL", "/system/bin/sh");
        environment.put("USER", uid == ShellAccess.ROOT_UID ? "root" : "shell");
        environment.put("LOGNAME", environment.get("USER"));
        environment.put("LANG", "C.UTF-8");
        environment.put("LC_ALL", "C.UTF-8");
        environment.put("TERM", interactive ? "xterm-256color" : "dumb");
        if (interactive) {
            environment.put("COLORTERM", "truecolor");
        } else {
            environment.remove("COLORTERM");
        }
    }

    static String diagnostics(final int uid) {
        final RuntimePaths paths = paths(uid);
        return "uid=" + uid
                + ", home=" + paths.home
                + ", tools=" + paths.bin
                + ", TERM=xterm-256color";
    }

    private static RuntimePaths prepareRuntime(final int uid) {
        final RuntimePaths preferred = paths(uid);
        if (createRuntimeDirectories(preferred)) {
            return preferred;
        }
        // Android's shell identity is expected to own /data/local/tmp. Keep
        // core commands usable if a vendor policy rejects our subdirectory.
        return RuntimePaths.fallback();
    }

    private static RuntimePaths paths(final int uid) {
        final String identity = uid == ShellAccess.ROOT_UID
                ? "root" : "shell";
        return new RuntimePaths(RUNTIME_PREFIX + identity);
    }

    private static boolean createRuntimeDirectories(
            final RuntimePaths paths) {
        for (final String path : paths.directories()) {
            final File directory = new File(path);
            if (!directory.isDirectory() && !directory.mkdirs()) {
                return false;
            }
            try {
                Os.chmod(path, 0700);
            } catch (ErrnoException error) {
                return false;
            }
        }
        return true;
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

    private static final class RuntimePaths {
        final String root;
        final String home;
        final String tmp;
        final String bin;
        final String config;
        final String data;
        final String cache;
        final String state;

        RuntimePaths(final String root) {
            this(
                    root,
                    root + "/home",
                    root + "/tmp",
                    root + "/bin",
                    root + "/home/.config",
                    root + "/home/.local/share",
                    root + "/home/.cache",
                    root + "/home/.local/state");
        }

        RuntimePaths(
                final String root,
                final String home,
                final String tmp,
                final String bin,
                final String config,
                final String data,
                final String cache,
                final String state) {
            this.root = root;
            this.home = home;
            this.tmp = tmp;
            this.bin = bin;
            this.config = config;
            this.data = data;
            this.cache = cache;
            this.state = state;
        }

        static RuntimePaths fallback() {
            return new RuntimePaths(
                    "/data/local/tmp",
                    "/data/local/tmp",
                    "/data/local/tmp",
                    "/data/local/tmp",
                    "/data/local/tmp",
                    "/data/local/tmp",
                    "/data/local/tmp",
                    "/data/local/tmp");
        }

        List<String> directories() {
            return List.of(root, home, tmp, bin, config, data, cache, state);
        }
    }
}

package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

/** One data layout per executor identity; no privilege selection or fallback. */
record RuntimeDataPaths(Path root) {
    static final Path SHELL_ROOT = Path.of("/data/local/tmp/magicdesk");

    RuntimeDataPaths {
        if (root == null || !root.isAbsolute()) throw new IllegalArgumentException("Absolute data root required");
        root = root.normalize();
    }

    static RuntimeDataPaths privileged(int uid) {
        return switch (uid) {
            case 2000 -> new RuntimeDataPaths(SHELL_ROOT);
            case 0 -> new RuntimeDataPaths(SHELL_ROOT.resolve("root"));
            default -> throw new IllegalArgumentException("An app executor must supply its Android data directory");
        };
    }

    static RuntimeDataPaths selected(int uid, Path appData) {
        return uid == 0 || uid == 2000 ? privileged(uid) : new RuntimeDataPaths(appData);
    }

    Path files() { return root.resolve("files"); }
    Path cache() { return root.resolve("cache"); }
    Path temporary() { return cache().resolve("tmp"); }
    Path tools() { return files().resolve("bin"); }
    Path config() { return files().resolve("config"); }
    Path data() { return files().resolve("share"); }
    Path state() { return files().resolve("state"); }
    Path runtime() { return root.resolve("no_backup/runtime"); }
    Path linux() { return root.resolve("linux"); }

    void prepare() throws IOException {
        var permissions = PosixFilePermissions.fromString("rwx------");
        for (Path path : List.of(root, files(), cache(), temporary(), tools(), config(), data(), state(), root.resolve("no_backup"), runtime())) {
            Files.createDirectories(path, PosixFilePermissions.asFileAttribute(permissions));
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid runtime directory: " + path);
            Files.setPosixFilePermissions(path, permissions);
        }
    }

    static void preparePrivilegedParent(int uid) throws IOException {
        if (uid != 0) return;
        try {
            Files.createDirectory(SHELL_ROOT, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            android.system.Os.chown(SHELL_ROOT.toString(), 2000, 2000);
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            if (!Files.isDirectory(SHELL_ROOT, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Invalid shell data root: " + SHELL_ROOT);
        } catch (android.system.ErrnoException error) {
            throw new IOException("Cannot prepare shell data root", error);
        }
    }

    static Path journal(String name) throws IOException {
        int uid = android.system.Os.getuid();
        preparePrivilegedParent(uid);
        var paths = privileged(uid);
        paths.prepare();
        Path target = paths.runtime().resolve(name);
        Path previous = SHELL_ROOT.resolveSibling("magicdesk-" + name);
        for (String suffix : List.of(".bak", ".new", "")) {
            Path old = previous.resolveSibling(previous.getFileName() + suffix);
            Path next = target.resolveSibling(target.getFileName() + suffix);
            if (!Files.exists(next, LinkOption.NOFOLLOW_LINKS) && Files.exists(old, LinkOption.NOFOLLOW_LINKS))
                Files.move(old, next, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }
        return target;
    }
}

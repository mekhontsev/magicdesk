package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Executor-scoped keyboard import; never consulted on a frame/input path. */
final class HostedKeyboardData {
    private static final Map<String, Path> COPIES = new HashMap<>();
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private long remaining = MAX_BYTES;
    private int entries = 4096;

    static synchronized String prepare(Context context, CommandExecution execution, String source) throws IOException {
        source = HostedKeyboardSource.normalize(source);
        if (source.isEmpty()) throw new IOException("An XKB data directory is required for the User or Shell graphical executor");
        execution.requireCurrent();
        String key = execution.scope + ":" + source;
        Path existing = COPIES.get(key);
        if (existing != null) return existing.toString();
        var selection = HostedKeyboardSource.parse(source);
        String exported = null;
        ShellCommandSession commands = null;
        Path parent = context.getCacheDir().toPath().resolve("xkb");
        Files.createDirectories(parent);
        Path target = Files.createTempDirectory(parent, "data-");
        boolean ready = false;
        try {
            if (!selection.guestStore().isEmpty()) {
                commands = execution.session("");
                exported = Path.of(execution.temporaryDirectory()).resolve("xkb-" + java.util.UUID.randomUUID()).toString();
                String command = GuestEnvironmentCatalog.command("image", "export-tree", selection.guestStore(),
                        selection.directory(), exported);
                var result = commands.execute(command);
                if (result.exitCode() != 0) throw new IOException("Cannot export guest XKB data: " + result.output());
                source = exported;
            }
            HostedKeyboardData transfer = new HostedKeyboardData();
            try (var files = execution.backend == DesktopExecBackend.APP ? new ShellFileSystem() : null) {
                transfer.copy(source, target, 0, files);
            }
            if (!Files.isRegularFile(target.resolve("rules/evdev"))) throw new IOException("Missing XKB rules/evdev");
            Path published = parent.resolve(java.util.UUID.nameUUIDFromBytes(
                    key.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
            FileTreeDeletion.deleteIfExists(published);
            Files.move(target, published, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            COPIES.put(key, published);
            ready = true;
            return published.toString();
        } finally {
            try {
                if (exported != null && commands != null) commands.execute("rm -rf -- " + ShellCommandLine.quote(exported));
            } finally {
                if (commands != null) commands.close();
                if (!ready) FileTreeDeletion.deleteIfExists(target);
            }
        }
    }

    private void copy(String source, Path target, int depth, ShellFileSystem local) throws IOException {
        if (depth > 12) throw new IOException("XKB directory nesting is too deep");
        int offset = 0;
        do {
            ShellFilePage page = local == null ? ShellAccess.listShellDirectory(source, offset, 256, false, 0, true)
                    : local.list(source, offset, 256, false, 0, true);
            for (ShellFileInfo file : page.entries) {
                if (--entries < 0) throw new IOException("Too many XKB files");
                if (file.name.equals(".") || file.name.equals("..") || file.name.indexOf('/') >= 0)
                    throw new IOException("Invalid XKB file name");
                // Distribution aliases are optional; do not follow links into another tree.
                if (file.symbolicLink) continue;
                Path child = target.resolve(file.name);
                if (file.directory) {
                    Files.createDirectory(child);
                    copy(file.absolutePath, child, depth + 1, local);
                } else {
                    if (!file.isRegularFile() || file.size < 0 || file.size > remaining)
                        throw new IOException("Invalid or oversized XKB file");
                    try (var input = new ParcelFileDescriptor.AutoCloseInputStream(local == null ? ShellAccess.openVerifiedShellFile(file, "r") : local.openVerified(file.absolutePath, "r", file.deviceId, file.inode));
                         var output = Files.newOutputStream(child)) {
                        byte[] bytes = new byte[8192];
                        long count = 0;
                        for (int n; (n = input.read(bytes)) >= 0;) {
                            count += n;
                            if (count > file.size) throw new IOException("XKB file changed while reading");
                            output.write(bytes, 0, n);
                        }
                        if (count != file.size) throw new IOException("Incomplete XKB file");
                        remaining -= count;
                    }
                }
            }
            if (page.complete) return;
            if (page.nextOffset <= offset) throw new IOException("Invalid XKB directory page");
            offset = page.nextOffset;
        } while (true);
    }

    private HostedKeyboardData() { }
}

package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Explicit privileged import of keyboard data. Never consulted by X clients or on a frame/input path. */
final class HostedKeyboardData {
    private static final Map<String, Path> COPIES = new HashMap<>();
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private long remaining = MAX_BYTES;
    private int entries = 4096;

    static synchronized String prepare(Context context, String source) throws IOException {
        source = DesktopExecWorkingDirectory.normalize(source);
        if (source.isEmpty()) throw new IOException("An XKB data directory is required for the Shell graphical executor");
        String key = ShellAccess.currentSnapshot().uid + ":" + source;
        Path existing = COPIES.get(key);
        if (existing != null) return existing.toString();
        Path parent = context.getCacheDir().toPath().resolve("xkb");
        Files.createDirectories(parent);
        Path target = Files.createTempDirectory(parent, "data-");
        boolean ready = false;
        try {
            HostedKeyboardData transfer = new HostedKeyboardData();
            transfer.copy(source, target, 0);
            if (!Files.isRegularFile(target.resolve("rules/evdev"))) throw new IOException("Missing XKB rules/evdev");
            Path published = parent.resolve(java.util.UUID.nameUUIDFromBytes(
                    key.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
            FileTreeDeletion.deleteIfExists(published);
            Files.move(target, published, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            COPIES.put(key, published);
            ready = true;
            return published.toString();
        } finally { if (!ready) FileTreeDeletion.deleteIfExists(target); }
    }

    private void copy(String source, Path target, int depth) throws IOException {
        if (depth > 12) throw new IOException("XKB directory nesting is too deep");
        int offset = 0;
        do {
            ShellFilePage page = ShellAccess.listShellDirectory(source, offset, 256, false, 0, true);
            for (ShellFileInfo file : page.entries) {
                if (--entries < 0) throw new IOException("Too many XKB files");
                if (file.name.equals(".") || file.name.equals("..") || file.name.indexOf('/') >= 0)
                    throw new IOException("Invalid XKB file name");
                // Distribution aliases are optional; do not follow links into another tree.
                if (file.symbolicLink) continue;
                Path child = target.resolve(file.name);
                if (file.directory) {
                    Files.createDirectory(child);
                    copy(file.absolutePath, child, depth + 1);
                } else {
                    if (!file.isRegularFile() || file.size < 0 || file.size > remaining)
                        throw new IOException("Invalid or oversized XKB file");
                    try (var input = new ParcelFileDescriptor.AutoCloseInputStream(ShellAccess.openVerifiedShellFile(file, "r"));
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

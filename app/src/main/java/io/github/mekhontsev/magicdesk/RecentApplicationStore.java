package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Bounded private launch history. Each record is a complete Desktop Entry, never a live task. */
final class RecentApplicationStore {
    static final int LIMIT = 24;
    record Entry(DesktopApplicationShortcut shortcut, String sourcePath, String termuxPackage, long lastUsed) {
        Entry {
            // Launch recipes contain Android paths, independent of the build host's filesystem.
            if (shortcut == null || sourcePath == null || termuxPackage == null || lastUsed <= 0 || lastUsed == Long.MAX_VALUE
                    || sourcePath.indexOf('\0') >= 0
                    || !sourcePath.isEmpty() && !sourcePath.startsWith("/")
                    || !termuxPackage.isEmpty() && !PackageNameValidator.isSafe(termuxPackage)
                    || shortcut.launchTarget != null && shortcut.application == null
                    || shortcut.hasExecLaunch() && shortcut.execBackend != DesktopExecBackend.SHELL
                            && termuxPackage.isEmpty()) {
                throw new IllegalArgumentException("Invalid recent launch");
            }
        }

        String key() {
            if (shortcut.defaultLaunch) {
                AppReference reference = AppReference.forTarget(shortcut.application, shortcut.launchTarget);
                if (reference != null) return digest("android", reference.persistentKey());
            }
            String command = "";
            if (shortcut.hasExecLaunch()) command = shortcut.execBackend == DesktopExecBackend.X11
                    ? DesktopExecTemplate.expandArguments(shortcut.exec, DesktopLaunchArguments.empty(), shortcut.name, shortcut.icon, sourcePath)
                    : DesktopExecTemplate.expand(shortcut.exec, DesktopLaunchArguments.empty(), shortcut.name, shortcut.icon, sourcePath);
            // Presentation and file location are not application identity. Field codes such as %k
            // still distinguish launches when their expansion actually changes the command.
            return digest(termuxPackage, shortcut.application == null ? "" : shortcut.application.persistentKey(),
                    shortcut.launchTarget == null ? "" : shortcut.launchTarget.stableKey(),
                    shortcut.intentUri, shortcut.appShortcutId, Boolean.toString(shortcut.defaultLaunch),
                    shortcut.execBackend.wireName, command, Boolean.toString(shortcut.terminal),
                    shortcut.workingDirectory, Boolean.toString(shortcut.x11Desktop));
        }

        Entry usedAt(long time) { return new Entry(shortcut, sourcePath, termuxPackage, time); }
    }

    private final Path directory;
    RecentApplicationStore(Path directory) { this.directory = directory; }

    List<Entry> read() throws IOException {
        if (!Files.exists(directory)) return List.of();
        final List<Entry> entries = new ArrayList<>();
        try (var paths = Files.newDirectoryStream(directory, "*.desktop")) {
            for (Path path : paths) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 64 * 1024) continue;
                try (var input = Files.newInputStream(path)) {
                    Entry entry = DesktopEntryFile.parseRecent(DesktopEntryFile.readUtf8(input));
                    if (entry != null && path.getFileName().toString().equals(entry.key() + ".desktop")) entries.add(entry);
                } catch (IllegalArgumentException ignored) {
                    // One malformed record must not hide the remaining history.
                }
            }
        }
        entries.sort(Comparator.comparingLong(Entry::lastUsed).reversed().thenComparing(Entry::key));
        return List.copyOf(entries);
    }

    List<Entry> record(Entry entry) throws IOException {
        final List<Entry> previous = read();
        // Preserve a strict order when clocks move backwards or launches share a millisecond.
        long time = previous.isEmpty() ? entry.lastUsed() : Math.max(entry.lastUsed(), Math.min(Long.MAX_VALUE - 1, previous.get(0).lastUsed() + 1));
        entry = entry.usedAt(time);
        final byte[] bytes = DesktopEntryFile.encodeRecent(entry).getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, ".recent-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, directory.resolve(entry.key() + ".desktop"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
        List<Entry> current = read();
        for (int i = LIMIT; i < current.size(); i++) Files.deleteIfExists(directory.resolve(current.get(i).key() + ".desktop"));
        return List.copyOf(current.subList(0, Math.min(LIMIT, current.size())));
    }

    List<Entry> removeSource(String termuxPackage, String sourcePath) throws IOException {
        if (termuxPackage == null || termuxPackage.isEmpty() || sourcePath == null || sourcePath.isEmpty())
            throw new IllegalArgumentException("A bound launcher source is required");
        for (Entry entry : read()) {
            if (entry.termuxPackage().equals(termuxPackage) && entry.sourcePath().equals(sourcePath))
                Files.deleteIfExists(directory.resolve(entry.key() + ".desktop"));
        }
        return read();
    }

    private static String digest(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update((value.length() + ":").getBytes(StandardCharsets.UTF_8));
                digest.update(value.getBytes(StandardCharsets.UTF_8));
            }
            StringBuilder key = new StringBuilder();
            for (byte value : digest.digest()) key.append(Character.forDigit((value & 255) >>> 4, 16)).append(Character.forDigit(value & 15, 16));
            return key.toString();
        }
        catch (NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }
}

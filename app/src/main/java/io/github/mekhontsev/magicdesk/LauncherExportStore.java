package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.json.JSONObject;

/** Private, profile-local export identities. No commands, history entries or transient display IDs. */
final class LauncherExportStore {
    static final int LIMIT = 512;
    record Target(String source, String path, String termuxPackage, int termuxUid, int shellUid, long profile) {
        Target {
            if (!List.of("termux", "guest", "desktop").contains(source) || path == null || !path.startsWith("/")
                    || path.indexOf('\0') >= 0 || path.length() > 4096 || profile < 0
                    || termuxPackage == null || (!termuxPackage.isEmpty() && !PackageNameValidator.isSafe(termuxPackage))
                    || (termuxPackage.isEmpty() ? termuxUid != -1 : termuxUid < 0)
                    || (source.equals("termux") ? shellUid != -1 || termuxPackage.isEmpty()
                            : shellUid != 0 && shellUid != 2000 && !(source.equals("guest") && shellUid >= 10000
                                    && shellUid % 100000 >= 10000 && shellUid % 100000 <= 19999)))
                throw new IllegalArgumentException("Invalid launcher source identity");
        }
        JSONObject json() throws org.json.JSONException {
            return new JSONObject().put("source", source).put("path", path).put("termuxPackage", termuxPackage)
                    .put("termuxUid", termuxUid).put("shellUid", shellUid).put("profile", profile);
        }
        static Target parse(JSONObject json) throws org.json.JSONException {
            return new Target(json.getString("source"), json.getString("path"), json.getString("termuxPackage"),
                    json.getInt("termuxUid"), json.getInt("shellUid"), json.getLong("profile"));
        }
    }

    record Entry(String id, Target target, String label, String certificate) {
        Entry {
            requireId(id);
            if (target == null || label == null || label.isBlank() || label.length() > 512 || label.indexOf('\0') >= 0
                    || certificate == null || !certificate.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid launcher export");
        }
        String packageName(String host) { return host + ".launcher.e" + id; }
    }

    private final Path directory;
    LauncherExportStore(Path directory) { this.directory = directory; }

    Entry get(String id) throws IOException {
        requireId(id);
        Path file = directory.resolve(id + ".json");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 32768)
            throw new IOException("Launcher entry is unavailable; add it again from MagicDesk");
        try {
            var json = new JSONObject(new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8));
            var entry = new Entry(json.getString("id"), Target.parse(json.getJSONObject("target")),
                    json.getString("label"), json.getString("certificate"));
            if (!entry.id().equals(id)) throw new IllegalArgumentException("Launcher identity mismatch");
            return entry;
        } catch (org.json.JSONException | IllegalArgumentException error) { throw new IOException("Invalid launcher entry", error); }
    }

    List<Entry> read() throws IOException {
        if (!Files.exists(directory)) return List.of();
        var entries = new ArrayList<Entry>();
        try (var paths = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : paths) {
                String name = file.getFileName().toString();
                if (!name.matches("[0-9a-f]{32}\\.json")) continue;
                entries.add(get(name.substring(0, 32)));
                if (entries.size() > LIMIT) throw new IOException("Too many launcher exports");
            }
        }
        return List.copyOf(entries);
    }

    Entry put(Target target, String label, String certificate) throws IOException {
        var entries = read();
        Entry previous = entries.stream().filter(entry -> entry.target().equals(target)).findFirst().orElse(null);
        if (previous == null && entries.size() >= LIMIT) throw new IOException("Too many launcher exports");
        if (previous != null && !previous.certificate().equals(certificate))
            throw new IOException("Launcher signing identity changed; remove the old launcher first");
        var entry = new Entry(previous == null ? UUID.randomUUID().toString().replace("-", "") : previous.id(),
                target, label, certificate);
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, ".export-", ".tmp");
        try {
            var json = new JSONObject().put("id", entry.id()).put("target", target.json())
                    .put("label", label).put("certificate", certificate);
            Files.write(temporary, json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Files.move(temporary, directory.resolve(entry.id() + ".json"),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (org.json.JSONException error) { throw new IOException(error); }
        finally { Files.deleteIfExists(temporary); }
        return entry;
    }

    void remove(String id) throws IOException { requireId(id); Files.deleteIfExists(directory.resolve(id + ".json")); }

    static String callerId(String host, String caller) {
        String prefix = host + ".launcher.e";
        if (caller == null || !caller.startsWith(prefix)) throw new SecurityException("Unregistered launcher caller");
        String id = caller.substring(prefix.length());
        requireId(id);
        return id;
    }

    private static void requireId(String id) {
        if (id == null || !id.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid launcher identity");
    }
}

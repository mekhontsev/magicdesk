package io.github.mekhontsev.magicdesk;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Shell-identity catalog. Names select independent stores, never a global current Linux. */
final class GuestEnvironmentLibrary {
    interface Images { String invoke(String... arguments) throws Exception; }
    enum SourceKind { REGISTRY, OCI, ROOTFS }
    record Environment(String name, String id, String image, String source, Path store) { }
    private final Path root;
    private final Images images;
    private final GuestOciRegistry registry;
    private final Consumer<String> progress;
    private final GuestImageFiles.DirectorySync directories;

    GuestEnvironmentLibrary(Path root, Images images, GuestOciRegistry registry, Consumer<String> progress) throws IOException {
        this(root, images, registry, progress, GuestImageFiles.DIRECTORY_SYNC);
    }

    GuestEnvironmentLibrary(Path root, Images images, GuestOciRegistry registry, Consumer<String> progress,
                            GuestImageFiles.DirectorySync directories) throws IOException {
        this.root = Files.createDirectories(root.toAbsolutePath().normalize()).toRealPath();
        this.images = images; this.registry = registry; this.progress = progress;
        this.directories = directories;
        Files.createDirectories(this.root);
        for (String child : List.of("names", "instances", "images", "layers", "cache", "work")) {
            Path directory = this.root.resolve(child);
            Files.createDirectories(directory);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid library directory: " + directory);
        }
    }

    Environment install(String source, SourceKind kind, String name) throws Exception {
        return install(source, kind, name, null);
    }

    Environment install(String source, SourceKind kind, String name, String resolver) throws Exception {
        validateName(name);
        try (Lock ignored = lock()) {
            requireNew(name);
            Path work = Files.createTempDirectory(root.resolve("work"), "install-");
            try {
                Path input;
                String image;
                if (kind == SourceKind.REGISTRY) {
                    GuestOciRegistry.Pulled pulled = registry.pull(source, root.resolve("cache"), work.resolve("layout"));
                    input = pulled.layout(); image = "oci-" + GuestImageFiles.hex(pulled.digest());
                } else if (kind == SourceKind.ROOTFS) {
                    Path archive = Path.of(source).toAbsolutePath();
                    GuestImageFiles.regular(archive);
                    input = work.resolve("rootfs");
                    try (var in = Files.newInputStream(archive, LinkOption.NOFOLLOW_LINKS); var out = Files.newOutputStream(input)) {
                        GuestImageFiles.copy(in, out, GuestImageFiles.BLOB_LIMIT, null);
                    }
                    image = "rootfs-" + GuestImageFiles.digest(input);
                } else {
                    input = Path.of(source).toAbsolutePath();
                    // Local layouts may have several tagged manifests; native selection owns validation.
                    image = "local-" + UUID.randomUUID();
                }
                Path base = root.resolve("images").resolve(image);
                if (!Files.exists(base, LinkOption.NOFOLLOW_LINKS)) {
                    progress.accept("Importing " + source);
                    if (kind == SourceKind.ROOTFS) images.invoke("rootfs", input.toString(), base.toString());
                    else if (kind == SourceKind.REGISTRY) images.invoke("import", input.toString(), base.toString(), "--preserve-ownership",
                            "--layers", root.resolve("layers").toString(), "--blobs", root.resolve("cache").toString());
                    else images.invoke("import", input.toString(), base.toString(), "--preserve-ownership",
                            "--layers", root.resolve("layers").toString());
                } else requireKind(base, "image");
                String id = UUID.randomUUID().toString();
                Path store = root.resolve("instances").resolve(id);
                images.invoke("create", base.toString(), store.toString());
                if (resolver != null) progress.accept(images.invoke("resolver", store.toString(), resolver).trim());
                return publish(name, id, image, source);
            } finally { discardWork(work); }
        }
    }

    void resolver(String name, String contents, boolean replace) throws Exception {
        try (Lock ignored = lock()) {
            Environment environment = resolve(name);
            progress.accept(replace ? images.invoke("resolver", environment.store().toString(), contents, "--replace")
                    : images.invoke("resolver", environment.store().toString(), contents));
        }
    }

    JSONArray catalog() throws Exception {
        JSONArray result = new JSONArray();
        for (Environment item : list()) result.put(new JSONObject().put("name", item.name()).put("id", item.id())
                .put("source", item.source()).put("store", item.store().toString()));
        return result;
    }

    JSONArray applications() throws Exception {
        JSONArray result = new JSONArray();
        for (Environment environment : list()) {
            var icons = new java.util.HashMap<String, String>();
            for (String line : images.invoke("applications", environment.store().toString()).split("\\n")) {
                if (line.isBlank()) continue;
                JSONObject item = new JSONObject(line);
                var shortcut = DesktopEntryFile.parseGuestApplication(item.getString("text"));
                // Hidden entries remain in the snapshot so their IDs can mask lower XDG directories.
                if (shortcut != null && TermuxIconCommand.valid(shortcut.icon)) {
                    String png = icons.get(shortcut.icon);
                    if (png == null) {
                        png = images.invoke("icon", environment.store().toString(), shortcut.icon).trim();
                        icons.put(shortcut.icon, png);
                    }
                    item.put("png", png);
                }
                item.put("environment", new JSONObject().put("name", environment.name()).put("id", environment.id())
                        .put("source", environment.source()).put("store", environment.store().toString()));
                if (result.length() >= 1024) throw new IOException("Too many guest applications");
                result.put(item);
            }
        }
        if (result.toString().length() > GuestImageFiles.JSON_LIMIT) throw new IOException("Guest catalog is too large");
        return result;
    }

    Environment restore(Path archive, String name) throws Exception {
        validateName(name);
        try (Lock ignored = lock()) {
            requireNew(name);
            String id = UUID.randomUUID().toString();
            images.invoke("restore", archive.toAbsolutePath().toString(), root.resolve("instances").resolve(id).toString());
            return publish(name, id, "", archive.toString());
        }
    }

    void backup(String name, Path destination) throws Exception {
        try (Lock ignored = lock()) {
            Environment environment = resolve(name);
            images.invoke("backup", environment.store().toString(), destination.toAbsolutePath().toString());
        }
    }

    void remove(String name) throws Exception {
        try (Lock ignored = lock()) {
            Environment environment = read(name);
            Path store = environment.store();
            if (Files.exists(store, LinkOption.NOFOLLOW_LINKS) || Files.exists(store.resolveSibling(".md-remove-" + store.getFileName()),
                    LinkOption.NOFOLLOW_LINKS)) images.invoke("remove", store.toString());
            Files.delete(recordPath(name));
            directories.sync(root.resolve("names"));
        }
    }

    Environment resolve(String name) throws Exception {
        Environment environment = read(name);
        requireKind(environment.store(), "instance");
        return environment;
    }

    List<Environment> list() throws Exception {
        List<Environment> result = new ArrayList<>();
        try (var paths = Files.list(root.resolve("names"))) {
            for (Path path : paths.sorted().toList()) {
                String filename = path.getFileName().toString();
                if (filename.startsWith(".publish-")) continue;
                if (!filename.endsWith(".json")) throw new IOException("Unknown catalog entry: " + filename);
                result.add(read(filename.substring(0, filename.length() - 5)));
            }
        }
        return List.copyOf(result);
    }

    JSONObject inspect(String name) throws Exception {
        Environment environment = resolve(name);
        return new JSONObject(images.invoke("inspect", environment.store().toString()))
                .put("name", environment.name()).put("store", environment.store().toString())
                .put("source", environment.source()).put("image", environment.image())
                .put("launches", launches(environment));
    }

    JSONArray launches(String name) throws Exception { return launches(resolve(name)); }

    private JSONArray launches(Environment environment) throws Exception {
        JSONArray result = new JSONArray();
        for (String line : images.invoke("launches", environment.store().toString()).split("\\n")) {
            if (!line.isBlank()) result.put(new JSONObject(line).put("environmentId", environment.id())
                    .put("name", environment.name()).put("store", environment.store().toString()));
        }
        return result;
    }

    void stop(String name, String id) throws Exception {
        if (id == null || !id.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid launch identity");
        images.invoke("stop", resolve(name).store().toString(), id);
    }

    /** Only this library's unreferenced resources are candidates; native leases still arbitrate live use. */
    void prune() throws Exception {
        try (Lock ignored = lock()) {
            for (String group : List.of("instances", "images", "layers")) {
                images.invoke("recover-staging", root.resolve(group).toString());
                finishRemovals(group);
            }
            // The catalog lock owns these Java-side inputs and unpublished name records.
            try (var files = Files.list(root.resolve("work"))) {
                for (Path file : files.toList()) if (file.getFileName().toString().startsWith("install-")) discardWork(file);
            }
            try (var files = Files.list(root.resolve("names"))) {
                for (Path file : files.toList()) if (file.getFileName().toString().startsWith(".publish-")) {
                    GuestImageFiles.regular(file); Files.delete(file);
                }
            }
            Set<Path> keptStores = new HashSet<>(), keptImages = new HashSet<>();
            for (Environment environment : list()) {
                keptStores.add(environment.store());
                if (!environment.image().isEmpty()) keptImages.add(root.resolve("images").resolve(environment.image()));
            }
            for (Path store : directories("instances")) {
                if (!keptStores.contains(store)) removeCandidate(store, false);
            }
            // Read every remaining dependency before removing any lower data. Unknown/corrupt stores abort GC.
            Set<Path> dependencies = dependencies(directories("instances"));
            for (Path base : directories("images")) {
                if (!keptImages.contains(base) && !dependencies.contains(base)) removeCandidate(base, false);
            }
            List<Path> owners = new ArrayList<>(directories("instances"));
            owners.addAll(directories("images"));
            dependencies = dependencies(owners);
            for (Path layer : directories("layers")) {
                if (!dependencies.contains(layer)) removeCandidate(layer, true);
            }
            // Downloads are independently verified inputs, not live backing files.
            try (var files = Files.list(root.resolve("cache"))) {
                for (Path file : files.toList()) {
                    String name = file.getFileName().toString();
                    if (name.matches("[0-9a-f]{64}") || name.startsWith(".download-")) {
                        GuestImageFiles.regular(file); Files.delete(file);
                    }
                }
            }
        }
    }

    private void finishRemovals(String group) throws Exception {
        try (var paths = Files.list(root.resolve(group))) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                if (!name.startsWith(".md-remove-")) continue;
                Path original = path.resolveSibling(name.substring(".md-remove-".length()));
                if (Files.exists(original, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Conflicting removal journal: " + original);
                images.invoke(group.equals("layers") ? "remove-layer" : "remove", original.toString());
            }
        }
    }

    private void removeCandidate(Path path, boolean layer) throws Exception {
        progress.accept("Removing unused " + path.getFileName());
        // A busy resource is not a success. The user can retry prune after its owner exits.
        images.invoke(layer ? "remove-layer" : "remove", path.toString());
    }

    private Set<Path> dependencies(List<Path> stores) throws Exception {
        Set<Path> result = new HashSet<>();
        for (Path store : stores) {
            JSONArray sources = new JSONObject(images.invoke("inspect", store.toString())).getJSONArray("sources");
            for (int i = 0; i < sources.length(); ++i) {
                Path objects = Path.of(sources.getJSONObject(i).getString("path")).toAbsolutePath().normalize();
                result.add(objects.getParent());
            }
        }
        return result;
    }

    private List<Path> directories(String group) throws IOException {
        try (var paths = Files.list(root.resolve(group))) {
            List<Path> result = paths.filter(path -> !path.getFileName().toString().matches("\\.md-image-[0-9a-f]{32}")).sorted().toList();
            for (Path path : result) if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Invalid library resource: " + path);
            return result;
        }
    }

    private Environment publish(String name, String id, String image, String source) throws Exception {
        JSONObject record = new JSONObject().put("format", 1).put("name", name).put("id", id)
                .put("image", image).put("source", source);
        GuestImageFiles.write(recordPath(name), record.toString(), directories);
        return read(name);
    }

    private Environment read(String name) throws Exception {
        JSONObject record = new JSONObject(GuestImageFiles.read(recordPath(name)));
        if (record.getInt("format") != 1 || !name.equals(record.getString("name"))) throw new IOException("Invalid environment record");
        String id = record.getString("id"), image = record.getString("image");
        if (!UUID.fromString(id).toString().equals(id) || !(image.isEmpty()
                || image.matches("(?:oci|rootfs)-[0-9a-f]{64}") || image.matches("local-[0-9a-f-]{36}")))
            throw new IOException("Invalid environment storage identity");
        return new Environment(name, id, image, record.getString("source"), root.resolve("instances").resolve(id));
    }

    private void requireKind(Path store, String expected) throws Exception {
        if (!expected.equals(new JSONObject(images.invoke("inspect", store.toString())).getString("kind")))
            throw new IOException("Expected guest " + expected + ": " + store);
    }

    private void requireNew(String name) throws IOException {
        if (Files.exists(recordPath(name), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Environment already exists: " + name);
    }

    static void validateName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))
            throw new IllegalArgumentException("Environment name must be 1-64 letters, digits, '.', '_' or '-', starting with a letter or digit");
    }

    private Path recordPath(String name) { validateName(name); return root.resolve("names").resolve(name + ".json"); }

    private Lock lock() throws IOException {
        FileChannel channel = FileChannel.open(root.resolve("library.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("Guest library is busy with another operation");
            return new Lock(channel, lock);
        } catch (IOException | OverlappingFileLockException error) {
            channel.close(); throw new IOException("Guest library is busy or unavailable", error);
        }
    }

    private record Lock(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override public void close() throws IOException { try { lock.release(); } finally { channel.close(); } }
    }

    private static void discardWork(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}

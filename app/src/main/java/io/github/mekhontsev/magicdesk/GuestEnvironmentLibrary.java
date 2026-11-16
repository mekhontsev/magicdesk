package io.github.mekhontsev.magicdesk;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Presentation adapter to the native catalog; never reads or mutates its records. */
final class GuestEnvironmentLibrary {
    interface Images { String invoke(String... arguments) throws Exception; }
    enum SourceKind { REGISTRY, OCI, ROOTFS }
    record Environment(String name, String id, String image, String source, Path store) { }
    private final Path root;
    private final Images images;
    private final Consumer<String> progress;

    GuestEnvironmentLibrary(Path root, Images images, Consumer<String> progress) {
        this.root = root.toAbsolutePath().normalize();
        this.images = images;
        this.progress = progress;
    }

    private String invoke(String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("library", root.toString()));
        command.addAll(List.of(arguments));
        return images.invoke(command.toArray(String[]::new));
    }

    Environment install(String source, SourceKind kind, String name, String resolver) throws Exception {
        validateName(name);
        var arguments = new ArrayList<>(List.of("install", kind.name().toLowerCase(Locale.ROOT), source, name));
        if (resolver != null) arguments.addAll(List.of("--resolver", resolver));
        progress.accept(invoke(arguments.toArray(String[]::new)));
        return resolve(name);
    }

    void resolver(String name, String contents, boolean replace) throws Exception {
        validateName(name);
        progress.accept(replace ? invoke("resolver", name, contents, "--replace")
                : invoke("resolver", name, contents));
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
            var owner = new JSONObject().put("name", environment.name()).put("id", environment.id())
                    .put("source", environment.source()).put("store", environment.store().toString());
            List<JSONObject> records;
            try {
                records = applicationRecords(environment);
            } catch (Exception error) {
                result.put(new JSONObject().put("environment", owner).put("error", error.toString()));
                continue;
            }
            for (var item : records) result.put(item.put("environment", owner));
            if (result.length() > 1024) throw new IOException("Too many guest applications");
        }
        if (result.toString().length() > GuestImageFiles.JSON_LIMIT) throw new IOException("Guest catalog is too large");
        return result;
    }

    private List<JSONObject> applicationRecords(Environment environment) throws Exception {
        var result = new java.util.ArrayList<JSONObject>();
        String text = images.invoke("applications", environment.store().toString());
        if (text.length() > GuestImageFiles.JSON_LIMIT) throw new IOException("Guest catalog is too large");
        for (String line : text.split("\\n")) {
            if (line.isBlank()) continue;
            if (result.size() >= 1024) throw new IOException("Too many guest applications");
            result.add(new JSONObject(line));
        }
        return result;
    }

    JSONArray icons(List<String> references) throws Exception {
        if (references.isEmpty() || references.size() > TermuxIconCommand.BATCH_SIZE)
            throw new IllegalArgumentException("Invalid icon batch size");
        var keys = new java.util.LinkedHashSet<ApplicationIconKey>();
        for (String reference : references) {
            var key = ApplicationIconKey.guest(reference);
            if (key == null) throw new IllegalArgumentException("Invalid guest icon reference");
            keys.add(key);
        }
        var environments = list();
        var records = new java.util.HashMap<String, java.util.Map<String, String>>();
        var pixels = new java.util.HashMap<String, String>();
        JSONArray result = new JSONArray();
        for (var key : keys) {
            var item = new JSONObject().put("id", key.guestReference());
            try {
                var environment = environments.stream().filter(value -> value.id().equals(key.owner())).findFirst().orElse(null);
                if (environment != null) {
                    var names = records.get(key.owner());
                    if (names == null) {
                        names = new java.util.LinkedHashMap<>();
                        for (var entry : applicationRecords(environment)) {
                            String id = ApplicationIconKey.desktopId(entry.getString("path"));
                            if (names.containsKey(id)) continue;
                            // The first XDG entry owns the ID, including a Hidden entry with no artwork.
                            var shortcut = DesktopEntryFile.parseGuestApplication(entry.getString("text"));
                            names.put(id, shortcut == null ? "" : shortcut.icon);
                        }
                        records.put(key.owner(), names);
                    }
                    String name = names.get(key.name());
                    if (name != null && TermuxIconCommand.valid(name)) {
                        String source = key.owner() + ":" + name;
                        String png = pixels.get(source);
                        if (png == null) {
                            png = images.invoke("icon", environment.store().toString(), name).trim();
                            if (png.length() > 32768 || (png.length() & 1) != 0 || !png.matches("[0-9a-f]*"))
                                throw new IOException("Invalid guest icon reply");
                            pixels.put(source, png);
                        }
                        item.put("png", png);
                    }
                }
            } catch (Exception error) {
                item.put("error", error.getClass().getSimpleName());
            }
            result.put(item);
        }
        return result;
    }

    Environment restore(Path archive, String name) throws Exception {
        validateName(name);
        progress.accept(invoke("restore", archive.toAbsolutePath().toString(), name));
        return resolve(name);
    }

    void backup(String name, Path destination) throws Exception {
        validateName(name);
        progress.accept(invoke("backup", name, destination.toAbsolutePath().toString()));
    }

    void remove(String name) throws Exception {
        validateName(name);
        progress.accept(invoke("remove", name));
    }

    Environment resolve(String name) throws Exception {
        validateName(name);
        return environment(new JSONObject(invoke("resolve", name)));
    }

    List<Environment> list() throws Exception {
        // An unused executor has no library yet. Listing must not create it.
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) return List.of();
        JSONArray items = new JSONArray(invoke("list"));
        var result = new ArrayList<Environment>();
        for (int i = 0; i < items.length(); ++i) result.add(environment(items.getJSONObject(i)));
        return List.copyOf(result);
    }

    private static Environment environment(JSONObject item) throws Exception {
        return new Environment(item.getString("name"), item.getString("id"), item.getString("image"),
                item.getString("source"), Path.of(item.getString("store")));
    }

    JSONObject inspect(String name) throws Exception {
        Environment environment = resolve(name);
        JSONObject information = new JSONObject(images.invoke("inspect", environment.store().toString()));
        if (!"instance".equals(information.getString("kind")))
            throw new IOException("Expected guest instance: " + environment.store());
        return information.put("name", environment.name()).put("store", environment.store().toString())
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

    void prune() throws Exception { progress.accept(invoke("prune")); }

    static void validateName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}"))
            throw new IllegalArgumentException("Environment name must be 1-64 letters, digits, '.', '_' or '-', starting with a letter or digit");
    }
}

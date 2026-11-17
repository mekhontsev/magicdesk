package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class GuestEnvironmentLibraryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static final class Images implements GuestEnvironmentLibrary.Images {
        final List<List<String>> commands = new ArrayList<>();
        final Map<String, JSONObject> records = new LinkedHashMap<>();
        final Map<Path, String> applications = new HashMap<>();
        final Map<String, String> icons = new HashMap<>();
        Exception failure;
        @Override public String invoke(String... args) throws Exception {
            commands.add(List.of(args));
            if (failure != null) throw failure;
            if (args[0].equals("library")) {
                String operation = args[2];
                if (operation.equals("list")) return new JSONArray(records.values()).toString();
                if (operation.equals("resolve")) return records.get(args[3]).toString();
                if (operation.equals("remove")) { records.remove(args[3]); return ""; }
                if (operation.equals("prune") || operation.equals("backup") || operation.equals("resolver")) return "";
                if (operation.equals("install") || operation.equals("restore")) {
                    String source = args[operation.equals("install") ? 4 : 3];
                    String name = args[operation.equals("install") ? 5 : 4];
                    records.put(name, new JSONObject().put("name", name).put("id", UUID.randomUUID().toString())
                            .put("image", "").put("source", source)
                            .put("store", Path.of(args[1], "instances", UUID.randomUUID().toString()).toString()));
                    return "Native progress\n";
                }
                throw new AssertionError(operation);
            }
            Path path = Path.of(args[1]);
            if (args[0].equals("applications")) {
                String value = applications.getOrDefault(path, "");
                if (value.equals("unavailable")) throw new IOException("Unreadable environment");
                return value;
            }
            if (args[0].equals("icon")) {
                if (args[2].equals("broken")) throw new IOException("Unreadable artwork");
                return icons.getOrDefault(path + ":" + args[2], "");
            }
            if (args[0].equals("stop") || args[0].equals("launches")) return "";
            if (args[0].equals("inspect")) return "{\"kind\":\"instance\"}";
            throw new AssertionError(args[0]);
        }
    }

    private GuestEnvironmentLibrary library(Path root, Images images) {
        return new GuestEnvironmentLibrary(root, images, message -> { });
    }

    @Test public void delegatesCatalogMutationWithoutTouchingNativeRecords() throws Exception {
        Path root = temporary.newFolder().toPath();
        Images images = new Images();
        var library = library(root, images);
        String source = "/archive with ' spaces.tar";
        var environment = library.install(source, GuestEnvironmentLibrary.SourceKind.ROOTFS, "work", "nameserver 192.0.2.1\n");
        assertEquals(List.of("library", root.toString(), "install", "rootfs", source, "work",
                "--resolver", "nameserver 192.0.2.1\n"), images.commands.get(0));
        assertEquals(List.of("library", root.toString(), "resolve", "work"), images.commands.get(1));
        assertEquals(source, environment.source());
        assertNotEquals(environment.id(), environment.store().getFileName().toString());
        assertEquals(environment, library.list().get(0));
        library.backup("work", Path.of("/backup with spaces.tar.zst"));
        assertEquals(List.of("library", root.toString(), "backup", "work", "/backup with spaces.tar.zst"),
                images.commands.get(images.commands.size() - 1));
        library.resolver("work", "nameserver 192.0.2.2\n", true);
        assertEquals(List.of("library", root.toString(), "resolver", "work", "nameserver 192.0.2.2\n", "--replace"),
                images.commands.get(images.commands.size() - 1));
        library.remove("work");
        library.prune();
        assertEquals(List.of("library", root.toString(), "prune"), images.commands.get(images.commands.size() - 1));
        try (var files = Files.list(root)) { assertEquals(0, files.count()); }
    }

    @Test public void unusedExecutorHasNoCatalogSideEffects() throws Exception {
        Path root = temporary.getRoot().toPath().resolve("unused");
        Images images = new Images();
        var library = library(root, images);
        assertTrue(library.list().isEmpty());
        assertEquals(0, library.catalog().length());
        assertFalse(Files.exists(root));
        assertTrue(images.commands.isEmpty());
    }

    @Test public void nativeErrorsAndLaunchIdentityAreRetained() throws Exception {
        Path root = temporary.newFolder().toPath();
        Images images = new Images();
        var library = library(root, images);
        var environment = library.restore(Path.of("/saved.tar.zst"), "work");
        library.stop("work", "a".repeat(32));
        assertEquals(List.of("stop", environment.store().toString(), "a".repeat(32)),
                images.commands.get(images.commands.size() - 1));
        assertEquals(environment.store().toString(), library.inspect("work").getString("store"));
        images.failure = new IOException("native library busy");
        assertSame(images.failure, assertThrows(IOException.class, () -> library.remove("work")));
        assertSame(images.failure, assertThrows(IOException.class, library::list));
        assertSame(images.failure, assertThrows(IOException.class, library::prune));
        for (String name : List.of("", "../oops", "-option", "a/b", "a\n", ".", ".."))
            assertThrows(IllegalArgumentException.class, () -> library.resolve(name));
    }

    @Test public void namedLaunchRetainsArgumentsInOriginalShell() {
        String script = GuestRuntimeCommand.BODY;
        assertTrue(script.contains("md_store=$(md_library resolve \"${1-}\")"));
        assertTrue(script.contains("exec \"$md_guest/libshroot_cli.so\" \"$md_action\" \"$md_store\" \"$@\""));
        // Only quoted preparation assignments are evaluated, never guest argv.
        assertEquals(1, script.split("eval ", -1).length - 1);
        assertTrue(script.contains("eval \"$md_prepared\""));
        assertTrue(script.contains("md_launch \"$md_guest/libshroot_cli.so\" \"$md_action\" \"$md_store\" \"$@\""));
        assertFalse(script.contains("exec /system/bin/app_process"));
    }

    private static String desktop(String path, String icon, boolean hidden) throws Exception {
        return new JSONObject().put("path", path).put("text", "[Desktop Entry]\nType=Application\nName=Test\nExec=test\nIcon="
                + icon + "\nHidden=" + hidden + "\n").toString() + "\n";
    }

    @Test public void metadataDoesNotFetchArtworkAndIconsResolveOnlyRequestedImmutableOwners() throws Exception {
        Path root = temporary.newFolder().toPath(), archive = temporary.newFile().toPath();
        Images images = new Images(); var library = library(root, images);
        var a = library.restore(archive, "first"); var b = library.restore(archive, "second");
        images.applications.put(a.store(), desktop("/usr/share/applications/editor.desktop", "editor", false));
        images.applications.put(b.store(), desktop("/usr/share/applications/editor.desktop", "editor", false));
        images.icons.put(a.store() + ":editor", "aa"); images.icons.put(b.store() + ":editor", "bb");
        assertEquals(2, library.applications().length());
        assertFalse(images.commands.stream().anyMatch(c -> c.get(0).equals("icon")));
        images.commands.clear();
        String key = "guest:" + a.id() + ":editor.desktop";
        assertEquals("aa", library.icons(List.of(key)).getJSONObject(0).getString("png"));
        assertFalse(images.commands.stream().anyMatch(c -> c.contains(b.store().toString())));
        library.remove("first"); library.restore(archive, "first");
        assertFalse(library.icons(List.of(key)).getJSONObject(0).has("png"));
        assertThrows(IllegalArgumentException.class, () -> library.icons(List.of("guest:../bad:file.desktop")));
        assertThrows(IllegalArgumentException.class, () -> library.icons(java.util.Collections.nCopies(5, key)));
    }

    @Test public void hiddenEntriesMaskLowerArtworkAndOneFailureDoesNotDiscardOtherIcons() throws Exception {
        Path root = temporary.newFolder().toPath(), archive = temporary.newFile().toPath();
        Images images = new Images(); var library = library(root, images);
        var a = library.restore(archive, "first");
        images.applications.put(a.store(), desktop("/root/.local/share/applications/hidden.desktop", "editor", true)
                + desktop("/usr/share/applications/hidden.desktop", "editor", false)
                + desktop("/usr/share/applications/bad.desktop", "broken", false)
                + desktop("/usr/share/applications/good.desktop", "editor", false)
                + desktop("/usr/share/applications/also.desktop", "editor", false));
        images.icons.put(a.store() + ":editor", "aabb");
        images.commands.clear();
        String prefix = "guest:" + a.id() + ":";
        var result = library.icons(List.of(prefix + "hidden.desktop", prefix + "bad.desktop", prefix + "good.desktop", prefix + "also.desktop"));
        assertFalse(result.getJSONObject(0).has("png"));
        assertTrue(result.getJSONObject(1).has("error"));
        assertEquals("aabb", result.getJSONObject(2).getString("png"));
        assertEquals("aabb", result.getJSONObject(3).getString("png"));
        assertEquals(1, images.commands.stream().filter(c -> c.get(0).equals("applications")).count());
        assertEquals(1, images.commands.stream().filter(c -> c.contains("editor")).count());
    }

    @Test public void unavailableEnvironmentDoesNotHideHealthyApplicationsOrArtwork() throws Exception {
        Path root = temporary.newFolder().toPath(), archive = temporary.newFile().toPath();
        Images images = new Images(); var library = library(root, images);
        var bad = library.restore(archive, "bad"); var good = library.restore(archive, "good");
        images.applications.put(bad.store(), "unavailable");
        images.applications.put(good.store(), desktop("/usr/share/applications/editor.desktop", "editor", false));
        images.icons.put(good.store() + ":editor", "abcd");
        var records = library.applications();
        assertEquals(2, records.length());
        var byOwner = new HashMap<String, JSONObject>();
        for (int i = 0; i < records.length(); ++i) {
            var record = records.getJSONObject(i);
            byOwner.put(record.getJSONObject("environment").getString("id"), record);
        }
        assertTrue(byOwner.get(bad.id()).getString("error").contains("Unreadable environment"));
        var healthy = byOwner.get(good.id());
        assertFalse(healthy.has("error"));
        assertEquals(good.store().toString(), healthy.getJSONObject("environment").getString("store"));
        assertEquals("/usr/share/applications/editor.desktop", healthy.getString("path"));
        assertTrue(healthy.getString("text").contains("Icon=editor"));
        var icons = library.icons(List.of("guest:" + bad.id() + ":editor.desktop", "guest:" + good.id() + ":editor.desktop"));
        assertTrue(icons.getJSONObject(0).has("error"));
        assertEquals("abcd", icons.getJSONObject(1).getString("png"));
    }
}

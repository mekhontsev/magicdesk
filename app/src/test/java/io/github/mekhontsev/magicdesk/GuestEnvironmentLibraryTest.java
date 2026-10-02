package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GuestEnvironmentLibraryTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static final class Images implements GuestEnvironmentLibrary.Images {
        final Map<Path, JSONObject> stores = new HashMap<>();
        final List<List<String>> commands = new ArrayList<>();
        boolean busy;
        @Override public String invoke(String... args) throws Exception {
            commands.add(List.of(args));
            Path path = Path.of(args[1]);
            switch (args[0]) {
                case "rootfs", "import", "restore" -> {
                    Path target = Path.of(args[2]); Files.createDirectory(target);
                    stores.put(target, new JSONObject().put("kind", args[0].equals("restore") ? "instance" : "image")
                            .put("sources", new JSONArray()));
                }
                case "create" -> {
                    Path target = Path.of(args[2]); Files.createDirectory(target);
                    stores.put(target, new JSONObject().put("kind", "instance").put("sources", new JSONArray()
                            .put(new JSONObject().put("path", path.resolve("objects").toString()))));
                }
                case "inspect" -> {
                    if (!stores.containsKey(path)) throw new IOException("Missing store");
                    return stores.get(path).toString();
                }
                case "remove", "remove-layer" -> {
                    if (busy) throw new IOException("Busy");
                    Files.delete(path); stores.remove(path);
                }
                case "backup" -> { if (busy) throw new IOException("Busy"); Files.writeString(Path.of(args[2]), "backup"); }
                default -> throw new AssertionError(args[0]);
            }
            return "";
        }
    }

    private GuestEnvironmentLibrary library(Path root, Images images) throws Exception {
        return new GuestEnvironmentLibrary(root, images, new GuestOciRegistry(message -> { }), message -> { });
    }

    @Test public void instancesShareImageButKeepIndependentStablePaths() throws Exception {
        Path root = temporary.newFolder().toPath(), archive = temporary.newFile().toPath();
        Files.writeString(archive, "rootfs input");
        Images images = new Images(); var library = library(root, images);
        var a = library.install(archive.toString(), GuestEnvironmentLibrary.SourceKind.ROOTFS, "work");
        var b = library.install(archive.toString(), GuestEnvironmentLibrary.SourceKind.ROOTFS, "other");
        assertEquals(a.image(), b.image()); assertNotEquals(a.store(), b.store());
        assertEquals(1, images.commands.stream().filter(c -> c.get(0).equals("rootfs")).count());
        assertEquals(2, library.list().size());
        library.remove("work");
        var c = library.install(archive.toString(), GuestEnvironmentLibrary.SourceKind.ROOTFS, "work");
        assertNotEquals(a.store(), c.store());
        assertEquals(b, library.resolve("other"));
    }

    @Test public void failedOrBusyRemovalKeepsNameAndPruneKeepsDependencies() throws Exception {
        Path root = temporary.newFolder().toPath(), archive = temporary.newFile().toPath();
        Images images = new Images(); var library = library(root, images);
        var environment = library.install(archive.toString(), GuestEnvironmentLibrary.SourceKind.ROOTFS, "work");
        images.busy = true;
        assertThrows(IOException.class, () -> library.remove("work"));
        assertEquals(environment, library.resolve("work"));
        library.prune();
        assertTrue(Files.exists(root.resolve("images").resolve(environment.image())));
        images.busy = false; library.remove("work"); library.prune();
        assertTrue(images.stores.isEmpty());
    }

    @Test public void restoreHasNoLowerImageAndDuplicateNamesAreRejected() throws Exception {
        Path root = temporary.newFolder().toPath(), archive = temporary.newFile().toPath();
        Images images = new Images(); var library = library(root, images);
        var restored = library.restore(archive, "saved");
        assertEquals("", restored.image());
        assertThrows(IOException.class, () -> library.restore(archive, "saved"));
        assertEquals(1, library.list().size());
        for (String name : List.of("", "../oops", "-option", "a/b", "a\n", ".", ".."))
            assertThrows(IllegalArgumentException.class, () -> library.resolve(name));
    }

    @Test public void catalogMutationFailsExplicitlyWhenAnotherOwnerHoldsLock() throws Exception {
        Path root = temporary.newFolder().toPath();
        var library = library(root, new Images());
        try (var channel = FileChannel.open(root.resolve("library.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var ignored = channel.lock()) {
            assertThrows(IOException.class, library::prune);
            assertTrue(library.list().isEmpty());
        }
    }

    @Test public void namedLaunchRetainsArgumentsInOriginalShell() {
        String script = GuestRuntimeCommand.SCRIPT;
        assertTrue(script.contains("md_store=$(md_library resolve \"${1-}\")"));
        assertTrue(script.contains("exec \"$md_guest/libmagicdesk_guest_image.so\" \"$md_action\" \"$md_store\" \"$@\""));
        assertFalse(script.contains("eval "));
        assertFalse(script.contains("exec /system/bin/app_process"));
    }
}

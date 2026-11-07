package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public final class GuestLibraryLocationTest {
    @Test public void nativeOwnerAdoptsOnlyTheDefaultLibrary() throws Exception {
        Path root = Files.createTempDirectory("library-location-");
        var paths = new RuntimeDataPaths(root);
        var calls = new ArrayList<List<String>>();
        GuestEnvironmentLibrary.Images images = args -> { calls.add(List.of(args)); return ""; };
        try {
            Path old = Files.createDirectories(root.resolve("no_backup/guest-environments"));
            GuestLibraryLocation.prepare(root.resolve("custom"), paths, 10647, images);
            assertTrue(calls.isEmpty());
            GuestLibraryLocation.prepare(paths.linux(), paths, 10647, images);
            assertEquals(List.of(List.of("relocate-library", old.toString(), paths.linux().toString())), calls);
            assertFalse(Files.exists(paths.linux()));
            Files.createDirectory(paths.linux());
            assertThrows(java.io.IOException.class, () -> GuestLibraryLocation.prepare(paths.linux(), paths, 10647, images));
            assertEquals(1, calls.size());
        } finally { FileTreeDeletion.deleteIfExists(root); }
    }
}

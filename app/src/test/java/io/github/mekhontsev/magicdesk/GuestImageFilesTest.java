package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GuestImageFilesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    // Publication fixtures model the directory barrier; its POSIX adapter is tested separately.
    static void assertDirectorySync(Path directory) {
        assertTrue(Files.isDirectory(directory));
    }

    @Test public void directoryBarrierObservesPublishedBytes() throws Exception {
        Path root = temporary.newFolder().toPath(), target = root.resolve("record");
        List<Path> synced = new ArrayList<>();
        GuestImageFiles.write(target, "published", directory -> {
            assertEquals("published", Files.readString(target));
            synced.add(directory);
        });
        assertEquals(List.of(root), synced);
        try (var files = Files.list(root)) { assertEquals(List.of(target), files.toList()); }
    }

    @Test public void directoryBarrierFailureIsNotReportedAsSuccessOrRolledBack() throws Exception {
        Path root = temporary.newFolder().toPath(), target = root.resolve("record");
        IOException failure = new IOException("directory sync failed");
        assertSame(failure, assertThrows(IOException.class, () -> GuestImageFiles.write(target, "published",
                directory -> { throw failure; })));
        assertEquals("published", Files.readString(target));
        try (var files = Files.list(root)) { assertEquals(List.of(target), files.toList()); }
    }

    @Test public void productionDirectoryBarrierUsesPosixFilesystem() throws Exception {
        Path root = temporary.newFolder().toPath();
        assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"));
        GuestImageFiles.DIRECTORY_SYNC.sync(root);
        assertThrows(IOException.class, () -> GuestImageFiles.DIRECTORY_SYNC.sync(root.resolve("missing")));
    }
}

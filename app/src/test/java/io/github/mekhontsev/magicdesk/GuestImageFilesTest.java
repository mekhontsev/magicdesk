package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GuestImageFilesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void readsBoundedNativeReplies() throws Exception {
        Path file = temporary.newFile().toPath();
        Files.writeString(file, "{\"name\":\"example\"}\n");
        assertEquals("{\"name\":\"example\"}\n", GuestImageFiles.read(file));
        Files.write(file, new byte[GuestImageFiles.JSON_LIMIT + 1]);
        assertThrows(IOException.class, () -> GuestImageFiles.read(file));
    }

    @Test public void refusesMissingFilesDirectoriesAndSymbolicLinks() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path target = temporary.newFile().toPath();
        Path link = root.resolve("link");
        Files.createSymbolicLink(link, target);
        for (Path path : new Path[]{root, link, root.resolve("missing")})
            assertThrows(IOException.class, () -> GuestImageFiles.read(path));
    }
}

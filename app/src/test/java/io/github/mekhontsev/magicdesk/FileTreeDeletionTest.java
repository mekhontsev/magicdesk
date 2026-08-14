package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FileTreeDeletionTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void deletingSymlinkDoesNotDeleteTarget() throws IOException {
        final Path targetDirectory = temporary.newFolder("target").toPath();
        final Path targetFile = Files.writeString(
                targetDirectory.resolve("keep.txt"), "keep");
        final Path link = targetDirectory.resolveSibling("link");
        Files.createSymbolicLink(link, targetDirectory);

        FileTreeDeletion.delete(link, null);

        assertFalse(Files.exists(link));
        assertTrue(Files.exists(targetFile));
    }

    @Test
    public void recursivelyDeletesOrdinaryDirectory() throws IOException {
        final Path directory = temporary.newFolder("tree").toPath();
        Files.createDirectories(directory.resolve("a/b"));
        Files.writeString(directory.resolve("a/b/file.txt"), "content");

        FileTreeDeletion.delete(directory, null);

        assertFalse(Files.exists(directory));
    }

    @Test
    public void cancellationRunsBeforeMutation() throws IOException {
        final Path file = temporary.newFile("keep.txt").toPath();

        assertThrows(IOException.class, () -> FileTreeDeletion.delete(
                file,
                () -> {
                    throw new IOException("cancelled");
                }));
        assertTrue(Files.exists(file));
    }
}

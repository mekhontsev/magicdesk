package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ShellFileSystemTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void conflictSuffixIsAddedBeforeExtension() throws IOException {
        final Path directory = temporary.newFolder("files").toPath();
        final Path requested = Files.createFile(
                directory.resolve("report.txt"));
        Files.createFile(directory.resolve("report (2).txt"));

        assertEquals(
                directory.resolve("report (3).txt"),
                ShellFileSystem.availableTarget(requested));
    }

    @Test
    public void unusedTargetIsUnchanged() throws IOException {
        final Path directory = temporary.newFolder("unused").toPath();
        final Path requested = directory.resolve("report.txt");

        assertEquals(requested, ShellFileSystem.availableTarget(requested));
    }

    @Test
    public void descendingNameSortKeepsDirectoriesBeforeFiles() {
        final List<ShellFileInfo> entries = new ArrayList<>(Arrays.asList(
                entry("alpha.txt", false, 0L, 10L),
                entry("beta", true, 0L, 0L),
                entry("zeta.txt", false, 0L, 20L),
                entry("gamma", true, 0L, 0L)));

        entries.sort(ShellFileSystem.comparator(
                ShellFileSystem.SORT_NAME, false));

        assertEquals("gamma", entries.get(0).name);
        assertEquals("beta", entries.get(1).name);
        assertEquals("zeta.txt", entries.get(2).name);
        assertEquals("alpha.txt", entries.get(3).name);
    }

    @Test
    public void modifiedAndSizeSortUseRequestedDirection() {
        final ShellFileInfo olderSmall = entry(
                "older.txt", false, 10L, 1L);
        final ShellFileInfo newerLarge = entry(
                "newer.txt", false, 20L, 2L);
        final List<ShellFileInfo> entries = new ArrayList<>(Arrays.asList(
                newerLarge, olderSmall));

        entries.sort(ShellFileSystem.comparator(
                ShellFileSystem.SORT_MODIFIED, true));
        assertEquals(olderSmall, entries.get(0));

        entries.sort(ShellFileSystem.comparator(
                ShellFileSystem.SORT_SIZE, false));
        assertEquals(newerLarge, entries.get(0));
    }

    private static ShellFileInfo entry(
            final String name,
            final boolean directory,
            final long modified,
            final long size) {
        return new ShellFileInfo(
                "/tmp/" + name,
                name,
                directory ? "vnd.android.document/directory"
                        : "text/plain",
                "",
                modified,
                size,
                1L,
                2L,
                2000,
                2000,
                directory ? 040755 : 0100644,
                directory,
                false,
                true,
                true,
                directory,
                false);
    }
}

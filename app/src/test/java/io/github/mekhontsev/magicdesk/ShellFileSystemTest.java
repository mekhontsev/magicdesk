package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

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
    public void conflictSelectionIsNotLimitedToOneDirectoryPage() throws IOException {
        final Path directory = temporary.newFolder("many-conflicts").toPath();
        final Path requested = Files.writeString(directory.resolve("report.txt"), "original");
        for (int suffix = 2; suffix <= 510; suffix++) {
            Files.createFile(directory.resolve("report (" + suffix + ").txt"));
        }

        assertEquals(directory.resolve("report (511).txt"),
                ShellFileSystem.availableTarget(requested));
        assertEquals("original", Files.readString(requested));
    }

    @Test
    public void invalidImportNameUsesAvailableFallbackOnTheFilesystem() throws IOException {
        final Path directory = temporary.newFolder("fallback").toPath();
        Files.createFile(directory.resolve("Imported file"));
        final Path requested = directory.resolve(ContentUriTransfer.safeFileName("../outside.txt"));

        assertEquals(directory.resolve("Imported file (2)"),
                ShellFileSystem.availableTarget(requested));
    }

    @Test
    public void nameConflictFollowsTheDestinationFilesystemsCaseRules() throws IOException {
        final Path directory = temporary.newFolder("case-rules").toPath();
        final Path existing = Files.writeString(directory.resolve("Report.txt"), "original");
        final Path requested = directory.resolve("report.txt");
        final boolean conflicts = Files.exists(requested);

        assertEquals(conflicts ? directory.resolve("report (2).txt") : requested,
                ShellFileSystem.availableTarget(requested));
        assertEquals("original", Files.readString(existing));
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

    @Test
    public void tiedMetadataHasStableOrderAcrossDirectoryPages() {
        final List<ShellFileInfo> ordered = new ArrayList<>();
        for (int index = 0; index < 1100; index++) {
            ordered.add(entry(String.format(Locale.ROOT, "file-%04d.txt", index), false, 7L, 0L));
        }
        for (final int sort : new int[] {ShellFileSystem.SORT_MODIFIED, ShellFileSystem.SORT_SIZE}) {
            final List<ShellFileInfo> firstRead = new ArrayList<>(ordered);
            final List<ShellFileInfo> nextRead = new ArrayList<>(ordered);
            Collections.shuffle(firstRead, new Random(1));
            Collections.shuffle(nextRead, new Random(2));
            firstRead.sort(ShellFileSystem.comparator(sort, true));
            nextRead.sort(ShellFileSystem.comparator(sort, true));
            final List<ShellFileInfo> pages = new ArrayList<>(firstRead.subList(0, 500));
            pages.addAll(nextRead.subList(500, nextRead.size()));

            assertEquals(ordered, pages);
            nextRead.sort(ShellFileSystem.comparator(sort, false));
            Collections.reverse(nextRead);
            assertEquals(ordered, nextRead);
        }
    }

    @Test
    public void namesDifferingOnlyByCaseHaveAnUnambiguousOrder() {
        final var lower = entry("alpha", false, 0L, 0L);
        final var upper = entry("Alpha", false, 0L, 0L);
        final var directory = entry("zeta", true, 0L, 0L);
        for (final int sort : new int[] {
                ShellFileSystem.SORT_NAME, ShellFileSystem.SORT_MODIFIED, ShellFileSystem.SORT_SIZE}) {
            final List<ShellFileInfo> entries = new ArrayList<>(List.of(lower, directory, upper));
            entries.sort(ShellFileSystem.comparator(sort, true));
            assertEquals(List.of(directory, upper, lower), entries);
            entries.sort(ShellFileSystem.comparator(sort, false));
            assertEquals(List.of(directory, lower, upper), entries);
        }
    }

    @Test
    public void searchMatchesNamesCaseInsensitively() {
        assertTrue(ShellFileSystem.matchesSearchQuery(
                "MagicDesk Report.txt", "desk"));
        assertFalse(ShellFileSystem.matchesSearchQuery(
                "MagicDesk Report.txt", "video"));
        assertFalse(ShellFileSystem.matchesSearchQuery(
                "MagicDesk Report.txt", ""));
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

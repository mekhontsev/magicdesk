package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public final class ShellDesktopDirectoryTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void metadataObservationIgnoresStagingButKeepsPublicationAndDirectoryInvalidation() {
        for (final String path : new String[] {
                ".magicdesk", ".magicdesk/desktop.json", ".magicdesk/wallpaper"}) {
            assertTrue(ShellDesktopDirectory.isPublishedMetadataPath(path));
        }
        for (final String path : new String[] {
                null, "", ".magicdesk/desktop.json.pending", ".magicdesk/wallpaper.42.pending",
                ".magicdesk/desktop.json.42.pending", ".magicdesk/other", "wallpaper"}) {
            assertFalse(ShellDesktopDirectory.isPublishedMetadataPath(path));
        }
    }

    @Test
    public void stateLimitIsEnforcedWhileReadingNotFromReportedLength() throws IOException {
        final int limit = 2 * 1024 * 1024;
        final InputStream input = new ByteArrayInputStream(new byte[limit + 1]) {
            @Override
            public int available() {
                return 0;
            }
        };
        assertThrows(IOException.class, () -> ShellDesktopDirectory.readState(input));
        assertEquals(limit, ShellDesktopDirectory.readState(
                new ByteArrayInputStream(new byte[limit])).length());
    }

    @Test
    public void stateReaderPreservesUtf8AndDoesNotCloseBorrowedStream() throws IOException {
        final String state = "{\"name\":\"\u0414\u043e\u043c\"}";
        final InputStream input = new ByteArrayInputStream(bytes(state)) {
            @Override
            public void close() {
                throw new AssertionError("stream belongs to caller");
            }
        };
        assertEquals(state, ShellDesktopDirectory.readState(input));
    }

    @Test
    public void overlappingWritesPublishOnlyTheirOwnCompleteContent() throws IOException {
        final Path directory = temporary.newFolder().toPath();
        final Path target = Files.writeString(directory.resolve("desktop.json"), "original");

        ShellDesktopDirectory.writeAtomically(target, outer -> {
            outer.write(bytes("outer-"));
            assertEquals("original", Files.readString(target));
            // Interleave writers deterministically without a scheduler or timing assumptions.
            ShellDesktopDirectory.writeAtomically(target, inner -> inner.write(bytes("inner")));
            assertEquals("inner", Files.readString(target));
            outer.write(bytes("complete"));
        });

        assertEquals("outer-complete", Files.readString(target));
        assertEquals(List.of(target), children(directory));
    }

    @Test
    public void failedOverlappingWriteDoesNotRemoveAnotherWritersStagingFile() throws IOException {
        final Path directory = temporary.newFolder().toPath();
        final Path target = Files.writeString(directory.resolve("desktop.json"), "original");
        final IOException failure = new IOException("source failed");

        ShellDesktopDirectory.writeAtomically(target, outer -> {
            outer.write(bytes("complete"));
            final var error = assertThrows(IllegalStateException.class,
                    () -> ShellDesktopDirectory.writeAtomically(target, inner -> {
                        inner.write(bytes("partial"));
                        throw failure;
                    }));
            assertSame(failure, error.getCause());
            assertEquals("original", Files.readString(target));
        });

        assertEquals("complete", Files.readString(target));
        assertEquals(List.of(target), children(directory));
    }

    @Test
    public void uncheckedWriterFailureCleansStagingAndPreservesDestination() throws IOException {
        final Path directory = temporary.newFolder().toPath();
        final Path target = Files.writeString(directory.resolve("wallpaper"), "original");
        final IllegalArgumentException failure = new IllegalArgumentException("invalid source");

        final var error = assertThrows(IllegalArgumentException.class,
                () -> ShellDesktopDirectory.writeAtomically(target, output -> {
                    output.write(bytes("partial"));
                    throw failure;
                }));

        assertSame(failure, error);
        assertEquals("original", Files.readString(target));
        assertEquals(List.of(target), children(directory));
    }

    @Test
    public void failedPublicationCleansStagingWithoutDeletingDestination() throws IOException {
        final Path directory = temporary.newFolder().toPath();
        final Path target = Files.createDirectory(directory.resolve("wallpaper"));
        final Path existing = Files.writeString(target.resolve("keep"), "original");

        assertThrows(IllegalStateException.class,
                () -> ShellDesktopDirectory.writeAtomically(target, output -> output.write(bytes("new"))));

        assertEquals("original", Files.readString(existing));
        assertEquals(List.of(target), children(directory));
    }

    @Test
    public void writeDoesNotTakeOverAnExistingPendingFile() throws IOException {
        final Path directory = temporary.newFolder().toPath();
        final Path target = directory.resolve("desktop.json");
        final Path other = Files.writeString(directory.resolve("desktop.json.pending"), "not ours");

        ShellDesktopDirectory.writeAtomically(target, output -> output.write(bytes("new")));

        assertEquals("not ours", Files.readString(other));
        assertEquals("new", Files.readString(target));
        assertEquals(2, children(directory).size());
    }

    private static byte[] bytes(final String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static List<Path> children(final Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            return paths.collect(Collectors.toList());
        }
    }
}

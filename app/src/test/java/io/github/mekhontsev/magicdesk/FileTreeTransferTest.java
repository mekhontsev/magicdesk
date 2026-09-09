package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Rule;
import org.junit.Test;

public final class FileTreeTransferTest {
    @Rule
    public final TestFileSystem files = new TestFileSystem();

    @Test
    public void copyMustNotOverwriteFileCreatedAtChosenTarget() throws Exception {
        final Path source = file("source", "new");
        final Path target = file("target", "existing");

        assertThrows(IOException.class, () -> FileTreeTransfer.transfer(
                source, target, false, progress()));
        assertEquals("existing", Files.readString(target));
        assertEquals("new", Files.readString(source));
    }

    @Test
    public void failedDirectoryCopyMustNotDeleteExistingTargetTree() throws Exception {
        final Path source = files.newDirectory("source");
        final Path target = files.newDirectory("target");
        Files.writeString(target.resolve("keep"), "existing");

        assertThrows(IOException.class, () -> FileTreeTransfer.transfer(
                source, target, false, progress()));
        assertTrue(Files.exists(target.resolve("keep")));
        assertEquals("existing", Files.readString(target.resolve("keep")));
    }

    @Test
    public void failedMoveMustKeepBothExistingFiles() throws Exception {
        final Path source = file("source", "new");
        final Path target = file("target", "existing");

        assertThrows(IOException.class, () -> FileTreeTransfer.transfer(
                source, target, true, progress()));
        assertEquals("existing", Files.readString(target));
        assertEquals("new", Files.readString(source));
    }

    @Test
    public void cancellationIsCheckedBeforeCreatingEvenAnEmptyFile() throws Exception {
        final Path source = file("source", "");
        final Path target = files.root().resolve("target");
        final FileTreeTransfer.Progress cancelled = new FileTreeTransfer.Progress() {
            @Override
            public void checkCancelled() throws IOException {
                throw new IOException("cancelled");
            }

            @Override
            public void addBytes(final int count, final Path file) {
                throw new AssertionError("cancelled copy must not write");
            }
        };

        assertThrows(IOException.class, () -> FileTreeTransfer.transfer(
                source, target, false, cancelled));
        assertFalse(Files.exists(target));
    }

    @Test
    public void failedCopyCleansOnlyItsOwnEntries() throws Exception {
        final Path source = files.newDirectory("source");
        Files.writeString(source.resolve("copy"), "payload");
        final Path target = files.root().resolve("target");
        final FileTreeTransfer.Progress cancelled = new FileTreeTransfer.Progress() {
            @Override
            public void checkCancelled() {
            }

            @Override
            public void addBytes(final int count, final Path file) throws IOException {
                Files.writeString(target.resolve("other-process-file"), "keep");
                throw new IOException("cancelled");
            }
        };

        assertThrows(IOException.class, () -> FileTreeTransfer.transfer(
                source, target, false, cancelled));
        assertTrue(Files.exists(target.resolve("other-process-file")));
        assertFalse(Files.exists(target.resolve("copy")));
    }

    @Test
    public void copiesNestedTreeAndLinksWithoutFollowingThem() throws Exception {
        final Path source = files.newDirectory("source");
        Files.createDirectory(source.resolve("nested"));
        Files.writeString(source.resolve("nested/file"), "payload");
        Files.createSymbolicLink(source.resolve("link"), files.path("nested/file"));
        Files.createSymbolicLink(source.resolve("broken"), files.path("missing"));
        final Path target = files.root().resolve("target");
        final AtomicLong bytes = new AtomicLong();

        FileTreeTransfer.transfer(source, target, false, new FileTreeTransfer.Progress() {
            @Override
            public void checkCancelled() {
            }

            @Override
            public void addBytes(final int count, final Path file) {
                bytes.addAndGet(count);
            }
        });

        assertEquals("payload", Files.readString(target.resolve("nested/file")));
        assertEquals(files.path("nested/file"), Files.readSymbolicLink(target.resolve("link")));
        assertTrue(Files.exists(target.resolve("broken"), LinkOption.NOFOLLOW_LINKS));
        assertEquals(7L, bytes.get());
        assertTrue(Files.exists(source.resolve("nested/file")));
    }

    @Test
    public void cancelledFileCopyRemovesItsIncompleteTarget() throws Exception {
        final Path source = file("source", "payload");
        final Path target = files.root().resolve("target");

        assertThrows(IOException.class, () -> FileTreeTransfer.transfer(
                source, target, false, failAfterWrite(() -> { })));

        assertFalse(Files.exists(target));
        assertEquals("payload", Files.readString(source));
    }

    @Test
    public void replacedDestinationCannotAuthorizeMoveSourceDeletion() throws Exception {
        final Path source = file("source", "payload");
        final Path target = files.root().resolve("target");
        final Path replacement = file("replacement", "keep");
        final FileTreeTransfer.Progress replacingWriter = new FileTreeTransfer.Progress() {
            @Override
            public void checkCancelled() { }

            @Override
            public void addBytes(final int count, final Path file) throws IOException {
                Files.delete(target);
                Files.move(replacement, target);
            }
        };

        assertThrows(IOException.class, () ->
                FileTreeTransfer.copyAndDeleteSource(source, target, replacingWriter));
        assertEquals("payload", Files.readString(source));
        assertEquals("keep", Files.readString(target));
    }

    @Test
    public void replacedTargetIsNotDeletedDuringCleanup() throws Exception {
        final Path source = file("source", "payload");
        final Path target = files.root().resolve("target");
        final Path replacement = file("replacement", "keep");

        assertThrows(IOException.class, () -> FileTreeTransfer.transfer(
                source, target, false, failAfterWrite(() -> {
                    Files.delete(target);
                    Files.move(replacement, target);
                })));

        assertEquals("keep", Files.readString(target));
    }

    @Test
    public void replacedParentIsNotFollowedDuringCleanup() throws Exception {
        final Path source = files.newDirectory("source");
        Files.writeString(source.resolve("copy"), "payload");
        final Path target = files.root().resolve("target");
        final Path moved = files.root().resolve("moved");

        assertThrows(IOException.class, () -> FileTreeTransfer.transfer(
                source, target, false, failAfterWrite(() -> {
                    Files.move(target, moved);
                    Files.createSymbolicLink(target, moved);
                })));

        assertEquals("payload", Files.readString(moved.resolve("copy")));
        assertTrue(Files.isSymbolicLink(target));
    }

    @Test
    public void existingSymlinkCannotRedirectTheCopy() throws Exception {
        final Path source = file("source", "payload");
        final Path outside = file("outside", "keep");
        final Path target = files.root().resolve("target");
        Files.createSymbolicLink(target, outside);

        assertThrows(IOException.class, () -> FileTreeTransfer.transfer(
                source, target, false, progress()));

        assertEquals("keep", Files.readString(outside));
        assertTrue(Files.isSymbolicLink(target));
    }

    @Test
    public void failedSourceDeletionKeepsTheCompletedMoveCopy() throws Exception {
        final Path source = file("source", "payload");
        final Path target = files.root().resolve("target");
        final FileTreeTransfer.Progress cancellation = new FileTreeTransfer.Progress() {
            private int completedChecks;

            @Override
            public void checkCancelled() throws IOException {
                if (Files.exists(target) && Files.size(target) == 7L
                        && ++completedChecks == 2) {
                    throw new IOException("cancelled during source deletion");
                }
            }

            @Override
            public void addBytes(final int count, final Path file) {
            }
        };

        final IOException failure = assertThrows(IOException.class,
                () -> FileTreeTransfer.copyAndDeleteSource(source, target, cancellation));

        assertTrue(failure.getMessage().contains("could not fully remove"));
        assertEquals("payload", Files.readString(source));
        assertEquals("payload", Files.readString(target));
    }

    private interface Mutation {
        void run() throws IOException;
    }

    private static FileTreeTransfer.Progress failAfterWrite(final Mutation mutation) {
        return new FileTreeTransfer.Progress() {
            @Override
            public void checkCancelled() {
            }

            @Override
            public void addBytes(final int count, final Path file) throws IOException {
                mutation.run();
                throw new IOException("cancelled");
            }
        };
    }

    @Test
    public void ordinaryMoveRetainsContentsAndRemovesSource() throws Exception {
        final Path source = file("source", "payload");
        final Path target = files.root().resolve("target");

        FileTreeTransfer.transfer(source, target, true, progress());

        assertFalse(Files.exists(source));
        assertEquals("payload", Files.readString(target));
    }

    private Path file(final String name, final String content) throws IOException {
        return Files.writeString(files.root().resolve(name), content);
    }

    private static FileTreeTransfer.Progress progress() {
        return new FileTreeTransfer.Progress() {
            @Override
            public void checkCancelled() {
            }

            @Override
            public void addBytes(final int count, final Path file) {
            }
        };
    }
}

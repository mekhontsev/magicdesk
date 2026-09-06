package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Filesystem transfer mechanics; Binder ownership and UI progress stay in ShellFileSystem. */
final class FileTreeTransfer {
    interface Progress {
        void checkCancelled() throws IOException;

        void addBytes(int count, Path source) throws IOException;
    }

    private static final LinkOption[] NO_FOLLOW = {LinkOption.NOFOLLOW_LINKS};
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private FileTreeTransfer() {
    }

    static void transfer(
            final Path source,
            final Path target,
            final boolean move,
            final Progress progress) throws IOException {
        progress.checkCancelled();
        if (move) {
            moveTree(source, target, progress);
        } else {
            copyTree(source, target, progress);
        }
    }

    private static void moveTree(
            final Path source,
            final Path target,
            final Progress operation) throws IOException {
        operation.checkCancelled();
        try {
            Files.move(source, target);
        } catch (FileAlreadyExistsException conflict) {
            throw conflict;
        } catch (IOException directMoveFailure) {
            try {
                copyAndDeleteSource(source, target, operation);
            } catch (IOException failure) {
                failure.addSuppressed(directMoveFailure);
                throw failure;
            }
        }
    }

    static void copyAndDeleteSource(
            final Path source, final Path target, final Progress operation) throws IOException {
        copyTree(source, target, operation);
        try {
            FileTreeDeletion.delete(source, operation::checkCancelled);
        } catch (IOException failure) {
            // The destination is now complete. Failure or cancellation during
            // source deletion must never roll back the surviving copy.
            throw new IOException("copied to " + target
                    + " but could not fully remove " + source, failure);
        }
    }

    private static void copyTree(
            final Path source,
            final Path target,
            final Progress operation) throws IOException {
        operation.checkCancelled();
        final List<CreatedEntry> created = new ArrayList<>();
        try {
            copyTree(source, target, operation, created);
            operation.checkCancelled();
        } catch (IOException | RuntimeException failure) {
            // Roll back only entries created by this copy, never the whole
            // target path: another process may have created or replaced it.
            Throwable cleanupFailure = null;
            for (int index = created.size() - 1; index >= 0; index--) {
                try {
                    created.get(index).delete();
                } catch (IOException | RuntimeException error) {
                    if (cleanupFailure == null) {
                        cleanupFailure = error;
                    }
                }
            }
            if (cleanupFailure != null) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private static void copyTree(
            final Path source,
            final Path target,
            final Progress operation,
            final List<CreatedEntry> created) throws IOException {
        if (Files.isSymbolicLink(source)) {
            Files.createSymbolicLink(target, Files.readSymbolicLink(source));
            recordCreated(target, null, created);
            return;
        }
        if (!Files.isDirectory(source, NO_FOLLOW)) {
            copyFile(source, target, operation, null, created);
            return;
        }
        final Deque<CreatedEntry> parents = new ArrayDeque<>();
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    final Path directory,
                    final BasicFileAttributes attributes) throws IOException {
                operation.checkCancelled();
                final Path relative = source.relativize(directory);
                final Path copy = target.resolve(relative);
                Files.createDirectory(copy);
                parents.addLast(recordCreated(copy, parents.peekLast(), created));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(
                    final Path file,
                    final BasicFileAttributes attributes) throws IOException {
                operation.checkCancelled();
                final Path copy = target.resolve(source.relativize(file));
                if (attributes.isSymbolicLink()) {
                    Files.createSymbolicLink(
                            copy, Files.readSymbolicLink(file));
                    recordCreated(copy, parents.peekLast(), created);
                } else {
                    copyFile(file, copy, operation, parents.peekLast(), created);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(
                    final Path directory, final IOException failure) throws IOException {
                if (failure != null) {
                    throw failure;
                }
                parents.removeLast();
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void copyFile(
            final Path source,
            final Path target,
            final Progress operation,
            final CreatedEntry parent,
            final List<CreatedEntry> created) throws IOException {
        final CreatedEntry entry;
        try (InputStream input = Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS);
                OutputStream output = Files.newOutputStream(
                        target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            entry = recordCreated(target, parent, created);
            final byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                operation.checkCancelled();
                output.write(buffer, 0, count);
                operation.addBytes(count, source);
            }
        }
        // Losing the destination is a failed copy, not a timestamp warning:
        // a cross-filesystem move must retain its source in this case.
        entry.verifyIdentity();
        try {
            Files.setLastModifiedTime(
                    target, Files.getLastModifiedTime(source, NO_FOLLOW));
        } catch (IOException ignored) {
            // Content is more important than optional timestamp preservation.
        }
    }

    private static CreatedEntry recordCreated(
            final Path path,
            final CreatedEntry parent,
            final List<CreatedEntry> created) throws IOException {
        final Object key = Files.readAttributes(
                path, BasicFileAttributes.class, NO_FOLLOW).fileKey();
        final CreatedEntry entry = new CreatedEntry(path, key, parent);
        created.add(entry);
        return entry;
    }

    private static final class CreatedEntry {
        final Path path;
        final Object key;
        final CreatedEntry parent;

        CreatedEntry(final Path path, final Object key, final CreatedEntry parent) {
            this.path = path;
            this.key = key;
            this.parent = parent;
        }

        void verifyIdentity() throws IOException {
            for (CreatedEntry entry = this; entry != null; entry = entry.parent) {
                if (entry.key == null || !entry.key.equals(Files.readAttributes(
                        entry.path, BasicFileAttributes.class, NO_FOLLOW).fileKey())) {
                    throw new IOException("copy target identity changed: " + entry.path);
                }
            }
        }

        void delete() throws IOException {
            try {
                verifyIdentity();
                // Non-recursive deletion preserves foreign files inside an
                // otherwise owned directory. Missing identity means no cleanup.
                Files.delete(path);
            } catch (NoSuchFileException ignored) {
                // The created entry (or its parent) is already gone.
            }
        }
    }
}

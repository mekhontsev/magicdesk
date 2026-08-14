package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

final class FileTreeDeletion {
    interface Cancellation {
        void check() throws IOException;
    }

    private static final LinkOption[] NO_FOLLOW = {
            LinkOption.NOFOLLOW_LINKS
    };

    private FileTreeDeletion() {
    }

    static void delete(final Path target, final Cancellation cancellation)
            throws IOException {
        check(cancellation);
        if (Files.isSymbolicLink(target)
                || !Files.isDirectory(target, NO_FOLLOW)) {
            Files.delete(target);
            return;
        }
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(
                    final Path file,
                    final BasicFileAttributes attributes) throws IOException {
                check(cancellation);
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(
                    final Path directory,
                    final IOException error) throws IOException {
                check(cancellation);
                if (error != null) {
                    throw error;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void check(final Cancellation cancellation)
            throws IOException {
        if (cancellation != null) {
            cancellation.check();
        }
    }
}

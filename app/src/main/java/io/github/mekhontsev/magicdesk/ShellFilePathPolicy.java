package io.github.mekhontsev.magicdesk;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

final class ShellFilePathPolicy {
    private static final LinkOption[] NO_FOLLOW = {
            LinkOption.NOFOLLOW_LINKS
    };

    private ShellFilePathPolicy() {
    }

    static Path absolute(final String rawPath) {
        if (rawPath == null || rawPath.length() == 0
                || rawPath.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("missing absolute path");
        }
        final Path path = Path.of(rawPath);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException("path must be absolute");
        }
        return path.normalize();
    }

    static Path existing(final String rawPath) {
        final Path path = absolute(rawPath);
        if (!Files.exists(path, NO_FOLLOW)) {
            throw new IllegalArgumentException("path does not exist");
        }
        return path;
    }

    static Path mutableEntry(final String rawPath) {
        final Path path = existing(rawPath);
        if (path.getParent() == null) {
            throw new IllegalArgumentException(
                    "filesystem root cannot be modified");
        }
        return path;
    }

    static void rejectRecursiveTarget(
            final Path source, final Path target) {
        final Path normalizedSource = source.toAbsolutePath().normalize();
        final Path normalizedTarget = target.toAbsolutePath().normalize();
        if (normalizedTarget.equals(normalizedSource)) {
            throw new IllegalArgumentException(
                    "source and destination are the same");
        }
        if (Files.isDirectory(source, NO_FOLLOW)
                && normalizedTarget.startsWith(normalizedSource)) {
            throw new IllegalArgumentException(
                    "cannot copy a directory into itself");
        }
    }
}

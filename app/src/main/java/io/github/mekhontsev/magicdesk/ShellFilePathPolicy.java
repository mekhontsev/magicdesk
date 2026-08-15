package io.github.mekhontsev.magicdesk;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

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

    /** Normalizes a path in the Android shell namespace on any build host. */
    static String normalizeShellAbsolute(final String rawPath) {
        if (rawPath == null || rawPath.length() == 0
                || rawPath.charAt(0) != '/'
                || rawPath.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("missing absolute path");
        }
        final Deque<String> components = new ArrayDeque<>();
        for (final String component : rawPath.split("/", -1)) {
            if (component.isEmpty() || ".".equals(component)) {
                continue;
            }
            if ("..".equals(component)) {
                if (!components.isEmpty()) {
                    components.removeLast();
                }
                continue;
            }
            components.addLast(component);
        }
        return components.isEmpty()
                ? "/" : "/" + String.join("/", components);
    }

    static String shellParent(final String rawPath) {
        final String normalized = normalizeShellAbsolute(rawPath);
        final int separator = normalized.lastIndexOf('/');
        return separator <= 0 ? "/" : normalized.substring(0, separator);
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

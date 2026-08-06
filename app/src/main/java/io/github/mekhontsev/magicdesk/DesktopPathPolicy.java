package io.github.mekhontsev.magicdesk;

import java.nio.file.Path;

final class DesktopPathPolicy {
    private DesktopPathPolicy() {
    }

    static String validateName(final String requestedName) {
        if (requestedName == null) {
            throw new IllegalArgumentException("missing desktop entry name");
        }
        final String name = requestedName.trim();
        if (name.length() == 0
                || ".".equals(name)
                || "..".equals(name)
                || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0
                || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid desktop entry name");
        }
        return name;
    }

    static Path resolve(final Path root, final String relativePath) {
        if (root == null) {
            throw new IllegalArgumentException("missing desktop root");
        }
        if (relativePath == null || relativePath.length() == 0) {
            throw new IllegalArgumentException("missing relative path");
        }
        final Path normalizedRoot = root.toAbsolutePath().normalize();
        final Path candidate = normalizedRoot.resolve(relativePath).normalize();
        if (!candidate.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException(
                    "path escapes the desktop directory");
        }
        return candidate;
    }
}

package io.github.mekhontsev.magicdesk.x11;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

/** Explicit guest alias for a session exchange directory, never a rootfs or privileged file resolver. */
final class X11SharedFiles {
    static final String GUEST = "/tmp/magicdesk-x11/content";
    private final Path root;

    X11SharedFiles(String directory) {
        try { root = new java.io.File(directory).getCanonicalFile().toPath(); }
        catch (IOException error) { throw new IllegalArgumentException("Invalid X11 content directory", error); }
    }

    String hostPath(String path) throws IOException {
        Path normalized = Path.of(path).normalize();
        Path guest = Path.of(GUEST);
        Path result = normalized.startsWith(guest) ? root.resolve(guest.relativize(normalized))
                : normalized.toFile().getCanonicalFile().toPath();
        requireInside(result.toString());
        return result.toString();
    }

    void requireInside(String path) throws IOException {
        Path normalized = Path.of(path).normalize();
        if (normalized.equals(root) || !normalized.startsWith(root))
            throw new IOException("Export the file through the X11 session's shared content directory");
    }

    String guestUri(String path) throws IOException {
        Path host = new java.io.File(path).getCanonicalFile().toPath();
        requireInside(host.toString());
        return Path.of(GUEST).resolve(root.relativize(host)).toUri().toASCIIString();
    }
}

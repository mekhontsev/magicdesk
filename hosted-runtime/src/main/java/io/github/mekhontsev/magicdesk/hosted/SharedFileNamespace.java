package io.github.mekhontsev.magicdesk.hosted;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

/** Explicit guest alias for a session exchange directory, never a rootfs or privileged file resolver. */
final class SharedFileNamespace {
    private final Path guest;
    private final Path root;

    SharedFileNamespace(String directory, String guestDirectory) {
        guest = Path.of(guestDirectory).normalize();
        if (!guest.isAbsolute() || guest.getParent() == null) throw new IllegalArgumentException("Invalid guest content alias");
        try { root = new java.io.File(directory).getCanonicalFile().toPath(); }
        catch (IOException error) { throw new IllegalArgumentException("Invalid content directory", error); }
    }

    String hostPath(String path) throws IOException {
        Path normalized = Path.of(path).normalize();
        Path result = normalized.startsWith(guest) ? root.resolve(guest.relativize(normalized))
                : normalized.toFile().getCanonicalFile().toPath();
        requireInside(result.toString());
        return result.toString();
    }

    void requireInside(String path) throws IOException {
        Path normalized = Path.of(path).normalize();
        if (normalized.equals(root) || !normalized.startsWith(root))
            throw new IOException("Export the file through the session's shared content directory");
    }

    String guestUri(String path) throws IOException {
        Path host = new java.io.File(path).getCanonicalFile().toPath();
        requireInside(host.toString());
        return guest.resolve(root.relativize(host)).toUri().toASCIIString();
    }
}

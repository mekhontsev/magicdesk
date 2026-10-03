package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/** Bounded artifact IO, independent of Android, Desktop and guest execution. */
final class GuestImageFiles {
    interface DirectorySync { void sync(Path directory) throws IOException; }
    static final DirectorySync DIRECTORY_SYNC = GuestImageFiles::syncDirectory;
    static final long JSON_LIMIT = 8 * 1024 * 1024;
    static final long BLOB_LIMIT = 8L * 1024 * 1024 * 1024;

    static String digest(InputStream input) throws IOException {
        MessageDigest digest = sha256();
        copy(input, null, BLOB_LIMIT, digest);
        return HexFormat.of().formatHex(digest.digest());
    }

    static String digest(Path path) throws IOException {
        regular(path);
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            return digest(input);
        }
    }

    static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }

    static String hex(String digest) throws IOException {
        if (digest == null || !digest.matches("sha256:[0-9a-f]{64}"))
            throw new IOException("Expected a SHA-256 content digest");
        return digest.substring(7);
    }

    static void regular(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Not an ordinary file: " + path);
    }

    static String read(Path path) throws IOException {
        regular(path);
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            var output = new java.io.ByteArrayOutputStream();
            copy(input, output, JSON_LIMIT, null);
            return output.toString(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    static long copy(InputStream input, java.io.OutputStream output, long limit,
                     MessageDigest digest) throws IOException {
        byte[] buffer = new byte[65536];
        long count = 0;
        for (int n; (n = input.read(buffer)) != -1;) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
            count += n;
            if (count > limit) throw new IOException("Artifact exceeds size limit");
            if (digest != null) digest.update(buffer, 0, n);
            if (output != null) output.write(buffer, 0, n);
        }
        return count;
    }

    static void write(Path destination, String text, DirectorySync directories) throws IOException {
        write(destination, text.getBytes(java.nio.charset.StandardCharsets.UTF_8), directories);
    }

    static void write(Path destination, byte[] data, DirectorySync directories) throws IOException {
        Path temporary = destination.resolveSibling(".publish-" + UUID.randomUUID());
        try {
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                ByteBuffer bytes = ByteBuffer.wrap(data);
                while (bytes.hasRemaining()) file.write(bytes);
                file.force(true);
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            directories.sync(destination.getParent());
        } finally { Files.deleteIfExists(temporary); }
    }

    static void syncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private GuestImageFiles() { }
}

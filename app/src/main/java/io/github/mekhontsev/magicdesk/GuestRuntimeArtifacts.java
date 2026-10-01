package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;

/** Immutable content-addressed helpers; no collection while guest processes may still exec. */
public final class GuestRuntimeArtifacts {
    public static final List<String> FILES = List.of("libmagicdesk_guest_bootstrap.so",
            "libmagicdesk_guest_supervisor.so", "libmagicdesk_guest_run.so", "libmagicdesk_guest_service.so",
            "libmagicdesk_guest_image.so");

    public static Path prepare(Path source, Path root) throws IOException {
        if (!source.isAbsolute() || !root.isAbsolute()) throw new IOException("Absolute runtime paths required");
        var hashes = new LinkedHashMap<String, String>();
        MessageDigest digest = sha256();
        for (String name : FILES) {
            String hash = hash(source.resolve(name));
            hashes.put(name, hash);
            digest.update(hash.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }
        Files.createDirectories(root, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid runtime store");
        Path target = root.resolve(HexFormat.of().formatHex(digest.digest()));
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            verify(target, hashes);
            return target;
        }
        Path temporary = Files.createTempDirectory(root, ".prepare-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        try {
            for (String name : FILES) {
                Path file = temporary.resolve(name);
                try (InputStream input = open(source.resolve(name));
                     var output = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    var bytes = new byte[32768];
                    int total = 0;
                    for (int n; (n = input.read(bytes)) >= 0;) {
                        if ((total += n) > 16777216) throw new IOException("Runtime artifact grew during preparation");
                        var buffer = java.nio.ByteBuffer.wrap(bytes, 0, n);
                        while (buffer.hasRemaining()) output.write(buffer);
                    }
                    output.force(true);
                }
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("r-x------"));
            }
            verify(temporary, hashes);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.FileSystemException conflict) {
                if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw conflict;
                verify(target, hashes);
            }
            return target;
        } finally {
            for (String name : FILES) Files.deleteIfExists(temporary.resolve(name));
            Files.deleteIfExists(temporary);
        }
    }

    private static void verify(Path directory, LinkedHashMap<String, String> hashes) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid runtime bundle");
        for (var entry : hashes.entrySet()) {
            Path file = directory.resolve(entry.getKey());
            if (!entry.getValue().equals(hash(file)) || !Files.isExecutable(file))
                throw new IOException("Guest runtime bundle is incomplete or modified: " + file);
        }
    }

    private static InputStream open(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 16777216)
            throw new IOException("Invalid guest runtime artifact: " + file);
        return Channels.newInputStream(Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
    }

    private static String hash(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream stream = open(file)) {
            byte[] bytes = new byte[32768];
            int total = 0;
            for (int n; (n = stream.read(bytes)) >= 0;) {
                if ((total += n) > 16777216) throw new IOException("Runtime artifact grew during preparation");
                digest.update(bytes, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private GuestRuntimeArtifacts() { }
}

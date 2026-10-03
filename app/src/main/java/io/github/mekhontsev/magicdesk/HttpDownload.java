package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Bounded-memory transfer with verified, atomic destination replacement. */
final class HttpDownload {
    interface Progress { void update(long bytes, long total); }

    private HttpDownload() { }

    static long save(HttpGet.Connections connections, URI uri, Path destination,
                     String sha256, Progress progress) throws IOException {
        HttpGet.requireHttp(uri);
        final MessageDigest hash = sha256 == null ? null : sha256();
        final Path target = destination.toAbsolutePath();
        final Path parent = target.getParent();
        if (parent == null || Files.isDirectory(target)) throw new IOException("Destination must be a file");
        final Path temporary = Files.createTempFile(parent, ".magicdesk-download-", ".part");
        // Normal process termination also removes unfinished data; SIGKILL cannot run cleanup.
        final Thread cleanup = new Thread(() -> {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }, "download-cleanup");
        HttpURLConnection connection = null;
        boolean hooked = false;
        try {
            Runtime.getRuntime().addShutdownHook(cleanup);
            hooked = true;
            connection = HttpGet.get(connections, uri);
            final String encoding = connection.getHeaderField("Content-Encoding");
            if (encoding != null && !encoding.equalsIgnoreCase("identity")) {
                throw new IOException("Unexpected HTTP Content-Encoding: " + encoding);
            }
            final long total = connection.getContentLengthLong();
            long copied = 0;
            progress.update(0, total);
            try (InputStream input = connection.getInputStream(); var output = Files.newOutputStream(temporary)) {
                final byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Download cancelled");
                    if (count == 0) continue;
                    if (copied > Long.MAX_VALUE - count || (total >= 0 && count > total - copied)) {
                        throw new IOException("Download exceeds Content-Length");
                    }
                    output.write(buffer, 0, count);
                    if (hash != null) hash.update(buffer, 0, count);
                    copied += count;
                    progress.update(copied, total);
                }
            }
            if (total >= 0 && copied != total) throw new IOException("Incomplete download: " + copied + " of " + total + " bytes");
            if (hash != null && !sha256.equalsIgnoreCase(HexFormat.of().formatHex(hash.digest()))) {
                throw new IOException("SHA-256 mismatch");
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Download cancelled");
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.WRITE)) { file.force(true); }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return copied;
        } finally {
            if (connection != null) connection.disconnect();
            if (hooked) {
                try { Runtime.getRuntime().removeShutdownHook(cleanup); }
                catch (IllegalStateException ignored) { /* Shutdown already owns the hook. */ }
            }
            Files.deleteIfExists(temporary);
        }
    }

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }
}

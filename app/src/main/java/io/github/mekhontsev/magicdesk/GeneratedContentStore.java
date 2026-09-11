package io.github.mekhontsev.magicdesk;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.UUID;

/** App-private, finite-lived exports. Publishing never evicts a still-valid recipient's file. */
final class GeneratedContentStore {
    static final long LIFETIME_MILLIS = 24L * 60 * 60 * 1000;
    static final long MAX_BYTES = 256L * 1024 * 1024;
    private static final int MAX_FILES = 128;

    interface Writer { void write(OutputStream output) throws IOException; }

    private GeneratedContentStore() { }

    static synchronized String publish(File directory, Writer writer, long now) throws IOException {
        Files.createDirectories(directory.toPath());
        long used = 0;
        int count = 0;
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("export directory is unavailable");
        for (File file : files) {
            if (expired(file, now)) Files.deleteIfExists(file.toPath());
            else { used += file.length(); count++; }
        }
        if (used >= MAX_BYTES || count >= MAX_FILES) throw new IOException("temporary export storage is full");
        String id = UUID.randomUUID().toString();
        File target = new File(directory, id);
        boolean complete = false;
        try {
            final long available = MAX_BYTES - used;
            try (OutputStream stream = Files.newOutputStream(target.toPath())) {
                writer.write(new OutputStream() {
                    private long written;
                    private void reserve(int length) throws IOException {
                        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException();
                        if (length > available - written) throw new IOException("temporary export storage is full");
                        written += length;
                    }
                    @Override public void write(int value) throws IOException { reserve(1); stream.write(value); }
                    @Override public void write(byte[] data, int offset, int length) throws IOException {
                        reserve(length); stream.write(data, offset, length);
                    }
                    @Override public void flush() throws IOException { stream.flush(); }
                });
            }
            if (!target.setLastModified(now)) throw new IOException("cannot timestamp export");
            complete = true;
            return id;
        } finally {
            if (!complete) Files.deleteIfExists(target.toPath());
        }
    }

    static File resolve(File directory, String id, long now) throws FileNotFoundException {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new FileNotFoundException("invalid export id");
        }
        File file = new File(directory, id);
        if (!file.isFile() || expired(file, now)) throw new FileNotFoundException("export has expired");
        return file;
    }

    private static boolean expired(File file, long now) {
        return now - file.lastModified() >= LIFETIME_MILLIS;
    }
}

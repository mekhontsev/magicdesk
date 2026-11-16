package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Bounded native command replies, independent of catalog and image storage. */
final class GuestImageFiles {
    static final int JSON_LIMIT = 8 * 1024 * 1024;

    static String read(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Not an ordinary file: " + path);
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(JSON_LIMIT + 1);
            if (bytes.length > JSON_LIMIT) throw new IOException("Guest reply exceeds size limit");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private GuestImageFiles() { }
}

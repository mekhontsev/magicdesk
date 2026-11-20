package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.zip.ZipInputStream;

/** Only for the signed APK asset, never for caller-supplied archives. */
final class BundledKeyboardData {
    static final String NAME = "xkeyboard-config-2.48";

    static void install(InputStream asset, Path target) throws IOException {
        Path staging = Files.createTempDirectory(target.getParent(), "xkb-");
        try (var zip = new ZipInputStream(asset)) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                Path file = staging.resolve(entry.getName()).normalize();
                if (!file.startsWith(staging) || file.equals(staging)) throw new IOException("Invalid XKB asset");
                if (entry.isDirectory()) Files.createDirectories(file);
                else {
                    Files.createDirectories(file.getParent());
                    Files.copy(zip, file);
                }
            }
            if (!Files.isRegularFile(staging.resolve("rules/evdev"))) throw new IOException("Incomplete XKB asset");
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            FileTreeDeletion.deleteIfExists(staging);
        }
    }

    private BundledKeyboardData() { }
}

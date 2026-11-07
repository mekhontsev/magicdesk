package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Offline adoption of the default library. Custom libraries retain their explicit location. */
final class GuestLibraryLocation {
    static void prepare(Path requested, RuntimeDataPaths paths, int uid,
                        GuestEnvironmentLibrary.Images images) throws Exception {
        Path previous = switch (uid) {
            case 2000 -> Path.of("/data/local/tmp/magicdesk-shell/guest-environments");
            case 0 -> Path.of("/data/local/tmp/magicdesk-root/guest-environments");
            default -> paths.root().resolve("no_backup/guest-environments");
        };
        if (requested.toAbsolutePath().normalize().equals(paths.linux()))
            adopt(previous, requested, images);
    }

    static void adopt(Path previous, Path destination, GuestEnvironmentLibrary.Images images) throws Exception {
        if (!Files.exists(previous, LinkOption.NOFOLLOW_LINKS)) return;
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Both Linux libraries exist: " + previous + " and " + destination);
        images.invoke("relocate-library", previous.toString(), destination.toString());
    }

    private GuestLibraryLocation() { }
}

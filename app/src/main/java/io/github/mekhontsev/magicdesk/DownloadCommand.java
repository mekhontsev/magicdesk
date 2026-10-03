package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Local CLI operation: no command channel, app initialization or guest runtime. */
final class DownloadCommand {
    static final String HELP = "Usage: magicdesk download [--sha256 HASH] URL FILE\n\n"
            + "Download an HTTP(S) file under the calling UID. FILE's parent must exist.\n"
            + "Replace FILE only after a complete transfer and optional SHA-256 verification.\n"
            + "HTTPS certificates are verified; HTTPS-to-HTTP redirects are rejected.\n"
            + "Progress and errors go to stderr. Stdin and stdout are not used.\n"
            + "Exit codes: 0 saved, 1 transfer or file error, 2 invalid arguments.\n";

    private DownloadCommand() { }

    static int run(String[] argv, PrintStream out, PrintStream err, HttpGet.Connections connections) {
        if (argv.length == 2 && argv[1].equals("--help")) { out.print(HELP); return 0; }
        final URI uri;
        final Path destination;
        String sha256 = null;
        try {
            final List<String> positional = new ArrayList<>();
            boolean options = true;
            for (int i = 1; i < argv.length; ++i) {
                final String arg = argv[i];
                if (options && arg.equals("--")) { options = false; continue; }
                if (options && arg.equals("--sha256")) {
                    if (sha256 != null || ++i == argv.length || !argv[i].matches("[0-9a-fA-F]{64}")) {
                        throw new IllegalArgumentException("Use --sha256 with one 64-digit hexadecimal digest");
                    }
                    sha256 = argv[i];
                } else {
                    if (options && arg.startsWith("-")) throw new IllegalArgumentException("Unknown download option: " + arg);
                    positional.add(arg);
                }
            }
            if (positional.size() != 2 || positional.get(1).isEmpty()) {
                throw new IllegalArgumentException("Use magicdesk download [--sha256 HASH] URL FILE");
            }
            uri = URI.create(positional.get(0));
            HttpGet.requireHttp(uri);
            destination = Path.of(positional.get(1));
        } catch (IllegalArgumentException | IOException error) {
            err.println("magicdesk download: " + error.getMessage());
            return 2;
        }
        try {
            final long[] nextReport = {0};
            final long bytes = HttpDownload.save(connections, uri, destination, sha256, (copied, total) -> {
                final long now = System.nanoTime();
                if (nextReport[0] == 0 || now - nextReport[0] >= 0) {
                    err.println("Downloaded " + copied + (total >= 0 ? " / " + total : "") + " bytes");
                    nextReport[0] = now + 1_000_000_000L;
                }
            });
            err.println("Saved " + bytes + " bytes to " + destination);
            return 0;
        } catch (IOException | SecurityException error) {
            err.println("magicdesk download: " + error.getMessage());
            return 1;
        }
    }
}

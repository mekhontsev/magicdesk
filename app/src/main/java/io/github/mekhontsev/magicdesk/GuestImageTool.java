package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Only offline native image operations run under ART; the shell execs application launches. */
final class GuestImageTool implements GuestEnvironmentLibrary.Images {
    private final Path executable;
    private final Path scratch;

    GuestImageTool(Path bundle, Path scratch) throws IOException {
        executable = bundle.resolve("libmagicdesk_guest_image.so");
        this.scratch = Files.createDirectories(scratch);
    }

    @Override public String invoke(String... arguments) throws Exception {
        Path output = Files.createTempFile(scratch, "image-", ".log");
        Process process = null;
        Thread cleanup = null;
        try {
            var command = new ArrayList<>(List.of(arguments));
            command.add(0, executable.toString());
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT)
                    .redirectOutput(output.toFile()).start();
            Process child = process;
            cleanup = new Thread(child::destroyForcibly, "guest-image-cancellation");
            Runtime.getRuntime().addShutdownHook(cleanup);
            // EVENT_WAIT: native image process exit; timeout cancels this offline operation.
            if (!process.waitFor(1, TimeUnit.HOURS)) throw new IOException("Guest image operation timed out");
            if (process.exitValue() != 0) throw new IOException("Guest image " + arguments[0] + " failed (exit " + process.exitValue() + ")");
            return GuestImageFiles.read(output);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                // EVENT_WAIT: killed helper exit; no successful publication is inferred from cancellation.
                process.waitFor(5, TimeUnit.SECONDS);
            }
            if (cleanup != null) Runtime.getRuntime().removeShutdownHook(cleanup);
            Files.deleteIfExists(output);
        }
    }
}

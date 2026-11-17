package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Out-of-process native CLI; ART owns only Android-side presentation and cancellation. */
final class GuestImageTool implements GuestEnvironmentLibrary.Images {
    private final Path executable;
    private final Path scratch;

    GuestImageTool(Path bundle, Path scratch) throws IOException {
        executable = bundle.resolve("libshroot_cli.so");
        this.scratch = Files.createDirectories(scratch);
    }

    @Override public String invoke(String... arguments) throws Exception {
        Path output = Files.createTempFile(scratch, "image-", ".log");
        Process process = null;
        Thread cleanup = null;
        try {
            var command = new ArrayList<>(List.of(arguments));
            command.addAll(0, List.of(executable.toString(), "--parent-pid", Integer.toString(android.os.Process.myPid())));
            var builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT)
                    .redirectOutput(output.toFile());
            process = builder.start();
            Process child = process;
            cleanup = new Thread(() -> cancel(child), "guest-image-cancellation");
            Runtime.getRuntime().addShutdownHook(cleanup);
            // EVENT_WAIT: native image process exit; timeout cancels this offline operation.
            if (!process.waitFor(1, TimeUnit.HOURS)) throw new IOException("Guest image operation timed out");
            if (process.exitValue() != 0) throw new IOException("Guest image " + arguments[0] + " failed (exit " + process.exitValue() + ")");
            return GuestImageFiles.read(output);
        } finally {
            if (process != null && process.isAlive()) cancel(process);
            if (cleanup != null) Runtime.getRuntime().removeShutdownHook(cleanup);
            Files.deleteIfExists(output);
        }
    }

    private static void cancel(Process process) {
        process.destroy();
        try {
            // EVENT_WAIT: CLI forwards cancellation to its owned worker; force termination bounds failure.
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}

package io.github.mekhontsev.magicdesk;

import java.io.IOException;

final class PrivilegedCommandRunner {
    private PrivilegedCommandRunner() {
    }

    static String run(final String command) throws IOException {
        if (RuntimeAccess.allowsRootCommands()) {
            return runRootUnchecked(command);
        }
        if (RuntimeAccess.allowsShizukuCommands()) {
            return ShizukuAccess.run(command);
        }
        throw new IOException(
                "Privileged command is unavailable in "
                        + RuntimeAccess.backendName() + " mode");
    }

    static Process start(final String command) throws IOException {
        RuntimeAccess.requireRootCommands("Privileged process");
        return new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start();
    }

    static String runRootSetup(final String command) throws IOException {
        return runRootUnchecked(command);
    }

    private static String runRootUnchecked(final String command) throws IOException {
        final Process process = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start();
        try {
            final BoundedProcessRunner.Result result =
                    BoundedProcessRunner.run(process);
            if (result.exitCode != 0) {
                throw new IOException("root command failed " + result.exitCode
                        + ": " + result.output.trim());
            }
            return result.output;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("root command interrupted", e);
        } finally {
            process.destroy();
        }
    }
}

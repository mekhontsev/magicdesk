package io.github.mekhontsev.magicdesk.displayfixes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/** Executes one explicit command through the user's installed su provider. */
final class RootCommandRunner {
    private RootCommandRunner() {
    }

    static Result run(final String command) throws IOException {
        final Process process = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start();
        final StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        try {
            return new Result(process.waitFor(), output.toString().trim());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("root command interrupted", error);
        } finally {
            process.destroy();
        }
    }

    static final class Result {
        final int exitCode;
        final String output;

        Result(final int exitCode, final String output) {
            this.exitCode = exitCode;
            this.output = output == null ? "" : output;
        }

        boolean succeeded() {
            return exitCode == 0;
        }
    }
}

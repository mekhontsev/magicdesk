package io.github.mekhontsev.magicdesk;

import java.io.IOException;

/** Reads a bounded snapshot of Android's input state. */
final class InputStateDump {
    private static final String DUMPSYS = "/system/bin/dumpsys";
    private static final long TIMEOUT_MILLIS = 3_000L;
    private static final int OUTPUT_LIMIT_BYTES = 1024 * 1024;

    private InputStateDump() {
    }

    static String read() throws IOException, InterruptedException {
        final Process process = new ProcessBuilder(DUMPSYS, "input")
                .redirectErrorStream(true)
                .start();
        final BoundedProcessRunner.Result result = BoundedProcessRunner.run(
                process, TIMEOUT_MILLIS, OUTPUT_LIMIT_BYTES);
        if (result.exitCode != 0) {
            throw new IOException(
                    "dumpsys input failed with exit code " + result.exitCode);
        }
        if (result.truncated) {
            throw new IOException("dumpsys input output was truncated");
        }
        return result.output;
    }
}

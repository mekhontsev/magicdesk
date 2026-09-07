package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.util.Locale;

/** Discovers DisplayManager power commands without changing any display. */
public final class DisplayPowerCommands {
    public static final String POWER_OFF = "power-off";
    public static final String POWER_RESET = "power-reset";
    public static final String POWER_ON = "power-on";

    @FunctionalInterface
    public interface CommandReader {
        String read(String operation) throws IOException;
    }

    @FunctionalInterface
    interface Reporter {
        void report(String key, String state, String detail);
    }

    private DisplayPowerCommands() {
    }

    public static String resolveRestoreOperation(final CommandReader reader)
            throws IOException {
        return resolveRestoreOperation(reader.read("help"), reader);
    }

    private static String resolveRestoreOperation(
            final String help, final CommandReader reader) throws IOException {
        if (advertises(help, POWER_RESET)) {
            return POWER_RESET;
        }
        if (advertises(help, POWER_ON)) {
            return POWER_ON;
        }
        if (recognizes(reader.read(POWER_RESET))) {
            return POWER_RESET;
        }
        return recognizes(reader.read(POWER_ON)) ? POWER_ON : null;
    }

    public static boolean isRestoreOperation(final String operation) {
        return POWER_RESET.equals(operation) || POWER_ON.equals(operation);
    }

    static boolean advertises(final String help, final String operation) {
        if (help != null) {
            for (final String line : help.split("\\R")) {
                if (operation.equals(line.trim().split("\\s+", 2)[0])) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean recognizes(final String output) throws IOException {
        final String normalized = output == null
                ? "" : output.toLowerCase(Locale.ROOT);
        if (normalized.contains("unknown command")) {
            return false;
        }
        // Android 15 may omit supported commands from help. Omitting the
        // display ID exercises argument validation, never a power transition.
        if (normalized.contains("no displayid specified")) {
            return true;
        }
        throw new IOException("display power probe was inconclusive: " + output);
    }

    static void probe(final Reporter reporter) {
        probe(reporter, DisplayPowerCommands::readLocal);
    }

    static void probe(final Reporter reporter, final CommandReader reader) {
        final String offKey = "display.power_off";
        final String restoreKey = "display.power_restore";
        final String help;
        try {
            help = reader.read("help");
        } catch (IOException error) {
            reportError(reporter, offKey, error);
            reportError(reporter, restoreKey, error);
            return;
        }
        try {
            final boolean present = advertises(help, POWER_OFF)
                    || recognizes(reader.read(POWER_OFF));
            reporter.report(offKey,
                    present ? "declared" : "unavailable",
                    "cmd display power-off; no display state changed");
        } catch (IOException error) {
            reportError(reporter, offKey, error);
        }
        try {
            final String operation = resolveRestoreOperation(help, reader);
            reporter.report(restoreKey,
                    operation != null ? "declared" : "unavailable",
                    operation != null ? "cmd display " + operation
                            + "; no display state changed" : "no restore command");
        } catch (IOException error) {
            reportError(reporter, restoreKey, error);
        }
    }

    private static void reportError(
            final Reporter reporter, final String key, final IOException error) {
        reporter.report(key, "error", error.toString());
    }

    private static String readLocal(final String operation) throws IOException {
        final Process process = new ProcessBuilder(
                "/system/bin/cmd", "display", operation)
                .redirectErrorStream(true).start();
        try {
            final BoundedProcessRunner.Result result =
                    BoundedProcessRunner.run(process, 2_000L, 16 * 1024);
            if (result.truncated) {
                throw new IOException("display power probe output was truncated");
            }
            return result.output;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("display power probe interrupted", error);
        } finally {
            process.destroy();
        }
    }
}

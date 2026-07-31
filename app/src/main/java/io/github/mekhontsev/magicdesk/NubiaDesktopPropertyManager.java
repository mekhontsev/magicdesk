package io.github.mekhontsev.magicdesk;

import java.io.IOException;

/**
 * Applies the two reviewed REDMAGIC desktop properties from the application UID.
 *
 * <p>The verified firmware service clears the Binder caller identity before
 * writing a system property. Keep this wrapper deliberately closed: callers
 * select a known property and a boolean value, never an arbitrary key.</p>
 */
final class NubiaDesktopPropertyManager {
    private static final String GETPROP = "/system/bin/getprop";
    private static final String SERVICE = "/system/bin/service";
    private static final String REDMAGIC_SERVICE = "redmagic.app.manager";
    private static final String SET_PROPERTY_TRANSACTION = "2";
    private static final long COMMAND_TIMEOUT_MILLIS = 5_000L;
    private static final int MAX_OUTPUT_BYTES = 32 * 1024;

    enum Property {
        DEVICE_RESTRICTIONS(
                "persist.wm.debug.desktop_mode_enforce_device_restrictions"),
        ROUNDED_CORNERS(
                "persist.wm.debug.desktop_use_rounded_corners");

        final String key;

        Property(final String key) {
            this.key = key;
        }
    }

    private NubiaDesktopPropertyManager() {
    }

    static String read(final Property property) throws IOException {
        requireProperty(property);
        final BoundedProcessRunner.Result result = run(
                new ProcessBuilder(GETPROP, property.key));
        requireSuccess("read " + property.key, result);
        final String value = result.output.trim();
        requireBooleanOrEmpty(value);
        return value;
    }

    static void write(final Property property, final String value)
            throws IOException {
        requireProperty(property);
        requireBooleanOrEmpty(value);
        if (value.equals(read(property))) {
            return;
        }

        final BoundedProcessRunner.Result result = run(
                new ProcessBuilder(
                        SERVICE,
                        "call",
                        REDMAGIC_SERVICE,
                        SET_PROPERTY_TRANSACTION,
                        "s16",
                        property.key,
                        "s16",
                        value));
        requireSuccess("write " + property.key, result);

        final String observed = read(property);
        if (!value.equals(observed)) {
            throw new IOException(
                    "REDMAGIC property verification failed for "
                            + property.key
                            + ": expected=" + printable(value)
                            + " observed=" + printable(observed));
        }
    }

    static boolean isBooleanOrEmpty(final String value) {
        return value != null
                && (value.isEmpty()
                        || "true".equals(value)
                        || "false".equals(value));
    }

    private static BoundedProcessRunner.Result run(final ProcessBuilder builder)
            throws IOException {
        builder.redirectErrorStream(true);
        final Process process = builder.start();
        try {
            return BoundedProcessRunner.run(
                    process,
                    COMMAND_TIMEOUT_MILLIS,
                    MAX_OUTPUT_BYTES);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("REDMAGIC property command interrupted", error);
        } finally {
            process.destroy();
        }
    }

    private static void requireSuccess(
            final String operation,
            final BoundedProcessRunner.Result result) throws IOException {
        if (result.exitCode != 0) {
            throw new IOException(
                    "could not " + operation
                            + ": exit=" + result.exitCode
                            + outputSuffix(result.output));
        }
        if (result.truncated) {
            throw new IOException(
                    "could not " + operation + ": command output was truncated");
        }
    }

    private static void requireProperty(final Property property) {
        if (property == null) {
            throw new IllegalArgumentException("property is null");
        }
    }

    private static void requireBooleanOrEmpty(final String value) {
        if (!isBooleanOrEmpty(value)) {
            throw new IllegalArgumentException(
                    "desktop property value must be true, false, or empty");
        }
    }

    private static String printable(final String value) {
        return value.isEmpty() ? "<empty>" : value;
    }

    private static String outputSuffix(final String output) {
        final String oneLine = output.trim()
                .replace('\n', ' ')
                .replace('\r', ' ');
        return oneLine.isEmpty() ? "" : ": " + oneLine;
    }
}

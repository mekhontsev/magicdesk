package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.BoundedProcessRunner;
import io.github.mekhontsev.magicdesk.PlatformDevice;

import java.io.IOException;
import java.util.Locale;

/** Detects the firmware service required by the Nubia platform adapter. */
public final class NubiaFirmwareDetector {
    private static final String SERVICE_COMMAND = "/system/bin/service";
    private static final String PLATFORM_SERVICE = "redmagic.app.manager";
    private static final long TIMEOUT_MILLIS = 1_000L;
    private static final int MAX_OUTPUT_BYTES = 4 * 1024;

    private NubiaFirmwareDetector() {
    }

    public static boolean isAvailable(final PlatformDevice device) {
        return isAvailable(device, hasPlatformService());
    }

    static boolean isAvailable(
            final PlatformDevice device,
            final boolean platformServicePresent) {
        if (platformServicePresent) {
            return true;
        }
        if (device == null) {
            return false;
        }
        final String fingerprint = device.fingerprint.toLowerCase(Locale.US);
        return fingerprint.startsWith("nubia/")
                || fingerprint.startsWith("redmagic/");
    }

    private static boolean hasPlatformService() {
        final Process process;
        try {
            process = new ProcessBuilder(
                    SERVICE_COMMAND, "check", PLATFORM_SERVICE)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException error) {
            return false;
        }

        try {
            final BoundedProcessRunner.Result result =
                    BoundedProcessRunner.run(
                            process, TIMEOUT_MILLIS, MAX_OUTPUT_BYTES);
            return !result.truncated && reportsPresent(result.output);
        } catch (IOException error) {
            return false;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            process.destroy();
        }
    }

    static boolean reportsPresent(final String output) {
        if (output == null) {
            return false;
        }
        final String expected = "Service " + PLATFORM_SERVICE + ": found";
        for (final String line : output.split("\\R")) {
            if (expected.equals(line.trim())) {
                return true;
            }
        }
        return false;
    }
}

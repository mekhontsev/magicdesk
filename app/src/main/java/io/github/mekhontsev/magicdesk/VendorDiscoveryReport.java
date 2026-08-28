package io.github.mekhontsev.magicdesk;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/** Explicit, bounded, read-only inventory for onboarding unknown firmware. */
final class VendorDiscoveryReport {
    private static final String FILE_NAME = "vendor-discovery.txt";
    private static final int MAX_SECTION_CHARS = 20_000;
    private static final int MAX_REPORT_CHARS = 60_000;

    private static final String[] PROPERTY_KEYS = {
        "ro.hardware",
        "ro.boot.hardware",
        "ro.soc.manufacturer",
        "ro.soc.model",
        "ro.board.platform",
        "ro.vendor.build.fingerprint",
        "ro.build.version.release",
        "ro.build.version.sdk"
    };

    private VendorDiscoveryReport() {
    }

    static String collect(final Context context) throws IOException {
        if (!ShellAccess.isReady()) {
            throw new IOException("running shell access is required");
        }
        final PlatformDevice device = PlatformDevice.current();
        final StringBuilder report = new StringBuilder(32_000)
                .append("## Extended vendor discovery\n")
                .append("Format: 1\n")
                .append("App: ").append(BuildConfig.VERSION_NAME)
                .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
                .append("Package: ").append(context.getPackageName())
                .append('\n')
                .append("Fingerprint: ").append(device.fingerprint).append('\n')
                .append("Platform: ")
                .append(PlatformDrivers.current().selection().summary())
                .append('\n')
                .append("Scope: read-only firmware services, commands, and framework names\n")
                .append("Properties:\n");
        for (final String key : PROPERTY_KEYS) {
            appendCommand(
                    report,
                    key,
                    "/system/bin/getprop " + key,
                    1_000);
        }
        appendCommand(report, "Binder services", "/system/bin/service list",
                MAX_SECTION_CHARS);
        appendCommand(report, "cmd services", "/system/bin/cmd -l",
                MAX_SECTION_CHARS);
        appendCommand(
                report,
                "Framework files",
                "/system/bin/find /system/framework /system_ext/framework"
                        + " /vendor/framework -maxdepth 1 -type f 2>/dev/null"
                        + " | /system/bin/sort",
                MAX_SECTION_CHARS);
        if (report.length() > MAX_REPORT_CHARS) {
            report.setLength(MAX_REPORT_CHARS);
            report.append("\n[report truncated]\n");
        }
        report.append('\n');
        return report.toString();
    }

    static void save(final Context context, final String report)
            throws IOException {
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(
                        new File(context.getFilesDir(), FILE_NAME), false),
                StandardCharsets.UTF_8)) {
            writer.write(report == null ? "" : report);
        }
    }

    static void appendSaved(
            final StringBuilder report,
            final Context context) {
        final File file = new File(context.getFilesDir(), FILE_NAME);
        if (!file.isFile()) {
            report.append("## Extended vendor discovery\nNot collected\n\n");
            return;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            final char[] buffer = new char[2_048];
            int read;
            int total = 0;
            while ((read = reader.read(buffer)) >= 0
                    && total < MAX_REPORT_CHARS) {
                final int accepted = Math.min(
                        read, MAX_REPORT_CHARS - total);
                report.append(buffer, 0, accepted);
                total += accepted;
            }
            if (report.length() > 0
                    && report.charAt(report.length() - 1) != '\n') {
                report.append('\n');
            }
        } catch (IOException error) {
            report.append("## Extended vendor discovery\nUnavailable: ")
                    .append(error.getMessage()).append("\n\n");
        }
    }

    private static void appendCommand(
            final StringBuilder report,
            final String label,
            final String command,
            final int maxChars) {
        report.append(label).append(":\n");
        try {
            final ShellAccess.CommandResult result =
                    ShellAccess.executeCommand(command);
            final String output = result.output == null ? "" : result.output;
            report.append(output, 0, Math.min(output.length(), maxChars));
            if (output.length() > maxChars) {
                report.append("\n[section truncated]");
            }
            if (result.exitCode != 0) {
                report.append("\n[exit=").append(result.exitCode).append(']');
            }
        } catch (IOException | RuntimeException error) {
            report.append("unavailable: ")
                    .append(error.getMessage() == null
                            ? error.getClass().getSimpleName()
                            : error.getMessage());
        }
        report.append('\n');
    }
}
